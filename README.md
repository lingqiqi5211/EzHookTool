# EzHookTool

Kotlin 反射与 Hook 工具库，提供成员查找、调用、DSL 和 Java 入口。`core` 可独立用于 JVM；Android Hook 按运行时选择经典 Xposed 或 libxposed 适配层。

![Maven Central](https://img.shields.io/maven-central/v/io.github.lingqiqi5211.ezhooktool/core)

[详细指南](doc/overview.md) · [API 文档](https://lingqiqi5211.github.io/EzHookTool/api/latest/) · [版本变化](CHANGELOG.md) · [示例工程](#示例与构建)

本文对应当前源码。使用已发布版本时，请同时查看 CHANGELOG；`Unreleased` 中的变化尚未发布。

## 选择模块

| 使用场景 | 依赖 | 边界 |
| --- | --- | --- |
| 纯 JVM / Android 反射 | `core` | 不依赖 Android、Xposed 或 libxposed |
| 经典 Xposed | `hook-xposed-82` | Xposed API 82 |
| libxposed | `hook-xposed-102` | 按 API 102 编译，运行基线为 API 101；热重载等能力需要 API 102 |

两个 Hook 模块二选一，不能同时引入。它们提供相同包名的常用入口，共享与 framework 无关的实现；不代表所有后端能力完全相同。

## 添加依赖

在模块的 `build.gradle.kts` 中选择所需依赖，将 `<version>` 替换为发布版本：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    val ezHookToolVersion = "<version>"
    implementation("io.github.lingqiqi5211.ezhooktool:core:$ezHookToolVersion")

    // 使用 libxposed 时添加；纯反射项目不需要。
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-102:$ezHookToolVersion")
    compileOnly("io.github.libxposed:api:102.0.0")
}
```

经典 Xposed 项目改用以下两项，并按 Xposed API 的要求配置依赖仓库：

```kotlin
implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-82:$ezHookToolVersion")
compileOnly("de.robv.android.xposed:api:82")
```

## 先从反射开始

```kotlin
import io.github.lingqiqi5211.ezhooktool.core.findMethod

class Example {
    private fun greeting(name: String): String = "Hello, $name"
}

val method = findMethod(Example::class.java) {
    name("greeting")
    params(String::class.java)
}
val text = method.invoke(Example(), "EzHookTool")
```

按类名查找时，可用 `EzReflect.init(yourClassLoader)` 设置默认加载器；未初始化时使用 `SystemClassLoader`。直接传入 `Class` 不需要初始化。

- 条件默认按 AND 组合；可选择当前类、继承链或唯一结果。
- 查询匹配和缓存使用同一份计划；一次同步查询及其嵌套查询沿用同一配置。
- 缓存合计最多 4096 条，超过 256 项的集合结果不缓存。它在作用域内持有强引用，配置切换或 `clearCache()` 会清空旧缓存并阻止旧查询回写。
- BestMatch 无法唯一选择时报告歧义，不依赖枚举顺序；不隐式拓宽数值或打包 vararg。

字段、构造器、descriptor、Java 门面和错误契约见 [Core 指南](doc/overview.md#core)。

## 接入 Hook 生命周期

### libxposed：API 101 / 102

在模块入口记录 framework 和目标进程信息，把同步 Hook 初始化放入 `onTargetReady`：

```kotlin
class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        EzXposed.onTargetReady { installHooks() }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != "com.example.target") return
        EzXposed.initOnPackageReady(param)
    }

    private fun installHooks() {
        // 在这里同步安装 Hook。
    }
}
```

上面省略了 import 和模块声明文件，完整配置见 [102 示例入口](sample-xposed-102/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/sample102/MainHook.kt)。普通模块使用 `onPackageReady`；Android Q 以上需要更早时机时，再选择指南中的 `initOnPackageLoadedAsTargetReady`。

### 经典 Xposed：API 82

在 `IXposedHookLoadPackage.handleLoadPackage` 筛选目标包后调用 `EzXposed.init(lpparam)`，再安装 Hook。使用模块资源时，还需实现 `IXposedHookZygoteInit` 并调用 `EzXposed.initZygote(startupParam)`，提供可靠的模块路径。

完整入口见 [82 示例](sample-xposed-82/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/sample82/MainHook.kt)。

### 查找后安装

初始化完成后，两种适配层都可使用常用 DSL：

```kotlin
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook

findMethod("com.example.Target") {
    name("isEnabled")
    noParams()
}.createReplaceHook { true }
```

普通 DSL 的 `safeMode` 会丢弃失败回调中尚未提交的参数和输出修改；after 失败不会再次执行原方法。`HookParam.raw`、参数对象内部修改以及文件、网络等副作用不在保护范围内。

[Hook 指南](doc/overview.md#hook) 包含 before / after / intercept、初始化失败查询、优先级和后端能力差异。

## 资源替换：先选清楚副作用

| 需求 | 用法 | 是否改变宿主资源池 |
| --- | --- | --- |
| 替换已有资源的值 | `setObjectReplacement` / `setDensityReplacement` | 否 |
| 使用模块求值后的文本、颜色等 | 先从 `EzXposed.moduleRes` 读取，再登记值 | 否；值固定到重新登记，不会自动跟随宿主配置或 Theme |
| 让宿主解析模块资源 | `inject` 或兼容入口 `setResReplacement` | 是；需要考虑命名空间、绑定和热重载限制 |

```kotlin
EzResources.setObjectReplacement("com.example.target", "color", "accent", Color.RED)
EzResources.setDensityReplacement("com.example.target", "dimen", "toolbar_height", 48f)
```

资源 getter 按需安装，包名支持 `"*"`。当前覆盖普通值、数组、mipmap、plurals 和常用 TypedArray 读取，但不是完整 Android 资源替换层：TypedArray 不转发模块资源，也不能还原原始 `?attr`。`clearReplacements()` 不解绑 loader，`fakeResId()` 不创建真实资源或保证无冲突。

覆盖范围、Theme 和 provider 生命周期见 [资源指南](doc/overview.md#resources)。

## 热重载：API 102 的可选能力

将 `onHotReloading` 和 `onHotReloaded` 原样桥接给 `EzXposed`，并在 `onTargetReady` 的同步窗口内安装 Hook。工具负责收集、聚合和迁移；模块不必手动登记 handle。需要独立跨版本标识时再使用 `reloadKey`。

- API 101 可使用普通 Hook（包括替换返回值），但不能使用 102-only 的 hook ID、`HookHandle.replaceHook`、热重载和 entry detach；通过 `XposedFeature` 查询能力。
- `onTargetReady` 初始化失败不自动重试。`targetReadyState` / `targetReadyFailure` 描述首次初始化，不代表整次热重载成功。
- framework 不提供全部 Hook 的统一原子回滚。部分发布、底层结果不明或撤销失败时，工具明确要求重启目标进程，不把混合状态报告为成功。
- 业务容器快照与 framework 对象分通道；监听器、线程和已创建的 View 仍需处理自己的生命周期。

完整桥接代码、功能开关、交接数据和失败处理见 [热重载指南](doc/overview.md#hot-reload)。

## 示例与构建

- [sample-xposed-82](sample-xposed-82)：经典 Xposed 接入与 Hook / 资源用法。
- [sample-xposed-102](sample-xposed-102)：libxposed 接入、多个目标作用域和热重载。
- [详细指南](doc/overview.md)：Kotlin / Java 用法、行为契约与限制。
- [API 文档](https://lingqiqi5211.github.io/EzHookTool/api/latest/)：Dokka 生成的签名与 KDoc。

本地构建需要 JDK 25：

```bash
./gradlew build
./gradlew generateApiDocs
```

`build` 包含编译、lint、JVM 测试和 API 102 网关检查。它不证明真实 Hook、ResourcesLoader 或 Theme 行为已通过真机验证；验证边界见 [指南](doc/overview.md#validation)。

API 文档生成到 `doc/api/index.html`；发布文档位于 `api/latest/` 和 `api/<tag>/`。

## 致谢与 License

感谢 [EzXHelper](https://github.com/KyuubiRan/EzXHelper)、[KavaRef](https://github.com/HighCapable/KavaRef) 的思路与启发。资源辅助亦参考了 HyperCeiler 的 ResourcesTool。

[MIT](LICENSE)
