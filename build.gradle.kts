plugins {
    java
}

group = "dev.eyesky"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Ресурспак собирается в zip и кладётся ВНУТРЬ jar плагина.
val packZip = tasks.register<Zip>("packZip") {
    from("resourcepack")
    archiveFileName.set("eyesky-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(packZip)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
