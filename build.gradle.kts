plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = properties["mod_version"] as String
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

    dependencies {
        // Fabric
        minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
        mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
        modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")

        // Meteor
        modImplementation("meteordevelopment:meteor-client:${properties["minecraft_version"] as String}-SNAPSHOT")


        // Baritone
        modImplementation("meteordevelopment:baritone:${properties["baritone_version"] as String}-SNAPSHOT")

        implementation("com.google.code.gson:gson:2.10.1")

        include(implementation(annotationProcessor("com.github.bawnorton.mixinsquared:mixinsquared-fabric:0.3.7-beta.1")!!)!!)



    }



    tasks {
        processResources {
            val propertyMap = mapOf(
                "version" to project.version,
                "mc_version" to project.property("minecraft_version"),
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

        register<Task>("copyToPrismLauncher") {
            dependsOn("remapJar")
            group = "build"
            description = "Copies the built JAR to PrismLauncher mods folder"
            
            val appDataDir = System.getenv("APPDATA")
            val modsDir = "$appDataDir/PrismLauncher/instances/Glazed/minecraft/mods"
            val archivesBaseName = base.archivesName.get()
            val projectVersion = version.toString()
            val buildLibsDir = layout.buildDirectory.dir("libs").get().asFile
            
            doLast {
                val targetDir = File(modsDir)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                    println("Created mods directory: $modsDir")
                }
                
                // Remove existing mod files with same base name
                targetDir.listFiles()?.filter { file ->
                    file.name.startsWith(archivesBaseName) && file.name.endsWith(".jar")
                }?.forEach { file ->
                    file.delete()
                    println("Deleted existing mod: ${file.name}")
                }
                
                // Copy the remapped jar from build/libs (production-ready)
                val jarFileName = "$archivesBaseName-$projectVersion.jar"
                val sourceFile = File(buildLibsDir, jarFileName)
                
                if (sourceFile.exists()) {
                    val targetFile = File(targetDir, sourceFile.name)
                    sourceFile.copyTo(targetFile, overwrite = true)
                    println("Copied mod to PrismLauncher: ${targetFile.absolutePath}")
                } else {
                    println("ERROR: Could not find remapped JAR at: ${sourceFile.absolutePath}")
                    buildLibsDir.listFiles()?.forEach { file ->
                        if (file.name.endsWith(".jar")) {
                            println("Available JAR: ${file.name}")
                        }
                    }
                }
            }
        }

        // Make the copy task run automatically after building
        build {
            finalizedBy("copyToPrismLauncher")
        }

        java {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 21
        }
    }
}

