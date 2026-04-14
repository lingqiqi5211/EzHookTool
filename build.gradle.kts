plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

val publishGroup = providers.gradleProperty("GROUP").get()
val publishVersion = providers.gradleProperty("VERSION_NAME").get()

subprojects {
    group = publishGroup
    version = publishVersion
}

dependencies {
    dokka(project(":core"))
    dokka(project(":hook-xposed-82"))
    dokka(project(":hook-xposed-101"))
}

dokka {
    dokkaPublications.html {
        moduleName.set("EzHookTool")
        outputDirectory.set(layout.projectDirectory.dir("doc/api"))
        includes.from("doc/overview.md")
    }
}

tasks.register("generateApiDocs") {
    group = "documentation"
    description = "Generates aggregated API docs into doc/api"
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
}

tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "Publishes all library modules to Maven Local"
    dependsOn(
        ":core:publishToMavenLocal",
        ":hook-xposed-82:publishToMavenLocal",
        ":hook-xposed-101:publishToMavenLocal",
    )
}
