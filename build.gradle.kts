// Minecraft 26.x ships unobfuscated, so this uses the non-remapping Fabric Loom.
plugins {
    id("net.fabricmc.fabric-loom") version "1.18.3"
}

val minecraft = property("minecraft_version") as String

version = "${property("mod_version")}+$minecraft"
group = "io.github.octomiku01"

base {
    archivesName = "mod-synchronizer"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

repositories {
    maven("https://maven.terraformersmc.com/releases") { content { includeGroup("com.terraformersmc") } }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    // Optional: the settings open from Mod Menu when it is installed, else from a button on the title screen
    compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}") { isTransitive = false }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "minecraft_version" to minecraft,
        "loader_version" to project.property("loader_version"),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}
