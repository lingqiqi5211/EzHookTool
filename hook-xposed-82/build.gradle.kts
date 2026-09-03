plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "io.github.lingqiqi5211.ezhooktool.xposed82"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_25
        sourceCompatibility = JavaVersion.VERSION_25
    }
}

// 82 与 102 共用的源码。走 LibraryExtension：AGP 9 下 android { sourceSets["main"] } 的访问器会抛 ClassCastException。
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.getByName("main").kotlin.directories.add(rootProject.layout.projectDirectory.dir("shared-src").asFile.path)
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.xposed82Api)
}

dokka {
    dokkaPublications.html {
        moduleName.set("hook-xposed-82")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}
