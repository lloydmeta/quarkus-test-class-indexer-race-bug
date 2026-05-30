/*
 * Diagnostic-only Java agent. NOT part of the bug.
 *
 * The bug: io.quarkus.test.common.TestClassIndexer.writeIndex opens
 * `<testClassesDir>/test-classes.idx` with FileOutputStream(file, false), which
 * truncates the file before any bytes are written, and then streams a Jandex
 * index through IndexWriter. A concurrent fork can land in
 * TestClassIndexer.readIndex during this window and either see no bytes
 * (EOFException, caught and recovered as a re-index) or see partial / scrambled
 * bytes that don't start with the Jandex magic (IllegalArgumentException
 * "Not a jandex index", NOT caught: readIndex only catches IOException). On a
 * fast laptop the window is microseconds; on slower CI it widens to dozens of
 * ms and we observe the second case in production runs.
 *
 * This agent makes the second case observable locally by replicating its shape:
 * at the entry of writeIndex it truncates the index file, writes 4 non-magic
 * bytes, and sleeps ~75 ms (with jitter so concurrent forks fall out of phase).
 * The original writeIndex then runs to completion and writes the real Jandex
 * index, restoring the file. While we sleep, any concurrent reader fork sees
 * 4 bytes that fail Jandex's magic check and throws "Not a jandex index", which
 * is exactly the failure observed in production.
 *
 * The agent does NOT introduce the bug. The smoking gun:
 *   * `./gradlew clean test -Pforks=1`                ALWAYS passes (no race possible)
 *   * `./gradlew clean test -Pforks=4`                may pass on a fast machine
 *   * `./gradlew clean test -Pforks=4 -Pwiden-window` reliably fails ("Not a jandex index")
 *
 * The 4-byte non-magic prefix is a deterministic stand-in for the
 * mid-write-and-multi-writer-interleaving state the real race produces; it lets
 * us hit the failure on every CI run instead of relying on incidental slowness.
 */
package com.beachape.widen;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.FileOutputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

public final class WidenWindowAgent {

    /** Mean sleep, ms. */
    static final long SLEEP_MS = 75;
    /** Jitter range, ms. Each call sleeps SLEEP_MS + uniform(-JITTER_MS, +JITTER_MS). */
    static final long JITTER_MS = 60;

    private WidenWindowAgent() {}

    public static void premain(String args, Instrumentation inst) {
        System.err.println(
                "[widen-window] installed; will write 4 non-magic bytes + sleep "
                        + SLEEP_MS
                        + " +/- "
                        + JITTER_MS
                        + " ms at entry of TestClassIndexer.writeIndex");

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .type(named("io.quarkus.test.common.TestClassIndexer"))
                .transform(
                        (builder, type, classLoader, module, pd) ->
                                builder.visit(
                                        Advice.to(WriteIndexAdvice.class)
                                                .on(
                                                        named("writeIndex")
                                                                .and(takesArguments(3)))))
                .installOn(inst);
    }

    /** Code injected at the entry of writeIndex. Public so Byte Buddy can read it. */
    public static class WriteIndexAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(1) Path testClassLocation) {
            // Truncate the index file and write 4 bytes that aren't Jandex magic. Mirrors
            // the partial-write state the real race exposes (truncate-then-fill is
            // non-atomic; concurrent writers and buffered output can leave a brief window
            // where the file's leading bytes don't match the Jandex magic).
            try (FileOutputStream fos =
                    new FileOutputStream(
                            testClassLocation.resolve("test-classes.idx").toFile(), false)) {
                fos.write(new byte[] {0x00, 0x00, 0x00, 0x00});
                fos.flush();
            } catch (Exception ignored) {
                // mirror writeIndex's IOException swallow
            }
            // Sleep with jitter so concurrent forks fall out of phase and a reader has a
            // wide chance of landing in the bad-bytes window.
            long jitter = ThreadLocalRandom.current().nextLong(-JITTER_MS, JITTER_MS + 1);
            try {
                Thread.sleep(Math.max(1, SLEEP_MS + jitter));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Original writeIndex runs after this; it'll truncate again and write the
            // real Jandex index, restoring the file.
        }
    }
}
