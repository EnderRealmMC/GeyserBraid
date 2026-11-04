plugins {
    id("java")
}

group = "cn.enderrealm"

// 发布时由 CI 从标签注入（tag v0.1.0 -> buildVersion=0.1.0），开发态默认快照版本
version = (findProperty("buildVersion") as String?) ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        // Aligned with Geyser master (2.12.x): its base convention targets Java 21
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    // Geyser core — provided by the Geyser platform at runtime; its transitive
    // dependencies (Netty, gson, fastutil, ...) ride along on the compile classpath
    compileOnly("org.geysermc.geyser:core:2.12.1-SNAPSHOT")

    // Protocol and transport libraries — versions aligned with Geyser master's
    // gradle/libs.versions.toml
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta15-20261026.210753-2")
    compileOnly("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta15-20261026.210753-2")
    compileOnly("org.cloudburstmc.netty:netty-transport-raknet:2.0.0.CR6-20261030.131938-23")
    compileOnly("org.cloudburstmc.netty:netty-transport-nethernet:2.0.0.CR6-20261030.131938-23")
    compileOnly("org.cloudburstmc.netty:netty-external-signaling:2.0.0.CR6-20261030.131938-23")
    compileOnly("dev.opencollab:libdatachannel-java-arch-detect:0.24.6.0-20261029.091642-10")
}

// The submodule build's short git commit, injected into the jar manifest
val braidGitCommit: String = try {
    providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    "unknown"
}

// extension.yml and the jar manifest receive the Gradle version (single source of truth)
tasks.processResources {
    filesMatching("extension.yml") {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to "$version (git-$braidGitCommit)")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
