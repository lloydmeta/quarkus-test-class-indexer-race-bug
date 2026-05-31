/*
 * Always-on Byte Buddy javaagent that patches io.quarkus.test.common.TestClassIndexer
 * to close the truncate-to-flush race described in the README:
 *
 *  - writeIndex(Index, Path, Class): replaced with atomic temp-file + Files.move
 *    using StandardCopyOption.ATOMIC_MOVE (falling back to non-atomic move if
 *    the filesystem doesn't support it). Readers therefore never observe a
 *    partially-written file: target is always either the previous complete
 *    index or the new complete index.
 *
 *  - readIndex(Path, Class): on IllegalArgumentException out of IndexReader
 *    (e.g. "Not a jandex index" from readVersion), fall back to indexTestClasses
 *    the same way the existing IOException catch does. Pure defence-in-depth
 *    once the atomic write is in place; included so a hypothetical un-agented
 *    writer (parallel build via IDE, etc.) couldn't kill our test workers.
 *
 * Ordering note: when this agent is loaded alongside src/widenWindow/...'s
 * WidenWindowAgent, this agent must be the LAST -javaagent on the command
 * line so its transformer runs last and its @Advice.OnMethodEnter is inlined
 * at the very top of writeIndex. The skipOn then bypasses widen's onEnter
 * entirely - which is the correct semantic, since the atomic write means
 * widen's chosen failure mode (sparse hole) cannot occur on disk.
 */
package com.beachape.fix;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.UnsupportedVersion;

import io.quarkus.test.common.TestClassIndexer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

public final class FixAgent {

    private FixAgent() {}

    public static void premain(String args, Instrumentation inst) {
        System.err.println(
                "[fix-agent] installed; patching TestClassIndexer.writeIndex"
                        + " (atomic temp + ATOMIC_MOVE) and TestClassIndexer.readIndex"
                        + " (IAE-tolerant fallback to indexTestClasses)");

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .type(named("io.quarkus.test.common.TestClassIndexer"))
                .transform(
                        (builder, type, classLoader, module, pd) ->
                                builder
                                        .visit(
                                                Advice.to(WriteIndexAdvice.class)
                                                        .on(
                                                                named("writeIndex")
                                                                        .and(takesArguments(3))))
                                        .visit(
                                                Advice.to(ReadIndexAdvice.class)
                                                        .on(
                                                                named("readIndex")
                                                                        .and(takesArguments(2)))))
                .installOn(inst);
    }

    /**
     * Replaces writeIndex(Index, Path, Class) with an atomic temp-file + rename. The
     * @Advice.OnMethodEnter return value of 1 triggers skipOn, which bypasses the
     * original method body (and any other advice inlined below this one).
     *
     * <p>Critically the temp file is NOT created in {@code testClassLocation} itself
     * because that directory is a Quarkus classpath element which Quarkus walks via
     * {@code PathTreeClassPathElement.getProvidedResources}. If a temp file sat
     * there during the walk, the rename would race the walker's {@code
     * Files.readAttributes} call and surface as {@code NoSuchFileException} for the
     * temp file. We instead use a dedicated sibling directory on the same
     * filesystem so {@link StandardCopyOption#ATOMIC_MOVE} still applies but the
     * walker never sees the temp.
     */
    public static class WriteIndexAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static int onEnter(
                @Advice.Argument(0) Index index,
                @Advice.Argument(1) Path testClassLocation) {
            Path target = testClassLocation.resolve("test-classes.idx");
            Path tmpDir = null;
            Path tmp = null;
            try {
                Path parent = testClassLocation.getParent();
                tmpDir =
                        parent != null
                                ? parent.resolve(".fix-agent-idx-tmp")
                                : target.getParent();
                Files.createDirectories(tmpDir);
                tmp = Files.createTempFile(tmpDir, "test-classes.idx.", ".tmp");
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    new IndexWriter(os).write(index);
                }
                try {
                    Files.move(
                            tmp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignore) {
                    }
                }
            }
            return 1;
        }
    }

    /**
     * Wraps readIndex(Path, Class) to recover from IllegalArgumentException out of
     * IndexReader (e.g. "Not a jandex index"). UnsupportedVersion is preserved -
     * upstream rewraps it with the path and rethrows, and we want that behaviour
     * to keep surfacing.
     */
    public static class ReadIndexAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(
                @Advice.Argument(1) Class<?> testClass,
                @Advice.Return(readOnly = false) Index returned,
                @Advice.Thrown(readOnly = false) Throwable thrown) {
            if (thrown instanceof IllegalArgumentException
                    && !(thrown instanceof UnsupportedVersion)) {
                try {
                    returned = TestClassIndexer.indexTestClasses(testClass);
                    thrown = null;
                    System.err.println(
                            "[fix-agent] readIndex recovered from IAE via indexTestClasses;"
                                    + " thread="
                                    + Thread.currentThread().getName());
                } catch (Throwable t) {
                    // give up; let original IAE propagate
                }
            }
        }
    }
}
