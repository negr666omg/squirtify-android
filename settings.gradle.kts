pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google-hosted mirror of Maven Central. Keep Maven Central and the
        // Plugin Portal as fallbacks in case one endpoint is throttled.
        maven {
            name = "MavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Fallback mirror protects CI from transient Maven Central throttling.
        maven {
            name = "MavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}
rootProject.name = "Squirtify"
include(":app")
