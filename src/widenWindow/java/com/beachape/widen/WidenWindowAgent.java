/*
 * Diagnostic-only Java agent. NOT part of the bug.
 *
 * The agent does not call any operation `writeIndex` doesn't already call
 * (truncate via `ftruncate(2)`). The `setLength(SPARSE_HOLE_SIZE)` is a
 * deterministic stand-in for "another fork's fd is past offset N and continues
 * writing", which is what produces the leading sparse-zero bytes in production
 * via POSIX sparse-hole semantics. The agent reproduces that end state, not a
 * second concurrent writer.
 *
 * Smoking gun: `-Pforks=1` always passes, `-Pforks=4` may flake, `-Pforks=4
 * -Pwiden-window` reliably fails. See README for the full analysis.
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
    /**
     * Size to extend the file to. IndexReader.readVersion reads 4 bytes of magic, so
     * any value >= 4 produces a leading-zero prefix that fails its magic check.
     */
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
            System.err.println(
                    "[widen-window] writeIndex entered: thread="
                            + Thread.currentThread().getName()
                            + " path="
                            + testClassLocation);
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
                Thread.sleep(SLEEP_MS + jitter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Original writeIndex runs after this; it'll truncate again and write the
            // real Jandex index, restoring the file.
        }

        @Advice.OnMethodExit
        public static void onExit(@Advice.Argument(1) Path testClassLocation) {
            try {
                long size = testClassLocation.resolve("test-classes.idx").toFile().length();
                System.err.println(
                        "[widen-window] writeIndex exited: thread="
                                + Thread.currentThread().getName()
                                + " indexBytes="
                                + size);
            } catch (Exception ignored) {
            }
        }
    }
}

