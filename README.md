# EzHookTool

一个让 Android / Xposed / libxposed 场景下的反射与 hook 编写更直接的 Kotlin 工具库。  
当前拆分为 `core`、`hook-xposed-82`、`hook-xposed-101` 三个主模块，其中 `core` 可以单独用于纯反射场景。

### 快速开始

`build.gradle`

```groovy
dependencies {
    def ezHookToolVersion = '<version>'

    implementation "io.github.lingqiqi5211.ezhooktool:core:$ezHookToolVersion"
    implementation "io.github.lingqiqi5211.ezhooktool:hook-xposed-101:$ezHookToolVersion"
    // 或
    // implementation "io.github.lingqiqi5211.ezhooktool:hook-xposed-82:$ezHookToolVersion"

    // 如果你的模块直接使用 Xposed / libxposed 的类型，
    // 还需要额外声明对应运行时 API。
    compileOnly "io.github.libxposed:api:101.0.1"
    // 或
    // compileOnly "de.robv.android.xposed:api:82"
}
```

`build.gradle.kts`

```kotlin
dependencies {
    val ezHookToolVersion = "<version>"

    implementation("io.github.lingqiqi5211.ezhooktool:core:$ezHookToolVersion")
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-101:$ezHookToolVersion")
    // 或
    // implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-82:$ezHookToolVersion")

    // 如果你的模块直接使用 Xposed / libxposed 的类型，
    // 还需要额外声明对应运行时 API。
    compileOnly("io.github.libxposed:api:101.0.1")
    // 或
    // compileOnly("de.robv.android.xposed:api:82")
}
```

`xposed-api-82`

```kotlin
private const val TargetApp = "com.example.target"

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TargetApp) return

        EzXposed.init(lpparam)
        initHooks()
    }

    private fun initHooks() {
        // register your hooks here
    }
}
```

`xposed-api-101`

```kotlin
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

private const val TargetApp = "com.example.target"

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TargetApp) return

        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TargetApp) return

        EzXposed.initOnPackageReady(param)
        initHooks()
    }

    private fun initHooks() {
        // register your hooks here
    }
}
```

`reflection-only`

```kotlin
// 可选
// 在使用本库之前，调用此函数设置默认的 ClassLoader。
// 否则它会默认使用 ClassLoader.getSystemClassLoader()。
EzReflect.init(yourClassLoader)
```

### 模块说明

- `core`：反射、查找、实例化、descriptor 解析、DSL 作用域
- `hook-xposed-82`：经典 Xposed API 82 hook 辅助函数与兼容桥接
- `hook-xposed-101`：libxposed 101 hook 辅助函数与兼容桥接

### API 文档

已接入 Dokka。

本地生成后的文档位于：

- `doc/api/index.html`

发布后的在线文档会跟随 GitHub Release 自动构建，并部署到 GitHub Pages：

- 最新版：`https://lingqiqi5211.github.io/EzHookTool/api/latest/`
- 指定 release：`https://lingqiqi5211.github.io/EzHookTool/api/<tag>/`

重新生成：

```bash
./gradlew generateApiDocs
```

建议阅读顺序：

1. 先看 `core`
2. 再根据运行时选择 `hook-xposed-82` 或 `hook-xposed-101`
3. 需要兼容旧写法时，再看对应模块里的 `XposedHelpers`

### 构建

```bash
./gradlew build
./gradlew generateApiDocs
./gradlew publishAllToMavenLocal
```

### 示例工程

- `sample-xposed-82`
- `sample-xposed-101`

### 致谢

感谢这些项目提供的思路与启发：

- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)
- [KavaRef](https://github.com/HighCapable/KavaRef)

### License

MIT
