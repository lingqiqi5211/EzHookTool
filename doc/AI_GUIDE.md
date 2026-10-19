# EzHookTool 接入指南

面向在业务工程里接入 EzHookTool 的 AI 编程助手与开发者，覆盖 `core` 反射、`hook-xposed-82`（经典 Xposed）与
`hook-xposed-102`（libxposed）。本指南可整份复制使用；接入工程自己的约束仍然适用。

## 复制到接入工程后怎么用

复制本文件到接入工程（常见位置 `doc/AI_GUIDE.md`，也可放任意路径），把下面一行合并进该工程根 `AGENTS.md`，
并把尖括号换成复制后的**实际本地路径**。本文不假设接入工程里存在上游的 `doc/` 或 `sample-*` 目录：

```text
本工程使用 EzHookTool：先读 <接入工程内的 AI_GUIDE.md 实际路径>，确认实际依赖版本，按对应后端与生命周期编写代码，并完成交付检查。
```

阅读顺序：**版本边界 → 确认环境 → 选后端并初始化 → 查找与 Hook → 性能与生命周期 → 按需热重载与资源 → 交付检查**。

## 版本边界

### 本文对应哪个版本

- 本文对应 **1.3.1** 源码；1.3.0 的差异单独列在下方。版本号已准备好，是否已发布以 Maven 仓库为准。
- 本文的 Kotlin 入口、安装函数、热重载桥接与 Java 门面示例已做编译核验；示例自身未在设备上执行。
  `./gradlew build` 验证库与示例工程，接入工程仍需编译自己的代码。
- 用之前核对实际解析到的版本与对应的 `sources.jar`，而不是只看 `build.gradle.kts` 里的声明；版本不一致时以实际依赖为准。本文不要求为了对齐文档而升级依赖。
- 示例里的 `com.example.target.*` 是宿主签名占位，必须替换成目标 App 的真实证据；不要把这些类加进模块冒充宿主。
  仓库的编译检查也没有为它伪造宿主类，只验证示例自身的语法与 API 用法。

### 1.3.0 的已知限制

以下结论来自 tag `1.3.0` 源码，未独立核验 Maven 产物或设备行为：

- 102 后端的 `runOnApplicationAttach` 无条件设置内部 `reloadKey`，在 API 101 上会触发能力检查而安装失败。
  兼容 101 时，应从已有、时机明确的宿主回调取得 Context，再调用 `initAppContext`；不要反复尝试注册 attach 回调。
- 两个后端的 attach 回调出错后直接调用用户 logger；logger 再抛错可能中断后续分发。自定义 logger 应保证不向外抛异常。

1.3.1 修复了以上两处，新增 `EzReflect.cacheStats` / `ReflectCacheStats`，并新增可取消 / 一次性的 attach 登记入口
（`subscribeApplicationAttach`、`runOnApplicationAttachOnce`）；`runOnApplicationAttach` 的签名与长期订阅行为不变。
使用源码依赖时先核对是否包含这些改动；
使用 1.3.0 时不要引用新增接口。内部精确查找优化不需要消费方更换 API，也不应据此删掉查找条件。

版本证据：[1.3.0 attach 实现](https://github.com/lingqiqi5211/EzHookTool/blob/1.3.0/hook-xposed-102/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/xposed/internal/ApplicationLifecycle.kt)、
[1.3.0 Hook 安装](https://github.com/lingqiqi5211/EzHookTool/blob/1.3.0/hook-xposed-102/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/xposed/dsl/HookFactory.kt)。
历史行为查对应 tag 的源码和使用指南；`1.3.0` tag 尚无本文件。复制本指南后，升级依赖时也要更新本地副本。

## 确认环境

改代码前先确认下表；能从依赖和仓库读到的自己读，只有影响实现且仓库里确实缺失时才提问。

| 项 | 要确认的内容 |
| --- | --- |
| 版本 | 实际解析到的 EzHookTool 版本、是否源码依赖、是否包含未发布改动 |
| 后端 | 纯 `core`、经典 82、libxposed 101/102；同一模块只能选一个 Hook 后端 |
| 范围 | 目标包、目标进程（含 `:remote` 等子进程）、是否 `system_server`；`scope.list` 必须与运行时过滤一致 |
| 宿主类 | 完整类名、加载它的 `ClassLoader`、可用时机；不能用模块自己的 loader 代替宿主 loader |
| 目标签名 | 方法名、参数顺序与每个参数类型、返回类型、static 还是 instance、声明在当前类还是父类 |
| 生命周期 | Hook 在哪一步同步安装、捕获的对象活多久、有没有外部 listener 或线程 |
| 失败策略 | 目标缺失时禁用可选功能还是报必需功能失败；不得悄悄放宽匹配条件 |

## 选一个后端并初始化

### 依赖

`core` 是独立反射层，不依赖 Android、Xposed 或 libxposed，纯反射项目只需要它：

```kotlin
implementation("io.github.lingqiqi5211.ezhooktool:core:<version>")
```

libxposed 后端，按 API 102 编译、运行基线是 API 101：

```kotlin
implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-102:<version>")
compileOnly("io.github.libxposed:api:102.0.0")
```

经典 Xposed 后端，与上面二选一：

```kotlin
implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-82:<version>")
compileOnly("de.robv.android.xposed:api:82")
```

两个 Hook artifact 的包名与共享类相同，**不能同时引入**。framework API 一律 `compileOnly`，不打进模块 APK。
经典 API 按上游要求需要仓库 `maven("https://api.xposed.info/")`。用源码依赖时，`core`、所选后端与 `shared-src` 必须同一版本。

### 101 / 102 与 Android API 不是一回事

- `101` / `102` 是 libxposed framework 的 API 版本；`minSdk`、`Build.VERSION_CODES` 是 Android API level，两者不能互相推断。
- `hook-xposed-102` 按 102 编译，但要在 101 上运行。普通 before / after / replace 与 `Chain` / `intercept` 都是 101 能力。
- 只有 hook ID、`HookHandle.replaceHook`、热重载回调、entry detach 需要 102，用 `XposedFeature.*.isSupported` 判断。
- 能力位在 `initOnModuleLoaded` 里解析一次，之后 `isSupported` 只是一次位测试；初始化前全为 `false`，不要缓存这个早期结果。

### libxposed 101 / 102 入口

```kotlin
package example.module

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.TargetReadyState

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // 这里只登记回调：宿主类此刻还不保证能加载。
        EzXposed.onTargetReady { installEzHooks(EzXposed.classLoader) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != "com.example.target") return
        if (EzXposed.processName != "com.example.target") return
        EzXposed.initOnPackageReady(param)
        if (EzXposed.targetReadyState == TargetReadyState.FAILED) {
            android.util.Log.e("ExampleModule", "target init failed", EzXposed.targetReadyFailure)
        }
    }
}
```

`initOnPackageLoaded` 只记录包名；`initOnPackageReady` 才设置目标 classloader 并触发 `onTargetReady`。
`onTargetReady` 的回调在就绪分发期间**同步执行**，要迁移或要原子收尾的 Hook 必须在这个窗口里装完，
不能丢进协程、`Handler` 或后续业务回调。初始化失败不自动重试，`targetReadyState` / `targetReadyFailure`
只描述本代首次就绪，不代表后续即时回调或整次热重载的结果。
102 的默认热重载批次会在同步安装回调全部返回后统一提交 Hook；注册返回不表示 getter 或目标方法已经被拦截。
检查实际拦截结果应在批次完成后进行，不能在安装回调内部立即调用目标方法就断言 Hook 无效。
`initOnPackageLoadedAsTargetReady` 是 Android Q 以上的提前路径（`@RequiresApi(Q)`），低版本用 `initOnPackageReady`；
`system_server` 走 `initOnSystemServerStarting`，不要套用包名过滤和 Application 假设。

### 经典 82 入口

```kotlin
package example.module

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.example.target") return
        if (lpparam.processName != "com.example.target") return
        EzXposed.init(lpparam)
        installEzHooks(EzXposed.classLoader)
    }
}
```

`initZygote` 是模块 apk 路径的可靠来源，也在这里初始化模块资源 `moduleRes`；没有它，`initModuleResources` /
`addModuleAssetPath` 会明确报错。`init(lpparam)` 设置目标 classloader、包名与进程名。
不要把 102 的 `XposedInterface` 与能力位假设搬到 82 上。

### 两个后端共用的安装函数

以下安装函数适用于上面任一后端，放在所选入口的同一 package 中。这里只演示两后端共有的行为；使用专属能力时按后端分别实现。

```kotlin
package example.module

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/** 宿主占位签名：com.example.target.LabelFormatter#formatLabel(String): String，实例方法。 */
internal fun installEzHooks(loader: ClassLoader) {
    val target = loadClass("com.example.target.LabelFormatter", loader)
    val formatLabel = findMethod(target) {
        name("formatLabel")
        params(String::class.java)
        returnType(String::class.java)
        notStatic()
        findOnlyClass()
        findSingle()
    }
    formatLabel.createHook {
        before { param ->
            val raw = param.args[0] as? String
            if (raw != null) param.args[0] = raw.trim()
        }
    }
}
```

这里只用两个后端都有的 `createHook { before { ... } }`：82 的 `createHook` 没有 `priority` / `exceptionMode` 参数，
共享代码里出现它们会编不过。`EzXposed.classLoader` 在 82 上是 `init(lpparam)` 之后的目标 loader，
在 102 上是 ready 之后的目标 loader。

### 入口 metadata 与 scope

`scope.list` 是 framework 允许本模块加载的包，运行时仍要自己按包名与进程过滤；两者不一致的表现是「装了但没跑」。

libxposed：

```text
src/main/resources/META-INF/xposed/java_init.list   -> example.module.MainHook
src/main/resources/META-INF/xposed/scope.list       -> com.example.target
src/main/resources/META-INF/xposed/module.prop      -> 以下独立文件内容
```

```properties
minApiVersion=101
targetApiVersion=102
```

经典 82：

```text
src/main/assets/xposed_init                         -> example.module.MainHook
```

经典模块还需按所用框架配置 AndroidManifest 中的模块标识、最低 API、描述与 scope；`xposed_init` 只声明入口类，不能替代模块 metadata。

确保 libxposed 的 `META-INF/xposed/*` 被打进 APK（例如 `packaging { resources { merges += "META-INF/xposed/*" } }`），
并按框架要求保留 entry 类不被 R8 改名。声明 `minApiVersion=101` 时不要同时打开 `autoHotReload`。

## 精确查找与 Hook 示例

### Kotlin：完整结构条件 + 唯一性

查询条件全部是 AND。预期只有一个结果时，把「够用的结构条件」写全，再显式 `findSingle()`：

- `name(...)` 名称；`params(...)` 按顺序的完整参数类型；`returnType(...)` 返回类型（`voidReturnType()` 表示 void）。
- `notStatic()` / `isStatic()` 区分实例与静态。默认先查当前类、找不到再往父类找；`findOnlyClass()` 限定在当前类，
  `findAndSuper()` 合并父类结果。
- `findSingle()` 要求恰好一个结果；多个命中抛 `SingleResultExpectedException`，不会退回「第一个」。
- primitive 与 boxed 是两个类型（`Int::class.javaPrimitiveType` / `Int::class.javaObjectType`）；不要指望 BestMatch 做数值拓宽或把多个参数打包成 vararg。
- 无参方法用 `noParams()`；`paramCount(n)` 之类的宽松条件只用于确实要收窄候选的场合。

**不要为了走快路径删条件**。1.3.1 的内部优化只在默认成员解析器下，覆盖首项查询中恰好为「方法名 + 完整参数类型」「字段名」或「构造器完整参数类型」的条件组合。
`findSingle()`、批量查询和额外筛选仍需扫描。是否触发优化不影响消费方的选择：需要返回类型、静态性或唯一性约束时就保留它们。

自定义 `filter` 默认不缓存；手动设置 `cacheKey` 时必须覆盖所有影响结果的输入。默认 `ClassResolver` 不扫描 Dex，
未知类名的发现需要接入实际类索引，不能把普通反射查询当成全 APK 搜索。

### 查找失败：按具体入口处理

| 入口与情况 | 行为 | 处理 |
| --- | --- | --- |
| `loadClassOrNull` / `loadClassFirstOrNull` | 类加载遇 `ClassNotFoundException`、`NoClassDefFoundError`、`TypeNotPresentException` 或 `LinkageError`，尝试候选与类名兜底后仍失败则返回 `null`；其它异常外抛 | `null` 不能证明类名不存在，也可能是加载失败；诊断时核对 loader 和依赖 |
| `findMethodOrNull(Class, ...)` / 字段、构造器对应入口 | 没有匹配返回 `null`；成员枚举、可访问性或用户条件中的异常不统一吞掉 | 可选目标缺失时跳过；不要把解析故障当成普通未命中 |
| 配合 `findSingle()` 的查询 | 多个结果抛 `SingleResultExpectedException`，即使用 `OrNull` 也一样 | 补充有证据的条件，不退化成「第一个」 |
| 非 `OrNull` 查找 | 类加载未成功抛 `ClassNotFoundError`；成员未命中抛 `MemberNotFoundException` | 必需功能报告失败，不能悄悄扩大匹配范围 |

以类名字符串查找成员时，先经过类加载，所以仍可能在加载阶段返回 `null`。这里的契约只适用于查找入口；
不要推断所有名字带 `OrNull` 的调用辅助函数都处理相同异常，也不要用 `catch (Throwable)` 把所有失败统一忽略。

### Java 门面配方

Java 用 `Classes` / `Methods` / `Fields` / `Constructors` 做反射，Hook 用 `Hooks`。
**Java 查询器没有 `findSingle()`**，唯一性要自己用 `toList().size` 验证：

```java
package example.module;

import java.lang.reflect.Method;
import java.util.List;

import io.github.lingqiqi5211.ezhooktool.core.java.Methods;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.Hooks;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public final class LabelHook {
    private LabelHook() {}

    /** 宿主占位签名，与 Kotlin 示例相同：formatLabel(String): String，实例方法。 */
    public static void install(Class<?> target) {
        List<Method> matches = Methods.find(target)
                .filterByName("formatLabel")
                .filterByParamTypes(String.class)
                .filterByReturnType(String.class)
                .filterNonStatic()
                .findOnlyClass()
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one formatLabel(String): String, found " + matches.size());
        }
        Hooks.createHook(matches.get(0), new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object raw = param.getArgs()[0];
                if (raw instanceof String) {
                    param.getArgs()[0] = ((String) raw).trim();
                }
            }

            @Override
            public void after(HookParam param) {
                // Java 侧请把 before 和 after 都写上（空实现也行），不要依赖 Kotlin 接口的默认实现。
            }
        });
    }
}
```

只做反射时不要引入 Hook 框架依赖；纯 `core` 项目里也没有 `Hooks`。

## 高频路径、引用生命周期与异常规则

### 高频路径

- 稳定的类、成员在初始化时解析，Hook 在对应生命周期安装一次；不要在每次 `onCreate`、`onFinishInflate`、draw、scroll 回调中重复查询和 `createHook`。
- 动态加载的目标应在其真实 loader 可用时解析，并按 loader/功能生命周期去重；不要为了提前缓存而改用错误的 loader。
- 高频回调只做本次调用需要的判断，功能开关读线程安全状态。
- 资源 getter 与 attach hook 由库按需安装，模块不需要持有或手动摘除它们的 handle。

### 缓存与释放

| 机制 | 实际行为 | 不要怎么理解 |
| --- | --- | --- |
| 反射缓存 | 作用域内强引用，合计 4096 条上限，超 256 项的集合不登记 | 不是零保留，也不是释放接口 |
| `EzReflect.clearCache()` | 换一代缓存并丢掉旧代，保留当前配置与默认 loader | 不是通用释放接口；高频调用只让后续查询重新扫描 |
| `EzReflect.reset()` | 重置默认 loader 与初始化状态，保留解析器等显式配置 | 自定义 resolver / logger 可能仍持有旧 loader |
| `reflect(loader) {}` | 只在块内换默认 loader，仍然使用库缓存 | 退出作用域**不会**清缓存，也不隔离对象生命周期 |
| `EzReflect.cacheStats`（1.3.1 起） | 只含数字的快照：代次、条目、容量、命中 / 未命中 / 淘汰 / 大集合跳过 | 它不持有 Class、loader 或结果，读一次不会延长存活 |

内存涨了先看 GC Root：Hook 注册数、外部 listener / 线程、附加字段、宿主缓存，再决定要不要 `clearCache()`。

### Application Context 与 attach 回调

- `runOnApplicationAttach` 登记的回调长期保留，没有取消入口；已有 `appContextOrNull` 时会在注册线程立即补发，补发后仍保留。
  需要取消时用 `subscribeApplicationAttach`（返回 `ApplicationAttachSubscription`），只跑一次用 `runOnApplicationAttachOnce`
  （领取即从登记表摘除，抛异常也算消费）。后两个从 1.3.1 提供，1.3.0 只有第一个。
  `cancel()` 阻止尚未领取的调用；已领取的调用仍可能在取消后开始或完成。取消后的 token 不再强持有回调，重复 cancel 无害。
  三个入口都不应反复登记，也不捕获 Activity、View 或短期 controller；登记与 token 只属于当前 generation，热重载不会带过去。
  102 开启热重载时，在每代 `onTargetReady` 同步窗口内登记，使内部 attach hook 参与迁移；不要只在 `onModuleLoaded` 登记。
  102 后端三个入口都先 `initOnModuleLoaded`；两个新入口找不到 attach 方法时抛 `IllegalStateException`，
  安装或 Context 读取失败则保留原异常，注册失败不留回调。旧入口找不到 attach 方法时只记日志；
  82 不需先 `initZygote`；兼容 101 时注意开头的 1.3.0 限制。
- attach 分发传入的是 `Application.attach(Context)` 的 Context 参数，不能强转为 Application；晚注册收到的是当时缓存的 Context。
  登记与分发使用同一把锁，Context 探针在锁外读取并校验分发序号，同一个 attach 不会重复补发；回调与 logger 都在锁外执行。
  `appContextOrNull` 可能为空，直接读 `appContext` 则会抛错。`system_server` 不保证存在普通 Application。
- `initAppContext(context, injectModuleAssetPath, force)` 默认只在缓存为空时保留 `applicationContext` 或传入的 Application；不可用时抛错。
  `force = true` 原样保留并覆盖旧值，不能拿它强存 Activity。第二个位置参数是资源注入开关，建议使用命名参数；
  开启注入时始终针对原始入参执行，不因已有缓存而跳过。仅为存 Context 时保持注入关闭。

### 附加字段

- 实例附加字段是**身份弱键 + 强值**，静态附加字段是弱 `Class` 键 + 强值。
- 值直接或间接指回 owner 时，弱键打断不了这条引用链；这种反向引用要显式移除
  （Kotlin `removeAdditionalField`，Java `ExtraFields.removeInstanceField`）。
- owner 被回收后的失效项会在后续表操作中清理，但库不会替功能执行生命周期注销；仍存活的 owner 上的状态要自行移除。
  key 带模块命名空间前缀，减少不同功能间冲突。
- 不要把 Activity、View、短期 controller 放进静态附加字段或进程级单例。

### 异常与 safeMode

- `EzXposed.safeMode` 默认 `true`：普通 DSL / Java helper 回调失败时丢弃未提交的参数和输出修改；after 恢复此前的结果或异常，**不重新执行原方法**。
  before / replace 失败按后端安全回退路径继续，不能据此重放已执行的业务逻辑。
  它不撤销参数对象内部修改、`HookParam.raw` 写入、文件、网络、数据库或 UI 副作用；用户 logger 抛错不会打断普通 Hook 的安全回退。
- 不要捕获异常后无条件重跑原方法；after 阶段失败不应再执行原方法。
- libxposed 的 `createInterceptHook` 由回调自己决定是否 `chain.proceed()`；**最多 proceed 一次**——
  第一次可能已经执行了宿主逻辑，`catch` 之后再 proceed 会执行两遍。
- 短路返回必须符合目标声明类型：`interrupt()` 等于返回 `null`，不能用于要求 `boolean` / `int` 等 primitive 的方法；
  需要非 null 固定值时用 `returnConstant(value)`。
- 静态方法用 `thisObjectOrNull`：102 直接读静态调用的 `thisObject` 会抛异常，82 返回 `null`。
- 日志带包名、进程、功能与阶段信息；不要在高频回调里打印完整异常或参数。

## 按需热重载与资源

### 热重载（仅 API 102）

默认自动模式由库收集 Hook、逐条替换并收尾旧 handle：**模块不需要逐条保存 handle，也不需要默认给每条 Hook 加 `reloadKey`**。
要求最低运行框架是 102，并在 `module.prop` 声明 `minApiVersion=102`、`targetApiVersion=102`、`autoHotReload=true`。
把下面两个 override 加到前面的 libxposed `MainHook` 类内，import 放文件顶部；启用自动热重载时整个模块只保留一个 Java entry：

```kotlin
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam

override fun onHotReloading(param: HotReloadingParam): Boolean =
    EzXposed.handleHotReloading(param)

override fun onHotReloaded(param: HotReloadedParam) {
    EzXposed.handleHotReloadedWithTargetReady(
        this,
        param,
        targetReady = { installEzHooks(EzXposed.classLoader) },
    )
}
```

- 两个回调原样桥接给 `EzXposed`；`onHotReloading` 返回 `false` 表示取消整次请求。
- framework 不会重放 `onModuleLoaded` / package 生命周期，新代只收到 `onHotReloaded`，所以桥接不能省。
- 默认管理的 Hook 必须在 `onTargetReady` 的同步窗口内安装。动态 loader 或延迟安装需要另行设计生命周期；
  单独加 `reloadKey` 并不能把窗口外的 Hook 纳入默认收集流程。
- 持久开关每一代重新读取；不要跨代保存模块对象、旧回调或安装时的固定 snapshot。
- 只有需要稳定的逐条身份时才用 `reloadKey`；不要与默认自动流程重叠使用 `HotReloadSession`。
- 外部 listener、receiver、定时任务、线程不是 HookHandle，仍需模块自己注销或停止。
- 101 不提供热重载；为 102 编译保留的覆写在 101 framework 上不会被调用。

### 资源替换

先确认副作用，再选入口：

| 需求 | 入口 | 是否改动宿主资源池 |
| --- | --- | --- |
| 替换某个资源的值 | `EzResources.setObjectReplacement` / `setDensityReplacement` | 否 |
| 用模块求值后的文本、颜色等 | 先读 `EzXposed.moduleRes`，再 `setObjectReplacement` | 否；值固定到重新登记为止 |
| 让宿主解析模块资源 | `EzResources.inject`，或兼容入口 `setResReplacement` | 是；受命名空间、绑定与热重载约束 |

```kotlin
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources

EzResources.setObjectReplacement("com.example.target", "color", "accent", android.graphics.Color.RED)
EzResources.setDensityReplacement("com.example.target", "dimen", "toolbar_height", 48f)
```

- `clearReplacements()` 只清规则与类型告警，**不卸载 getter Hook、不解绑已注入的 loader**。
- `fakeResId(name)` 只是兼容用 hash 标识，可能冲突，不创建真实 Android 资源。
- 规则按「包名 + 类型 + 名称」匹配，包名 `"*"` 通配；getter 按需安装，一条规则都没登记时不装这些 Hook。
- 定位与最终值分开：Theme、density、locale 决定的最终值逐次求值（每次 getter 用同一份规则快照），
  而预求值登记的值固定到重新登记为止，不会自动跟随宿主的主题切换或配置变化。
- TypedArray 只覆盖公开 `getResourceId` 能取到 ID 的条目，只支持直接值与尺寸替换，不还原 `?attr` 或内联字面值。
- `setDensityReplacement` 只接受 `dimen`，按 dp 语义乘屏幕密度。

## 交付检查

1. 版本与后端：写明实际解析到的版本；确认只引入一个 Hook 后端，framework 依赖是 `compileOnly`。
2. 查找条件：名称、完整参数类型、返回类型、static / instance、声明层、唯一性都写进查询，且没有为性能删条件。
3. 生命周期：Hook 在同步初始化窗口内安装；重复进入、初始化失败、功能关闭三条路径都有确定结果。
4. 引用：没有单例 / 闭包 / 附加字段强持有 Activity、View 或短期 controller；反向引用已显式移除。
5. 异常：区分类加载失败、成员未命中和匹配歧义；safeMode 没被当成回滚业务副作用的保证；intercept 没有重复 proceed；primitive 返回值没用 `interrupt()`。
6. 热重载与资源（如涉及）：两个回调已桥接，Hook 同步安装；资源规则与绑定边界按上一节核对。
7. 验证：用接入工程真实存在的 task 编译受影响的模块；改匹配条件要核对候选数量，改生命周期要覆盖重复进入与失败路径。
   纯 JVM 测试与 `./gradlew build` 只证明查询、缓存、回调状态与异常顺序，**不证明真实 Hook 生效、ResourcesLoader 切换或 Theme 效果**。
8. 报告：写清改动位置与行为、关键生命周期选择、实际执行的验证、未验证的设备 / 环境限制。

本文示例在当前源码上编译验证，不代表所有接入工程或目标签名都有效。发布前已用真实 Lite 工程完成
Release/R8 与 Android 17 / libxposed API 102 的 Hook、偏好更新和同进程热重载验证；资源跨代迁移另有真机探针验证。
这些结果不替代接入工程自己的设备测试，也不证明 API 101 / 经典 82 或所有 ROM 均通过。

## 证据入口

- 使用指南（tag 1.3.0）：https://github.com/lingqiqi5211/EzHookTool/blob/1.3.0/doc/overview.md
- 使用指南（main 分支）：https://github.com/lingqiqi5211/EzHookTool/blob/main/doc/overview.md
- 经典 82 示例入口（tag 1.3.0）：https://github.com/lingqiqi5211/EzHookTool/blob/1.3.0/sample-xposed-82/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/sample82/MainHook.kt
- libxposed 102 示例入口（tag 1.3.0）：https://github.com/lingqiqi5211/EzHookTool/blob/1.3.0/sample-xposed-102/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/sample102/MainHook.kt
- `core` 公共 API（tag 1.3.0）：https://github.com/lingqiqi5211/EzHookTool/tree/1.3.0/core/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/core
- 102 Hook DSL（tag 1.3.0）：https://github.com/lingqiqi5211/EzHookTool/tree/1.3.0/hook-xposed-102/src/main/kotlin/io/github/lingqiqi5211/ezhooktool/xposed/dsl
- 版本变化：https://github.com/lingqiqi5211/EzHookTool/blob/main/CHANGELOG.md
