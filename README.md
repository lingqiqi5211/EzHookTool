# EzHookTool

一个让 Android / Xposed / libxposed 场景下的反射与 hook 编写更直接的 Kotlin 工具库。  
当前拆分为 `core`、`hook-xposed-82`、`hook-xposed-102` 三个主模块，其中 `core` 可以单独用于纯反射场景。

当前 Maven 最新构建版本为: ![Maven Central Version](https://img.shields.io/maven-central/v/io.github.lingqiqi5211.ezhooktool/core)

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
    // 注意：hook-xposed-102 和 hook-xposed-82 二选一，不要同时引入。

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

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
    }

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
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

private const val TargetApp = "com.example.target"

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        EzXposed.onTargetReady { initHooks() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage || param.packageName != TargetApp) return

        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != TargetApp) return

        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        EzXposed.handleHotReloadedWithTargetReady(this, param, { initHooks() })
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
一个带稳定内部 ID 的物理 hook。所有同步规则构建完成后才提交；相同 ID 的旧组由 framework 逐条原子替换，
通常无需手写 ID。新增目标会先安装，已经关闭或删除的旧目标会在最后撤销，因此支持开关引起的 hook 增删，
也支持新版本关闭全部 hook。
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

`detachCurrentEntry()` 也会停止 `onHotReloading`。需要热重载的目标 entry 不要调用它；它只适合确认不会安装
hook 的非目标 entry。

热重载：API 102 模块在 `META-INF/xposed/module.prop` 中设置 `autoHotReload=true` 后，
Xposed 应用更新模块也会请求热重载。新模块默认只需把所有同步初始化放进
`EzXposed.onTargetReady { ... }`，工具会自动聚合并分配稳定物理 ID；规则构建成功后，相同 ID 的旧 hook
会逐条无空窗替换，不会先全量 unhook 旧 handle：

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
        EzXposed.handleHotReloadedWithTargetReady(this, param, targetReady = { installHooks() })
    }
}
```

新增、删除或重排逻辑 hook、目标 executable、优先级/异常模式组和显式 hook ID 都受支持。一个功能包含多个
hook 时，可以用同一个开关包住整组同步初始化；新 generation 重新读取持久设置即可：

```kotlin
private fun installHooks() {
    val switches = readHookSwitches()
    if (switches.loginReporter) {
        loginMethod.createBeforeHook { /* ... */ }
        reportMethod.createBeforeHook { /* ... */ }
    }
    if (switches.premium) {
        premiumMethod.createReplaceHook { true }
    }
}
```

若开关需要立即生效而不是等模块热重载，保持 hook 固定安装，在 callback 内读取可更新状态；关闭时直接放行：

```kotlin
method.createInterceptHook { chain ->
    if (isFeatureEnabled()) patchedResult() else chain.proceed()
}
```

多个作用域按进程分别保存和恢复，互不共用一次热重载事务。在 `installHooks()` 内按
`EzXposed.isSystemServer`、`packageName`、`processName` 分派即可；`onPackageReady` 建议只接收
`isFirstPackage`，避免同一进程后续加载的其它 package 落到批次外。示例工程同时列出了两个 app scope。

`reloadKey`、`HotReloadSession`、`HookReloadBatch` 与 `handleHotReloaded(..., onOldHooks = ...)` 保留给需要
自定义 identity、跨代状态或旧 handle 迁移的场景。listener、receiver、线程、资源缓存或已 inflate 的 View
不属于 hook handle，必须在旧 generation 明确停止，并在新 generation 重建。

libxposed 只保证单个 handle 或相同 ID hook 的原子替换，没有“全部 hook 一次性回滚”的能力。若底层在替换
多条旧 hook 的中途失败，或新增 hook 安装失败后无法完整撤销，工具会明确报错，此时应重启目标进程；不会把
混合状态伪报为成功。
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
