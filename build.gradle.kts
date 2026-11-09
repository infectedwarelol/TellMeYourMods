plugins {
    java
}

group = "dev.infectedware"
// Single source of truth for the plugin version, filtered into plugin.yml.
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Built against the lowest supported API so 1.20.1+ servers can load it.
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    processResources {
        val properties = mapOf("version" to project.version)
        inputs.properties(properties)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(properties)
        }
    }

    jar {
        archiveBaseName.set("TellMeYourMods")
    }
}
