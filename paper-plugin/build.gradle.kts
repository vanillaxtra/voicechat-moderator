plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.voicechat"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.13")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.4")

    // Bundled via shadow (not provided by Paper)
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("voicechat-moderator.jar")

    // HikariCP is pure-Java — safe to relocate
    relocate("com.zaxxer.hikari", "dev.voicechat.libs.hikari")

    // SQLite JDBC uses JNI — relocating the Java class breaks native method lookup,
    // so we leave org.sqlite in-place and only strip the unused native binaries.

    // Keep only Windows x64 + Linux x64 natives; strip the rest to shrink the JAR
    exclude("org/sqlite/native/Mac/**")
    exclude("org/sqlite/native/FreeBSD/**")
    exclude("org/sqlite/native/Android/**")
    exclude("org/sqlite/native/Linux/aarch64/**")
    exclude("org/sqlite/native/Linux/arm/**")
    exclude("org/sqlite/native/Linux/armv6/**")
    exclude("org/sqlite/native/Linux/armv7/**")
    exclude("org/sqlite/native/Linux/ppc64/**")
    exclude("org/sqlite/native/Linux/x86/**")    // 32-bit Linux
    exclude("org/sqlite/native/Windows/x86/**")  // 32-bit Windows
}

// Disable plain jar — shadow jar is the deployment artifact
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
