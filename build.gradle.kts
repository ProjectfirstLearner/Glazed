import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

plugins {
    id("fabric-loom") version "1.14.1"
}

val selectedMcProfile = (findProperty("minecraft_profile") as String?)
    ?.trim()
    .orEmpty()
    .ifEmpty { "1.21.11" }

fun profileKey(prefix: String): String {
    val normalizedProfile = selectedMcProfile.replace('.', '_')
    return "${prefix}_$normalizedProfile"
}

fun resolveProfileValue(prefix: String, fallbackKey: String): String {
    val profileValue = (findProperty(profileKey(prefix)) as String?)?.trim()
    if (!profileValue.isNullOrEmpty()) return profileValue

    val fallbackValue = (findProperty(fallbackKey) as String?)?.trim()
    if (!fallbackValue.isNullOrEmpty()) return fallbackValue

    error("Missing Gradle property '${profileKey(prefix)}' (or fallback '$fallbackKey').")
}

val minecraftVersion = resolveProfileValue("minecraft_version", "minecraft_version")
val yarnMappings = resolveProfileValue("yarn_mappings", "yarn_mappings")
val loaderVersion = resolveProfileValue("loader_version", "loader_version")
val baritoneVersion = resolveProfileValue("baritone_version", "baritone_version")
val modVersion = resolveProfileValue("mod_version", "mod_version")

val copyAfterBuild = when ((findProperty("copy_after_build") as String?)?.trim()?.lowercase()) {
    "false", "0", "no" -> false
    else -> true
}

val outputProfile = (findProperty("build_output_profile") as String?)
    ?.trim()
    ?.lowercase()
    ?: "prism"

val rawOutputDirs = (findProperty("build_output_dirs") as String?)
    ?.trim()
    .orEmpty()

val prismModsDir = "${System.getenv("APPDATA")}/PrismLauncher/instances/Glazed/minecraft/mods"

fun resolveOutputDirectories(): List<File> {
    val profileDirs = when (outputProfile) {
        "none" -> emptyList()
        "prism" -> listOf(prismModsDir)
        else -> listOf(outputProfile)
    }

    val customDirs = rawOutputDirs
        .split(';', ',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val merged = (profileDirs + customDirs).distinct()
    return merged.map { File(it) }
}

base {
    archivesName = properties["archives_base_name"] as String
    version = modVersion
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        url = uri("https://jitpack.io")
    }
    maven {
        name = "Bawnorton"
        url = uri("https://maven.bawnorton.com/releases")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("meteordevelopment:meteor-client:$minecraftVersion-SNAPSHOT")
    modImplementation("meteordevelopment:baritone:$baritoneVersion-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.10.1")
    include(implementation(annotationProcessor("com.github.bawnorton.mixinsquared:mixinsquared-fabric:0.3.7-beta.1")!!)!!)
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion,
        )
        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }
    jar {
        val licenseSuffix = project.base.archivesName.get()
        from("LICENSE") {
            rename { "${it}_${licenseSuffix}" }
        }
    }
    register<Task>("copyBuiltJar") {
        dependsOn("remapJar")
        group = "build"
        description = "Copies the built JAR to configured output folder(s)."
        notCompatibleWithConfigurationCache("Uses dynamic output directory resolution and file operations.")

        val archivesBaseName = base.archivesName.get()
        val projectVersion = version.toString()
        val buildLibsDir = layout.buildDirectory.dir("libs").get().asFile

        doLast {
            if (!copyAfterBuild) {
                println("Skipping jar copy because copy_after_build=false")
                return@doLast
            }

            val outputDirs = resolveOutputDirectories()
            if (outputDirs.isEmpty()) {
                println("No output directory selected. Set build_output_profile or build_output_dirs.")
                return@doLast
            }

            val jarFileName = "$archivesBaseName-$projectVersion.jar"
            val sourceFile = File(buildLibsDir, jarFileName)

            if (!sourceFile.exists()) {
                println("ERROR: Could not find remapped JAR at: ${sourceFile.absolutePath}")
                buildLibsDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".jar")) {
                        println("Available JAR: ${file.name}")
                    }
                }
                return@doLast
            }

            outputDirs.forEach { targetDir ->
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                    println("Created mods directory: ${targetDir.absolutePath}")
                }

                targetDir.listFiles()?.filter { file ->
                    file.name.startsWith(archivesBaseName) && file.name.endsWith(".jar")
                }?.forEach { file ->
                    file.delete()
                    println("Deleted existing mod: ${file.name}")
                }

                val targetFile = File(targetDir, sourceFile.name)
                sourceFile.copyTo(targetFile, overwrite = true)
                println("Copied mod to: ${targetFile.absolutePath}")
            }
        }
    }

    register<Task>("copyToPrismLauncher") {
        dependsOn("copyBuiltJar")
        group = "build"
        description = "Compatibility alias for copyBuiltJar."
    }

    build {
        finalizedBy("copyBuiltJar")
    }
    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
