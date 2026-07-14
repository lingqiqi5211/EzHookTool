# EzHookTool

一个让 Android / Xposed / libxposed 场景下的反射与 hook 编写更直接的 Kotlin 工具库。  
当前拆分为 `core`、`hook-xposed-82`、`hook-xposed-102` 三个主模块，其中 `core` 可以单独用于纯反射场景。

当前 Maven 最新构建版本为: ![Maven Central Version](https://img.shields.io/maven-central/v/io.github.lingqiqi5211.ezhooktool/core)

> `hook-xposed-102` 依赖 libxposed API `102.0.0` 正式版；如需使用其他版本，请自行替换为对应的 runtime API。

### 快速开始

`build.gradle`

```groovy
dependencies {
    def ezHookToolVersion = '<version>'

    implementation "io.github.lingqiqi5211.ezhooktool:core:$ezHookToolVersion"
    implementation "io.github.lingqiqi5211.ezhooktool:hook-xposed-102:$ezHookToolVersion"
    // 或
    // implementation "io.github.lingqiqi5211.ezhooktool:hook-xposed-82:$ezHookToolVersion"

    // 如果你的模块直接使用 Xposed / libxposed 的类型，
    // 还需要额外声明对应运行时 API。
    compileOnly "io.github.libxposed:api:102.0.0"
    // 或
    // compileOnly "de.robv.android.xposed:api:82"
}
```

`build.gradle.kts`

```kotlin
dependencies {
    val ezHookToolVersion = "<version>"

    implementation("io.github.lingqiqi5211.ezhooktool:core:$ezHookToolVersion")
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-102:$ezHookToolVersion")
    // 或
    // implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-82:$ezHookToolVersion")

    // 如果你的模块直接使用 Xposed / libxposed 的类型，
    // 还需要额外声明对应运行时 API。
    compileOnly("io.github.libxposed:api:102.0.0")
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

`xposed-api-102`

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

    @RequiresApi(Build.VERSION_CODES.Q)
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

### API 102 新能力

详细说明见 `doc/overview.md`。

`HookFactory` 的默认热重载路径会把同一 executable、优先级和异常模式的 DSL / Java helper hook 自动聚合为
一个带稳定内部 ID 的物理 hook。规则构建完成后才提交，成功后由 framework 原子替换旧组；通常无需手写 ID。
需要独立的跨版本 identity 时，再使用语义更明确的 `reloadKey`：

```kotlin
val handle = method.createHook {
    reloadKey("license-check")
    before { /* ... */ }
}

val newHandle = handle.replaceWith { /* HookParam */ true }
```

`EzXposed.detachCurrentEntry()` 停止 framework 向当前 entry 分发后续生命周期回调，hook 不受影响：

```kotlin
override fun onPackageReady(param: PackageReadyParam) {
    if (param.packageName != TargetApp) {
        EzXposed.detachCurrentEntry()
        return
    }
    EzXposed.initOnPackageReady(param)
}
```

热重载：API 102 模块在 `META-INF/xposed/module.prop` 中设置 `autoHotReload=true` 后，
Xposed 应用更新模块也会请求热重载。新模块默认只需把所有同步初始化放进
`EzXposed.onTargetReady { ... }`，工具会自动聚合并分配稳定物理 ID；规则构建成功后原子替换新旧 hook，
不会先全量 unhook 旧 handle：

```kotlin
class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        EzXposed.onTargetReady { installHooks() }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TargetApp) return
        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: HotReloadingParam) =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        // API 102 不会重放 onModuleLoaded；这个重载会完成新 generation 的初始化与规则注册。
        EzXposed.handleHotReloadedWithTargetReady(this, param) { installHooks() }
    }
}
```

新增、删除或重排同一目标/优先级/异常模式组内的逻辑 hook 不会改变默认物理 ID。`reloadKey`、
`HotReloadSession`、`HookReloadBatch` 与
`handleHotReloaded(..., onOldHooks = ...)` 保留给需要自定义 identity 或旧 handle 迁移的场景。
默认自动模式会要求物理 hook identity 集合不变；若新增或删除了目标 executable、优先级/异常模式组或显式
hook ID，它会在发布任何新 hook 前拒绝本次热重载，要求重启目标进程。
外部 listener / receiver / 线程、资源缓存或已 inflate 的 View 仍需模块自行恢复；必要时应重启目标进程。
完整约束、迁移条件和 Java 写法见 `doc/overview.md`。

### 模块说明

- `core`：反射、查找、实例化、descriptor 解析、DSL 作用域
- `hook-xposed-82`：经典 Xposed API 82 hook 辅助函数与兼容桥接
- `hook-xposed-102`：libxposed 102 hook 辅助函数与兼容桥接

### API 文档

已接入 Dokka。

推荐用法和参数说明见：

- `doc/overview.md`

本地生成后的文档位于：

- `doc/api/index.html`

发布后的在线文档会跟随 GitHub Release 自动构建，并写入 `gh-pages` 分支的 `api/latest/` 与 `api/<tag>/`：

- 最新版：`https://lingqiqi5211.github.io/EzHookTool/api/latest/`
- 指定 release：`https://lingqiqi5211.github.io/EzHookTool/api/<tag>/`

重新生成：

```bash
./gradlew generateApiDocs
```

建议阅读顺序：

1. 先看 `core`
2. 再根据运行时选择 `hook-xposed-82` 或 `hook-xposed-102`
3. Java 写法入口看 `core.java` 包下的 `Classes`、`Methods`、`Fields`、`Constructors`，以及 hook 模块里的 `xposed.java.Hooks`

### 构建

```bash
./gradlew build
./gradlew generateApiDocs
./gradlew publishAllToMavenLocal
```

### 示例工程

- `sample-xposed-82`
- `sample-xposed-102`

### 致谢

感谢这些项目提供的思路与启发：

- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)
- [KavaRef](https://github.com/HighCapable/KavaRef)

### License

MIT
