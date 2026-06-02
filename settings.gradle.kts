pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info/")
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent {
                snapshotsOnly()
            }
            content {
                includeGroup("io.github.libxposed")
            }
        }
    }
}

rootProject.name = "EzHookTool"

include(
    ":core",
    ":hook-xposed-82",
    ":hook-xposed-102",
    ":sample-xposed-82",
    ":sample-xposed-102"
)
