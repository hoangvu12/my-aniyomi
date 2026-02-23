pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "aniyomi-extensions"

include(":core")

// Load library modules
File(rootDir, "lib").eachDir {
    include(":lib:${it.name}")
}

// Load multi-source library modules
File(rootDir, "lib-multisrc").eachDir {
    include(":lib-multisrc:${it.name}")
}

// Load extension modules
val extensionsDir = File(rootDir, "src")
if (extensionsDir.exists()) {
    extensionsDir.eachDir { langDir ->
        langDir.eachDir { extensionDir ->
            val hasGradle = extensionDir.listFiles()?.any {
                it.name == "build.gradle" || it.name == "build.gradle.kts"
            } ?: false
            if (hasGradle) {
                include(":src:${langDir.name}:${extensionDir.name}")
            }
        }
    }
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && !file.name.startsWith('.')) {
            block(file)
        }
    }
}
