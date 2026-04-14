import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

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
        targetCompatibility = JavaVersion.VERSION_21
        sourceCompatibility = JavaVersion.VERSION_21
    }
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
    configure(AndroidSingleVariantLibrary(publishJavadocJar = false))
}
