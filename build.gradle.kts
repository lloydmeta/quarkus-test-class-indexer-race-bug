plugins {
    java
    id("io.quarkus") version "3.36.2"
}

repositories {
    mavenCentral()
    mavenLocal()
}

// Optional widen-window source set: builds a small Java agent that widens the
// TestClassIndexer.writeIndex race window from microseconds to ~75 ms so the
// race is reliably observable locally. NOT part of the bug. Opt-in via
// `-Pwiden-window`. See README and src/widenWindow/.../WidenWindowAgent.java.
sourceSets {
    create("widenWindow") {
        java.setSrcDirs(listOf("src/widenWindow/java"))
    }
}

val widenWindowImplementation by configurations.getting

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.36.0"))
    implementation("io.quarkus:quarkus-arc")
    testImplementation("io.quarkus:quarkus-junit5")

    widenWindowImplementation("net.bytebuddy:byte-buddy:1.18.10")
    widenWindowImplementation("net.bytebuddy:byte-buddy-agent:1.18.10")
}

group = "com.beachape"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// Generate a pile of dummy classes whose only purpose is to bloat the Jandex
// index so that writeIndex's truncate-to-flush window is wider in wall-clock
// time. Plain POJOs with a handful of methods/fields/annotations each: enough
// to give Jandex a non-trivial per-class entry without introducing any CDI/ARC
// machinery that Quarkus would react to.
val dummyCount = (project.findProperty("dummies") as String?)?.toInt() ?: 5000

val generateTestDummies = tasks.register("generateTestDummies") {
    val outDir = layout.buildDirectory.dir("generated-sources/test-dummies/java/com/beachape/dummies")
    inputs.property("dummyCount", dummyCount)
    outputs.dir(layout.buildDirectory.dir("generated-sources/test-dummies/java"))
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        for (i in 0 until dummyCount) {
            val name = "Dummy$i"
            val sb = StringBuilder()
            sb.append("package com.beachape.dummies;\n\n")
            sb.append("@Deprecated\n")
            sb.append("@SuppressWarnings(\"unused$i\")\n")
            sb.append("public final class $name {\n")
            for (f in 0 until 8) {
                sb.append("    @Deprecated private final int field${f} = $f;\n")
            }
            for (m in 0 until 8) {
                sb.append("    @SuppressWarnings(\"m$m\")\n")
                sb.append("    @Deprecated\n")
                sb.append("    public int method${m}(int a, int b) { return a + b + ${m}; }\n")
            }
            sb.append("}\n")
            dir.resolve("$name.java").writeText(sb.toString())
        }
    }
}

sourceSets["test"].java.srcDir(layout.buildDirectory.dir("generated-sources/test-dummies/java"))
tasks.named("compileTestJava") { dependsOn(generateTestDummies) }

val widenWindowAgentJar = tasks.register<Jar>("widenWindowAgentJar") {
    archiveBaseName.set("widen-window-agent")
    from(sourceSets["widenWindow"].output)
    from({
        configurations["widenWindowRuntimeClasspath"].map {
            if (it.isDirectory) it else zipTree(it)
        }
    }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "module-info.class",
        )
    }
    manifest {
        attributes(
            "Premain-Class" to "com.beachape.widen.WidenWindowAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Tunable from CLI: ./gradlew test -Pforks=4
    maxParallelForks = (project.findProperty("forks") as String?)?.toInt()
        ?: gradle.startParameter.maxWorkerCount
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    if (project.hasProperty("widen-window")) {
        dependsOn(widenWindowAgentJar)
        val agentJarPath = widenWindowAgentJar.get().archiveFile.get().asFile.absolutePath
        jvmArgumentProviders.add(
            CommandLineArgumentProvider { listOf("-javaagent:$agentJarPath") }
        )
    }
}
