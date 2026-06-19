# quarkus-test-class-indexer-race-bug

Minimal Quarkus + Gradle repro for a write/read race in
`io.quarkus.test.common.TestClassIndexer` that surfaces as
`IllegalArgumentException: Not a jandex index` when running tests in parallel.

## tl;dr

* `./gradlew clean test -Pforks=1`                     ALWAYS passes (no concurrency, no race)
* `./gradlew clean test -Pforks=16`                    flaky (observed 9 of 10 runs failing on a 12-core Apple Silicon laptop; see CI matrix for `ubuntu-latest` rate)

The smoking gun is `forks=1` vs `forks>1`: the project itself is thirty-two
trivial empty `@QuarkusTest` classes spread across eight `@TestProfile`s,
plus 5000 generated dummy classes whose only role is to bloat the Jandex index
file (~1.9 MB) so the truncate-to-flush window is wider in wall-clock time.
The bug exists regardless of suite size; the dummies just make it observable
in a few runs rather than over weeks of CI. There is nothing in the test code
that should care how many JVM forks JUnit runs in. The only thing that changes
is whether multiple forks share the same
`build/classes/java/test/test-classes.idx`.

## The bug

[`io.quarkus.test.common.TestClassIndexer.writeIndex`][writeIndex] uses
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
`writeIndex` once per distinct `@TestProfile` (verified: a `forks=1` run with
8 profiles emits exactly 8 `writeIndex` calls on `Test worker`, 
which also reports the resulting index size of ~1.9 MB) and `readIndex` once per test class (called from
[`TestBuildChainFunction.apply`][readIndex-caller] during build chain
construction, surfaced in the stack trace below).

[writeIndex]: https://github.com/quarkusio/quarkus/blob/3.36.0/test-framework/common/src/main/java/io/quarkus/test/common/TestClassIndexer.java#L52-L60
[readIndex-caller]: https://github.com/quarkusio/quarkus/blob/3.36.0/test-framework/junit5/src/main/java/io/quarkus/test/junit/TestBuildChainFunction.java

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

## Repro

Requires JDK 25.

```bash
git clone <this repo>
cd quarkus-test-class-indexer-race-bug

./gradlew clean test -Pforks=1                  # passes (control)
./gradlew clean test -Pforks=16                 # natural race; flaky (over-subscribed forks)
```

`-Pforks=N` sets `maxParallelForks` on the `Test` task. `forks=16` is enough
to over-subscribe a typical CI runner (`ubuntu-latest` is 4 vCPU on public
repos at time of writing, see [the GitHub-hosted runners reference][gh-runners])
and most developer laptops, which widens the truncate-to-flush window past
the race threshold. The default if `-Pforks` is omitted is
`gradle.startParameter.maxWorkerCount`, which mirrors how many real projects
configure it.

`-Pdummies=N` controls how many generated dummy classes are added to the test
classpath (default 5000) to bloat the Jandex index and shift the natural flake
rate. See the "Why two reproduction modes" table above for measured rates;
roughly: index < 200 KB never flakes locally, ~750 KB is ~60%, ~2 MB is ~90%.
Useful for bisecting "how big does the index need to be before this fires on
my hardware".

[gh-runners]: https://docs.github.com/en/actions/reference/runners/github-hosted-runners

CI runs all matrices on `ubuntu-latest`: see `.github/workflows/ci.yaml`. 
so each attempt's pass/fail is recorded independently. 

### About `maxParallelForks`

Gradle's [Test task documentation][gradle-max-parallel-forks] documents
`maxParallelForks` as the standard knob for parallel test execution and
explicitly warns about the relevant failure mode:

> When using parallel test execution, make sure your tests are properly
> isolated from one another. Tests that interact with the filesystem are
> particularly prone to conflict, causing intermittent test failures.

The user-visible tests in this repro don't touch the filesystem; the
filesystem conflict is entirely inside Quarkus's `TestClassIndexer`.

[gradle-max-parallel-forks]: https://docs.gradle.org/current/userguide/java_testing.html#test_execution

## Expected vs actual

Expected: `writeIndex`/`readIndex` either tolerate concurrent access or
fail in a recoverable way. `maxParallelForks > 1` is a documented Gradle
`Test` task property (see "About `maxParallelForks`" above), and the same
race also fires whenever two Gradle builds touch the same checkout
(CI matrix re-runs, IDE auto-run alongside `./gradlew test`, etc.).

Actual: with multiple forks sharing one test classes directory, `writeIndex`
truncate-then-fill races `readIndex`'s magic check, and the `IllegalArgumentException`
escapes `readIndex`'s narrow `IOException` catch, killing the test worker.

## Suggested fix

A few options come to mind:

* `writeIndex` could write atomically: write the full index to a temp file in
the same directory and `Files.move(..., ATOMIC_MOVE)` it into place
([`Files.move` ATOMIC_MOVE docs][atomic-move]; same-filesystem rename, which
is the case here since the temp file is in the same directory). Or hold an OS
file lock across the whole write.
* As defence-in-depth alongside the above, `readIndex` could also catch
`IllegalArgumentException` from `IndexReader` and fall back to
`indexTestClasses(testClass)` the same way it does for `IOException`. (This
alone is a band-aid, not a fix, but it would prevent the confusing
`FacadeClassLoader` failure mode while the underlying race exists.)

`removeIndex` (which deletes the index file) is similarly unsynchronised; it's
not part of this failure mode but might be worth eyeballing as part of any
fix.

[atomic-move]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardCopyOption.html#ATOMIC_MOVE


## Versions

* Quarkus 3.36.0
* Java 25 (Temurin)
* Gradle 9.5.1
