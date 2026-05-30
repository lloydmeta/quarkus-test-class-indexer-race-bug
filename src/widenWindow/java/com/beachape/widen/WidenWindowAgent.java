/*
 * Diagnostic-only Java agent. NOT part of the bug.
 *
 * The bug: io.quarkus.test.common.TestClassIndexer.writeIndex opens
 * `<testClassesDir>/test-classes.idx` with FileOutputStream(file, false), which
 * truncates the file before any bytes are written, and then streams a Jandex
 * index through IndexWriter. A concurrent fork can land in
 * TestClassIndexer.readIndex during this window and either see no bytes
 * (EOFException, caught and recovered as a re-index) or see bytes that don't
 * start with the Jandex magic (IllegalArgumentException "Not a jandex index",
 * NOT caught: readIndex only catches IOException). On a fast laptop the
 * window is microseconds; on slower CI it widens to dozens of ms and we
 * observe the second case in production runs.
 *
 * The non-magic-bytes case in production is a consequence of POSIX sparse-hole
 * semantics: when fork A truncates the index file while fork B has an open
 * FileOutputStream past offset N, fork B's subsequent writes land at fd_b's
 * current position and the kernel reports a file of size N+M with bytes
 * 0..N-1 being sparse zeros. A reader at that moment sees four zero bytes and
 * fails IndexReader's magic check.
 *
 * This agent reproduces that sparse-hole state deterministically using only
 * ftruncate (no byte writes): on entry to writeIndex it truncates the index
 * file to 0 then extends it back to 8 bytes via setLength, which the kernel
 * implements as a sparse hole. It then sleeps ~75 ms (with jitter, so forks
 * fall out of phase) and lets the original writeIndex run to completion, which
 * truncates again and writes the real Jandex index, restoring the file. While
 * we sleep, any concurrent reader fork sees the same sparse-zero leading bytes
 * the multi-writer race produces in production, and throws "Not a jandex index".
 *
 * The agent does NOT introduce the bug. The smoking gun:
 *   * `./gradlew clean test -Pforks=1`                ALWAYS passes (no race possible)
 *   * `./gradlew clean test -Pforks=4`                may pass on a fast machine
 *   * `./gradlew clean test -Pforks=4 -Pwiden-window` reliably fails ("Not a jandex index")
 */
package com.beachape.widen;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.RandomAccessFile;
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
    /** Size to extend the file to. Anything >= 4 makes IndexReader.readVersion fail. */
    static final long SPARSE_HOLE_SIZE = 8;

    private WidenWindowAgent() {}

    public static void premain(String args, Instrumentation inst) {
        System.err.println(
                "[widen-window] installed; will create a "
                        + SPARSE_HOLE_SIZE
                        + "-byte sparse hole + sleep "
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
            // Truncate-and-extend via ftruncate. The result is a SPARSE_HOLE_SIZE-byte
            // file with all sparse-hole bytes (read as zero) and no explicit byte
            // writes. This is the same end state POSIX produces when fork A truncates
            // the index while fork B has fd_b past offset N and continues writing.
            try (RandomAccessFile raf =
                    new RandomAccessFile(
                            testClassLocation.resolve("test-classes.idx").toFile(), "rw")) {
                raf.setLength(0);
                raf.setLength(SPARSE_HOLE_SIZE);
            } catch (Exception ignored) {
                // mirror writeIndex's IOException swallow
            }
            // Sleep with jitter so concurrent forks fall out of phase and a reader has a
            // wide chance of landing in the sparse-hole window.
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

