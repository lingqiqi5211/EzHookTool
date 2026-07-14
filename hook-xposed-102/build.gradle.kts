plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "io.github.lingqiqi5211.ezhooktool.xposed102"
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.libxposedApi)
    testImplementation(libs.junit.api)
    testImplementation(libs.libxposedApi)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}

dokka {
    dokkaPublications.html {
        moduleName.set("hook-xposed-102")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}
