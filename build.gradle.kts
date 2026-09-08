plugins {
    java
    id("io.quarkus") version "3.36.0"
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.39.2"))
    implementation("io.quarkus:quarkus-arc")
    testImplementation("io.quarkus:quarkus-junit5")
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
}
