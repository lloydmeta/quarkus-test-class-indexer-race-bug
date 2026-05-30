# quarkus-test-class-indexer-race-bug

Minimal Quarkus + Gradle repro for a write/read race in
`io.quarkus.test.common.TestClassIndexer` that surfaces as
`IllegalArgumentException: Not a jandex index` when running tests in parallel.

## TL;DR

* `./gradlew clean test -Pforks=1`                     ALWAYS passes (no concurrency, no race)
* `./gradlew clean test -Pforks=4`                     intermittently fails on slow CI; can pass on a fast laptop
* `./gradlew clean test -Pforks=4 -Pwiden-window`      reliably fails (deterministic widening of the existing race)

The smoking gun is `forks=1` vs `forks>1`: the project itself is a handful of
trivial `@QuarkusTest` classes against `@TestProfile`s that hit `/hello`. There
is nothing in the test code that should care how many JVM forks JUnit runs in.
The only thing that changes is whether multiple forks share the same
`build/classes/java/test/test-classes.idx`.

## The bug

`io.quarkus.test.common.TestClassIndexer.writeIndex` uses
`new FileOutputStream(file, false)` to truncate-then-fill the index file:

```java
try (FileOutputStream fos = new FileOutputStream(indexPath(testClassLocation).toFile(), false)) {
    IndexWriter indexWriter = new IndexWriter(fos);
    indexWriter.write(index);
}
```

Truncate-then-fill is non-atomic. Between the truncate and the final byte
written, the file is empty or partially written. With `maxParallelForks > 1`,
several JVMs share one `build/classes/java/test/` directory and they each call
`writeIndex` (during `AppMakerHelper`/`FacadeClassLoader` bootstrap, once per
distinct `@TestProfile`) and `readIndex` (during test class discovery, once per
test class).

`readIndex` only catches `IOException`:

```java
try (FileInputStream fis = new FileInputStream(path.toFile())) {
    return new IndexReader(fis).read();
} catch (UnsupportedVersion e) {
    throw new UnsupportedVersion(...);
} catch (IOException e) {
    return indexTestClasses(testClass);
}
```

If the racing reader sees an empty file it gets `EOFException` (an
`IOException`) and re-indexes silently, fine. If it sees bytes that don't
start with the Jandex magic (multi-writer truncate interleaving, buffered
output flushed mid-record, etc.), `IndexReader.readVersion` throws
`IllegalArgumentException("Not a jandex index")` which is NOT caught and
propagates out of `Class.forName` as a Gradle test-worker fatal error before
JUnit can even start the test:

```
java.lang.RuntimeException: java.lang.IllegalArgumentException: Not a jandex index
    at io.quarkus.bootstrap.app.CuratedApplication.createAugmentor(CuratedApplication.java:145)
    at io.quarkus.test.junit.AppMakerHelper.prepare(AppMakerHelper.java:74)
    at io.quarkus.test.junit.AppMakerHelper.getStartupAction(AppMakerHelper.java:191)
    at io.quarkus.test.junit.classloading.FacadeClassLoader.getOrCreateRuntimeClassLoader(FacadeClassLoader.java:582)
    at io.quarkus.test.junit.classloading.FacadeClassLoader.getQuarkusClassLoader(FacadeClassLoader.java:498)
    at io.quarkus.test.junit.classloading.FacadeClassLoader.loadClass(FacadeClassLoader.java:366)
    at java.base/java.lang.Class.forName0(Native Method)
    ...
Caused by: java.lang.IllegalArgumentException: Not a jandex index
    at org.jboss.jandex.IndexReader.readVersion(IndexReader.java:115)
    at org.jboss.jandex.IndexReader.read(IndexReader.java:73)
    at io.quarkus.test.common.TestClassIndexer.readIndex(TestClassIndexer.java:77)
    at io.quarkus.test.junit.TestBuildChainFunction.apply(TestBuildChainFunction.java:46)
    ...
```

## Why two reproduction modes

On commodity hardware the truncate-to-write window is microseconds. On a busy
CI runner under contention it widens to dozens of milliseconds and the bug
fires intermittently (this is how it was first observed in production). To
make the bug reliably observable on any machine without depending on luck, this
repo ships an opt-in javaagent (`src/widenWindow/`) that widens the existing
race window from microseconds to ~75 ms (with jitter) by writing 4 non-magic
bytes to the index file at the entry of `writeIndex` before the original
`writeIndex` runs to completion.

The agent does not introduce a bug. It simulates the same partial-write state
the real race produces and lets the existing concurrent reader hit it
deterministically. The smoking gun is still `-Pforks=1` always passing.

See `src/widenWindow/java/com/beachape/widen/WidenWindowAgent.java` for the
full source and rationale.

## Repro

Requires JDK 25.

```bash
git clone <this repo>
cd quarkus-test-class-indexer-race-bug

./gradlew clean test -Pforks=1                  # passes (control)
./gradlew clean test -Pforks=4                  # may pass on a fast laptop, flaky on CI
./gradlew clean test -Pforks=4 -Pwiden-window   # reliably fails with "Not a jandex index"
```

`-Pforks=N` sets `maxParallelForks` on the `Test` task. Default is
`gradle.startParameter.maxWorkerCount`, which mirrors how many real projects
configure it.

CI runs all three matrices on `ubuntu-latest`: see `.github/workflows/ci.yaml`.

## Expected vs actual

Expected: `writeIndex`/`readIndex` are concurrency-safe, since Gradle's
`maxParallelForks > 1` is the documented and recommended way to speed up
`@QuarkusTest` suites.

Actual: with multiple forks sharing one test classes directory, `writeIndex`
truncate-then-fill races `readIndex`'s magic check, and the `IllegalArgumentException`
escapes `readIndex`'s narrow `IOException` catch, killing the test worker.

## Suggested fix

`writeIndex` should write atomically: either write the full index to a temp
file in the same directory and `Files.move(..., ATOMIC_MOVE)` it into place,
or hold an OS file lock across the whole write. Belt-and-braces, `readIndex`
should also catch `IllegalArgumentException` from `IndexReader` and fall back
to `indexTestClasses(testClass)` the same way it does for `IOException`.

## Versions

* Quarkus 3.36.0
* Java 25 (Temurin)
* Gradle 9.5.1
