plugins {
    java
    id("io.quarkus") version "3.36.0" // Inlined for Dependabot version management
}

repositories {
    mavenCentral()
    mavenLocal()
}

// Optional widen-window source set: builds a small Java agent that widens the
// TestClassIndexer.writeIndex race window from microseconds to 75 ms so the
// race is reliably observable locally. NOT part of the bug. Opt-in via
// `-Pwiden-window`. See README and src/widenWindow/.../WidenWindowAgent.java.
sourceSets {
    create("widenWindow") {
        java.setSrcDirs(listOf("src/widenWindow/java"))
    }
}

val widenWindowImplementation by configurations.getting

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.36.0")) // Inlined for Dependabot version management
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-arc")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")

    widenWindowImplementation("net.bytebuddy:byte-buddy:1.18.8")
    widenWindowImplementation("net.bytebuddy:byte-buddy-agent:1.18.8")
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
    // Default mirrors UIAM: gradle.startParameter.maxWorkerCount (== CPU count by default).
    maxParallelForks = (project.findProperty("forks") as String?)?.toInt()
        ?: gradle.startParameter.maxWorkerCount
    // With maxParallelForks > 1, every JVM tries to bind quarkus.http.test-port (default 8081).
    // Force port 0 so each fork gets an OS-assigned free port. Rest-assured picks it up via
    // RestAssured.port which Quarkus sets after binding.
    systemProperty("quarkus.http.test-port", "0")
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
