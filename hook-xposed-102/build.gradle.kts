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
    compileOnly(libs.libxposedApi)

    // 只为 @RequiresApi。CLASS retention，使用方的 lint 从 class 文件就能读到，运行期不需要它。
    compileOnly(libs.annotation.jvm)
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

// 102-only 成员只允许出现在 XposedApiCompat.Api102 网关里，别处一出现就构建失败。只查成员调用，不查类型引用。
val checkApi102Gateway = tasks.register("checkApi102Gateway") {
    group = "verification"
    description = "Fails if libxposed 102-only members are used outside XposedApiCompat.Api102"
    // shared-src 也编进本模块，同样要扫：它虽然「与 framework 无关」，但没人拦着往里写 102 调用。
    val sources = fileTree("src/main/kotlin") {
        include("**/*.kt")
        exclude("**/internal/XposedApiCompat.kt")
    } + fileTree(rootProject.layout.projectDirectory.dir("shared-src")) { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        // 负向后顾排除掉网关自己：Api102.setId(...) 这一类是合法出口。
        val forbidden = Regex("""(?<!Api102)\.(?:(?:replaceHook|setId|setSavedInstanceState)\(|getId\(\)|detach\(\)|savedInstanceState\b|oldHookHandles\b)""")
        val hits = sources.flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .filter { (_, line) -> forbidden.containsMatchIn(line) }
                .map { (i, line) -> "${file.relativeTo(projectDir)}:${i + 1}: ${line.trim()}" }
        }
        check(hits.isEmpty()) {
            "libxposed 102-only members must go through XposedApiCompat.Api102:\n" + hits.joinToString("\n")
        }
    }
}

tasks.named("check") { dependsOn(checkApi102Gateway) }
