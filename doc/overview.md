# EzHookTool 使用指南

按使用顺序阅读：

- [依赖与阅读导航](#dependencies)
- [Core：反射查询与调用](#core)
- [Hook：初始化与回调](#hook)
- [资源：直接值、模块预求值与宿主绑定](#resources)
- [热重载与失败恢复](#hot-reload)
- [验证与兼容边界](#validation)

<a id="dependencies"></a>

## 依赖与阅读导航

| 使用场景 | 依赖 | 入口 |
| --- | --- | --- |
| 纯 Kotlin/JVM 反射 | `core` | Kotlin 顶层函数、`reflect {}`；Java 的 `Classes` / `Methods` / `Fields` / `Constructors` |
| 经典 Xposed API 82 | `core` + `hook-xposed-82` | `EzXposed`、`xposed.dsl` / `xposed.java` |
| libxposed API 101 / 102 | `core` + `hook-xposed-102` | 同名入口；按 102 编译，101 是运行基线，102 特性按需启用 |

两个 Hook artifact **二选一**，不能同时引入。`core` 不依赖 Android 或 Hook framework。

```kotlin
// build.gradle.kts
val ezHookToolVersion = "<version>"

dependencies {
    implementation("io.github.lingqiqi5211.ezhooktool:core:$ezHookToolVersion")
    // 纯反射到此即可；Hook 模块再选择下面一组。
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-102:$ezHookToolVersion")
    compileOnly("io.github.libxposed:api:102.0.0")

    // 经典 Xposed 改用：
    // implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-82:$ezHookToolVersion")
    // compileOnly("de.robv.android.xposed:api:82")
}
```

示例省略重复 import 和应用自己的 `installHooks()` 等实现。Core Kotlin API 位于
`io.github.lingqiqi5211.ezhooktool.core`，查询类型位于其 `query` 包；Hook DSL 位于
`io.github.lingqiqi5211.ezhooktool.xposed.dsl`。完整工程可参考
[`sample-xposed-82`](../sample-xposed-82) 和 [`sample-xposed-102`](../sample-xposed-102)。
本页是详细指南；项目入口见 [README](../README.md)。

<a id="core"></a>

## Core：反射查询与调用

### 选择 ClassLoader 与加载类

未初始化时默认使用 `ClassLoader.getSystemClassLoader()`。纯反射调用可设置默认加载器，或通过局部作用域显式传入：

```kotlin
EzReflect.init(yourClassLoader)
val target = loadClass("com.example.Target")
val fallback = loadClassFirst("com.example.Target", "com.example.a")

reflect(yourClassLoader) {
    val method = "com.example.Target".findMethod { name("run") }
}
```

`loadClassOrNull` / `loadClassFirstOrNull` 提供可空形式；`lazyClass` / `lazyClassOrNull` 可延迟加载。
Hook 场景通常由下文的 `EzXposed` 初始化目标加载器，不必重复设置。

项目不做 dex 扫描。`findClassIf` 通过 `EzReflect.classResolver` 获取可查询类名，再按条件筛选；
默认类名解析器返回空序列，需要调用者接入索引。成员查找只在给定类及所选父类范围内进行。

```kotlin
val clazz = findClassIf {
    findSingle()
    cacheKey("login-activity")
    packageStartsWith("com.example")
    simpleNameContains("Login")
    hasMethod {
        name("login")
        paramCount(2)
    }
    hasField { name("token") }
}
```

对应入口还有 `findClassIfOrNull` 和 `findAllClassesIf`。Java 的类加载门面：

```java
import io.github.lingqiqi5211.ezhooktool.core.java.Classes;

Class<?> target = Classes.loadClass("com.example.Target");
Class<?> fallback = Classes.loadClassFirst("com.example.Target", "com.example.a");
```

Java 门面目前没有 Kotlin `findClassIf { ... }` 的类条件查询入口；可在 Kotlin 侧封装，
或组合 `Classes.loadClass` 与反射检查。

### 查找方法、字段与构造器

```kotlin
val method = clazz.findMethod {
    name("foo")
    paramCount(2)
    returnType(String::class.java)
}
val methods = clazz.findAllMethods {
    name("foo")
    paramCount(2)
    findAndSuper()
}
val field = clazz.findField {
    name("mContext")
    type(Context::class.java)
    findOnlyClass()
}
val constructor = clazz.findConstructor {
    noParams()
    isPublic()
}
```

方法和字段默认智能查找：先查当前类，找不到再查父类。

- `findOnlyClass()`：只查当前类。
- `findAndSuper()`：查当前类和全部父类。
- 不写条件的 `findAllMethods()` / `findAllFields()` / `findAllConstructors()` 返回当前范围内全部结果。
- `currentClassOnly()` / `includeSuper()` 是弃用旧名称；最后推荐使用版本为 `1.0.4`。

```kotlin
val methods = clazz.findAllMethods()
val declaredFields = clazz.findAllFields { findOnlyClass() }
```

Java 使用 `filterBy...` 链式追加条件，`first()` 取首项，`toList()` 取全部：

```java
import io.github.lingqiqi5211.ezhooktool.core.java.Methods;
import io.github.lingqiqi5211.ezhooktool.core.java.Fields;
import io.github.lingqiqi5211.ezhooktool.core.java.Constructors;

Method method = Methods.find(target)
        .filterByName("foo")
        .filterByParamCount(2)
        .filterByReturnType(String.class)
        .findAndSuper()
        .first();

Field field = Fields.find(target)
        .filterByName("mContext")
        .filterByType(Context.class)
        .findOnlyClass()
        .first();

Constructor<?> constructor = Constructors.find(target)
        .filterEmptyParam()
        .filterPublic()
        .first();
```

### 组合查询条件

| 目标 | 常用条件 |
| --- | --- |
| 方法名称 | `name`、`nameContains`、`nameStartsWith`、`nameEndsWith` |
| 方法 / 构造器参数 | `paramCount(2)`、`paramCountIn(1..3)`、`noParams()`、`hasParams()` |
| 完整参数类型 | `params(String::class.java)`；数量、顺序、类型必须一致 |
| 可赋值参数 | `paramsAssignableFrom(String::class.java)`；目标参数能接收给定类型 |
| 方法返回值 | `returnType`、`returnTypeExtendsFrom`、`voidReturnType()` |
| 字段名称与类型 | `name`、`nameContains`、`type`、`typeExtendsFrom` |
| 通用可见性 | `isPublic()`、`isPrivate()`、`isProtected()` |
| 方法 / 字段静态性 | `isStatic()`、`notStatic()` |
| 方法修饰符 | `isFinal()`、`isAbstract()`、`isNative()`、`isSynchronized()`、`isVarArgs()`、`isSynthetic()`、`isBridge()`、`isDefault()` |
| 字段修饰符 | `isFinal()`、`isVolatile()`、`isTransient()`、`isEnumConstant()`、`isSynthetic()` |
| 构造器修饰符 | `isVarArgs()`、`isSynthetic()` |

例如宽松匹配一个公开、非静态方法：

```kotlin
val method = clazz.findMethod {
    nameContains("open")
    paramCountIn(1..3)
    paramsAssignableFrom(String::class.java)
    returnTypeExtendsFrom(CharSequence::class.java)
    isPublic()
    notStatic()
}
```

Java 对应使用 `filterByAssignableParamTypes`、`filterByReturnTypeExtendsFrom` 等：

```java
Method method = Methods.find(target)
        .filterByNameContains("open")
        .filterByAssignableParamTypes(String.class)
        .filterByReturnTypeExtendsFrom(CharSequence.class)
        .filterPublic()
        .first();
```

特殊匹配：

- `parameterTypesVague(String::class.java, VagueType, Boolean::class.javaObjectType)`：方法和构造器参数数量固定，
  `VagueType` 位置跳过精确匹配，其余位置仍要求类型相等。
- `genericParameterTypes(GenericTypeMatcher.typeVariableNamed("T"))`：匹配方法或构造器擦除前的
  `genericParameterTypes`，可区分真正的 `TypeVariable` 与已擦除参数。
- `genericReturnType(GenericTypeMatcher.rawType(List::class.java))`：匹配方法擦除前的 `genericReturnType`。
  上述泛型条件禁用自动查询缓存。
- `filter { ... }`：自定义条件。内部再调用查找器会产生警告，优先使用结构化条件；没有手动 `cacheKey` 时不缓存。

Java 入口使用同义的 `filterBy...`、`filterPublic()`、`filterStatic()` 等命名。

### 调用、字段读写与 descriptor

```kotlin
val value = obj.callMethod("getValue")
obj.putField("enabled", true)

val method = getMethodByDesc("Lcom/example/Foo;->doTask(Ljava/lang/String;I)V")
val field = getFieldByDesc("Lcom/example/Foo;->name:Ljava/lang/String;")
```

```java
Object value = Methods.callMethod(obj, "getValue");
Fields.setBooleanField(obj, "enabled", true);
```

`getMethodByDescOrNull` / `getFieldByDescOrNull` 是可空形式。descriptor 格式错误抛
`IllegalArgumentException`；格式合法但成员不存在，严格入口抛 `MemberNotFoundException`，可空入口返回 `null`。
查找错误中的 `Search` 只描述当前类或当前类加父类，不代表接口继承图，也不承诺遍历该图。

BestMatch 的类型重载与实参重载使用同一选择规则：

- 精确类型优先于 primitive/wrapper 对应，再比较引用类型具体程度；多参数逐位比较，不能用总分掩盖歧义。
- 无法唯一选择时抛 `SingleResultExpectedException`，不按反射枚举顺序任选；覆写优先于同签名父类成员，非 bridge 优先。
- `null` 只匹配引用类型；不相关的最具体候选仍报告歧义。
- 不隐式拓宽数值或打包 vararg；vararg 数组须作为单个实参传入。
- 自动实例 / 静态调用在候选选择前过滤调用模式；公开 `findMethodBestMatch` 本身仍可查找两者。
- 默认成员解析器区分正常空结果与枚举失败；备用访问也不可用时保留原链接错误，不缓存成“没有成员”。

### 查询计划、缓存与加载器生命周期

查询块结束后生成独立 `QueryPlan`。匹配与缓存 key 使用同一份条件、搜索范围和结果模式；重复条件按 AND 保留，
`findSingle()`、首项和批量结果不混用，嵌套成员条件也保留完整计划。参数类型数组在登记条件时复制，
原数组后续修改不改变计划；用户 lambda 内部状态不在快照范围内，手动 `cacheKey(...)` 的一致性由调用者负责。

缓存保存最终结果，不保存扫描过程：

| 查询 | 缓存内容 |
| --- | --- |
| `loadClass` / `loadClassOrNull` | `ClassLoader + 类名` 对应的成功结果 |
| `loadClassFirst` / `loadClassFirstOrNull` | `ClassLoader + 候选名列表` 对应的首个成功结果 |
| 类条件查询 | 结构化条件或手动 `cacheKey` 对应的结果 |
| `findMethod` / `findField` / `findConstructor` | 结构化条件对应的首项 |
| `findAllMethods` / `findAllFields` / `findAllConstructors` | 可缓存条件对应的完整列表 |
| BestMatch | 名称与参数类型对应的匹配结果 |

找不到的类或成员不缓存。带条件的 `findAll` 只扫描一次，不预热其他查询；批量结果返回独立列表，
修改列表不影响后续查询。无手动 key 的自定义条件不自动缓存。

当前缓存是同步有界 LRU，类与成员查询**合计最多 4096 条**，超过 **256 项**的集合结果不缓存。
有效期间强引用键与结果，不靠弱引用自动释放加载器；成员枚举、解析器和用户条件在缓存锁外执行。

```kotlin
EzReflect.clearCache()
EzReflect.cacheEnabled = false
```

`init()`、`reset()`、`clearCache()`、解析器替换和缓存开关变更会发布新配置/缓存状态，清空旧缓存并停止其写入。
一次同步查询从 DSL 构建到结果选择都使用同一状态，嵌套查询沿用；查询回调中改变配置只影响后续顶层查询。
默认加载器进入查询状态后才解析，显式传入的加载器不会被替换。

临时加载器用完应清缓存；若仍是默认加载器，还需 `reset()` 或切换加载器。清缓存不会释放调用者持有的
反射结果、监听器和回调，不能据此认定整个加载器已可回收。

<a id="hook"></a>

## Hook：初始化与回调

### 先区分 82 与 101 / 102

- **82** 使用 `de.robv.android.xposed` 的生命周期，不使用 `XposedInterface`，也没有本页的 102 热重载流程。
- **101 / 102** 使用 `hook-xposed-102`：普通 before / after / replace、`Chain` / `intercept`、remote preferences / files
  都属于 101 能力。只有 hook ID、原子替换、entry detach 和热重载回调需要 102。
- `@RequiresXposedApi(102)` 标记不能按原语义降级的入口；调用前判断所需特性，不能把它们当作 101 普通 Hook 的前置条件。

若模块需要被 101 framework 加载，`META-INF/xposed/module.prop` 使用：

```properties
minApiVersion=101
targetApiVersion=102
```

不要为这种配置声明 `autoHotReload`。API 102 的自动热重载配置见[热重载章节](#hot-reload)。

### 初始化目标环境

**经典 Xposed 82**：模块入口实现 `IXposedHookZygoteInit` 和 `IXposedHookLoadPackage`，分别转发：

```kotlin
override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
    EzXposed.initZygote(startupParam)
}

override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
    if (lpparam.packageName != TargetApp) return
    EzXposed.init(lpparam)
    installHooks()
}
```

`initZygote` 是可靠的模块路径来源，并自动初始化 `moduleRes`；`init(lpparam)` 设置目标加载器、包名和进程名。
未调用 `initZygote` 就执行 `initModuleResources` / `addModuleAssetPath` 会明确报错。

**libxposed 101 / 102**：在 `XposedModule` 中按阶段转发，不把模块加载与目标就绪混为一谈：

```kotlin
override fun onModuleLoaded(param: ModuleLoadedParam) {
    EzXposed.initOnModuleLoaded(this, param)
    EzXposed.onTargetReady { installHooks() }
}

override fun onPackageLoaded(param: PackageLoadedParam) {
    if (!param.isFirstPackage || param.packageName != TargetApp) return
    EzXposed.initOnPackageLoaded(param)
}

override fun onPackageReady(param: PackageReadyParam) {
    if (!param.isFirstPackage || param.packageName != TargetApp) return
    EzXposed.initOnPackageReady(param)
}
```

| 初始化入口 | 可用状态与时机 |
| --- | --- |
| `initOnModuleLoaded` | 解析 framework 特性，保存 `base`、模块路径、进程名和 `isSystemServer`，初始化模块资源；尚未设置目标加载器 |
| `initOnPackageLoaded` | 只记录 package 信息 |
| `initOnPackageReady` | 初始化目标 `EzReflect.classLoader`，触发 `onTargetReady` |
| `initOnSystemServerStarting` | 初始化 system_server 加载器并触发 `onTargetReady`；不要假设存在 app Context |
| `initOnPackageLoadedAsTargetReady` | Android Q+ 的提前路径，在 AppComponentFactory 创建前以 `defaultClassLoader` 触发就绪 |

需要提前安装时，用 `initOnPackageLoadedAsTargetReady(param)` 替换普通 `initOnPackageLoaded(param)`；
后续 `initOnPackageReady` 会被忽略，初次加载和热重载均保持该加载器。**这条提前路径要求 Android Q**，低版本使用正常 ready 时机。

`targetReadyState` 是当前 generation 首次就绪状态：`NOT_STARTED`、`INITIALIZING`、`SUCCEEDED`、`FAILED`，
原因见 `targetReadyFailure`。分发期间新注册的回调进入队尾，执行后释放；首次成功后的新回调立即执行、不保存，
也不属于已提交的默认 Hook batch。失败不自动重试，再注册不能重启初始化。未启用默认 batch 时，首次初始化记录
单个回调异常后继续其余回调，最终仍为 `FAILED`；热重载恢复中的异常向调用方传播。
这些状态不代表后续即时回调或旧 Hook 清理的结果。

### Application 与模块资源的可用时机

82 和 101 / 102 都可通过 `runOnApplicationAttach` 等待 Application；若已经 attach，新注册回调在当前线程立即执行：

```kotlin
EzXposed.runOnApplicationAttach { context ->
    // context 是 Application context。
}
```

回调按登记顺序执行，异常记日志且不阻断其他回调。libxposed 必须先调用 `initOnModuleLoaded`；82 的此入口不要求先调
`initZygote`。过早读取 `appContext` 会抛异常，不确定时用 `appContextOrNull`。

`initAppContext(context)` 默认只在缓存为空时保留 `applicationContext` 或传入的 Application，不可用时抛
`IllegalStateException`；已有值不覆盖。`force=true` 原样缓存指定 Context，调用者承担 Activity 泄漏等生命周期风险。
其可选资源注入始终针对原始入参，不因缓存已有值而跳过。

libxposed 同一 generation 的模块资源成功初始化后不重复创建，创建失败可以重试 `initOnModuleLoaded`；
`initModuleResources()` 是显式刷新，每次创建新模块资源。82 可用 `initModuleResources(origRes)` 复用指定资源配置。
刷新 `moduleRes` 不等于重新登记已求值的替换值，见[资源章节](#resources)。

### Kotlin / Java 安装回调

Kotlin 的方法与构造器均可使用 `createHook`；批量方法使用 `createHooks`：

```kotlin
method.createHook {
    before { param ->
        param.args[0] = param.argAs<String>(0).trim()
    }
    after { param -> param.result = "done" }
}

clazz.findAllMethods {
    name("foo")
    paramCount(1)
}.createHooks {
    before {
        // 处理本次调用。
    }
}
```

Java 使用 `xposed.java` 门面；以下普通 Hook 在 82 与 libxposed 101 / 102 均有对应入口：

```java
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.Hooks;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IReplaceHook;

Hooks.createHook(method, new IMethodHook() {
    @Override
    public void before(HookParam param) {
        Object[] args = param.getArgs();
        args[0] = ((String) args[0]).trim();
    }

    @Override
    public void after(HookParam param) {
        param.setResult("done");
    }
});

Hooks.createHook(method, new IReplaceHook() {
    @Override
    public Object replace(HookParam param) {
        return null;
    }
});

Hooks.findAndHookMethod(target, "foo", String.class, new IMethodHook() {
    @Override
    public void before(HookParam param) {
        // 处理本次调用。
    }
});

List<Method> methods = Methods.find(target)
        .filterByName("foo")
        .filterByParamCount(1)
        .toList();
Hooks.createHooks(methods, new IMethodHook() {
    @Override
    public void before(HookParam param) {
        // 处理本次调用。
    }
});
```

libxposed 的 `intercept` 用于直接控制 `XposedInterface.Chain`，**101 即可使用**：

```kotlin
method.createInterceptHook { chain ->
    if (isFeatureEnabled()) patchedResult() else chain.proceed()
}
```

```java
Hooks.intercept(method, chain -> {
    Object[] args = chain.getArgs().toArray();
    return chain.proceed(args);
});
```

### safeMode 与异常

`EzXposed.safeMode` 默认开启，保护普通 DSL / Java helper 的调用阶段，不是任意副作用的事务：

| 失败阶段 | 回退语义 |
| --- | --- |
| before / replace | 丢弃失败回调的参数和输出修改，继续下游，不绕过其他 Hook 手工调用原方法 |
| after | 恢复进入回调前的结果或异常，不重复执行原方法 |
| intercept 尚未 `proceed()` | 放行下游调用 |
| intercept 已 `proceed()` | 保留下游结果或异常，不重复执行 |
| 原方法自身异常 | 原样传播，不视为模块 callback 失败 |

保护包括调用状态与参数数组浅拷贝，不撤销参数对象内部修改或外部副作用。82 在 callback 成功后才提交输出，
失败时还原参数数组；其 `HookParam.raw` 直接操作 framework 对象，不具有同等保护。用户 logger 抛异常不会阻断安全回退。

libxposed 的 `deoptimize(executable)` 保留 framework 的 Boolean，异常时返回 `false`；
`deoptimizeOrThrow(executable)` 可区分返回 `false` 与抛异常，未初始化时也会明确报错。

### 102 可选能力：ID、替换与 detach

在 `initOnModuleLoaded` 之后查询能力；初始化前均为 `false`，不应缓存这个早期结果：

```kotlin
XposedFeature.HOOK_ID.isSupported
XposedFeature.REPLACE_HOOK.isSupported
XposedFeature.HOT_RELOAD.isSupported
XposedFeature.DETACH_ENTRY.isSupported
XposedFeature.HOOK_ID.minApiVersion  // 102
EzXposed.frameworkApiVersion        // 未初始化时为 0
```

能力来自 framework 报告的 API 版本，不反射探测 Xposed API；`isSupported` 只判断能力，不会调用它。
无法降级的 helper 通常抛带所需版本、特性名和当前版本的 `IllegalStateException`。

| 操作 | API 101 framework | API 102 framework |
| --- | --- | --- |
| 普通 Hook、`Chain` / `intercept` | 可用 | 可用 |
| 自动 Hook ID | 不分配、不调用 `setId` | 热重载启用时分配 |
| `groupById()` / Java `HookHandles.getId(handle)` | ID 为 `null` | 返回可见的底层 ID，聚合逻辑 handle 为 `null` |
| `id(nonNull)` / `reloadKey` | 安装时抛异常 | 可用 |
| `id(null)` | 可匿名安装 | 关闭自动 ID，不能放进默认 batch |
| `replaceWith` / `replaceIntercept` / `replaceAll` / `Hooks.replaceHook` | 抛异常 | 可用 |
| `HotReloadSession` / `HookReloadBatch` | 构造即抛异常 | 初始化运行时后可用 |
| `handleHotReloaded` 系列 / `detachCurrentEntry` | 不可用 | 可用 |
| `handleHotReloading` | 不支持时警告并返回 `false` | 按当前状态决定是否接受 |

`HookFactory.id(...)` 指定底层 ID，`reloadKey(...)` 进一步表示跨版本稳定契约；默认自动热重载通常不需要逐条声明。
同一 executable 内显式 key 必须区分不同逻辑 Hook，不要使用库保留的 `ezhooktool.batch.v1:` 命名空间。

```kotlin
val handle = method.createHook {
    reloadKey("license-check")
    before {
        // 检查本次调用。
    }
}
val newHandle = handle.replaceWith { true }
// 需要 Chain 控制时改用 handle.replaceIntercept { chain -> ... }。
```

```java
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookHandles;

HookHandle handle = Hooks.createHook(method, "license-check", methodHook);
HookHandle newHandle = Hooks.replaceHook(handle, methodHook);  // IMethodHook
// 或 Hooks.replaceHook(handle, replaceHook)，其中 replaceHook 是 IReplaceHook。
String id = HookHandles.getId(newHandle);
```

helper 替换保留 executable、priority、exceptionMode、ID，并继续提供 safeMode；成功后旧 handle 失效。
原生 `handle.replaceHook(myHooker)` 也可在确认 102 能力后调用，但原生 Hooker 的保护由调用方负责。

**不要在兼容 101 的代码里直接读 `handle.id`**：Kotlin 优先把它解析成 Java `getId()`，会导致
`NoSuchMethodError`，不是安全扩展。Java 使用上面的 `HookHandles.getId`；Kotlin 可用安全的 `groupById()` 分组，
或先判断 `XposedFeature.HOOK_ID.isSupported` 再调用原生 `getId()`。默认聚合返回逻辑 handle，内部物理 ID 不对其暴露。

`EzXposed.detachCurrentEntry()` 停止向当前 entry 分发后续生命周期回调，不撤销已装 Hook，也不禁用其他
`XposedInterface` API。它幂等，但要求 `initOnModuleLoaded` 传入 `XposedInterfaceWrapper`（如 `XposedModule`），
否则抛 `IllegalStateException`。只适合确认无需后续回调的非目标 entry；detach 后也收不到 `onHotReloading`，
需要热重载的目标 entry 不要调用。

<a id="resources"></a>

## 资源：直接值、模块预求值与宿主绑定

`EzResources` 在 82 与 libxposed 101 / 102 提供相同入口，按「包名 + 类型 + 名称」拦截支持的
`Resources` / `TypedArray` getter。实现思路来自 HyperCeiler 的 `ResourcesTool`，不要求 framework 提供专用资源替换接口。
包名 `"*"` 表示通配，精确规则优先。先完成上文对应运行时初始化，再登记规则。

### 选择三种方式

| 方式 | 现有 API | 取值与副作用 |
| --- | --- | --- |
| 直接值 | `setObjectReplacement` / `setDensityReplacement` | 不注入模块 APK；尺寸替换按密度计算 |
| 模块预求值 | 先读 `EzXposed.moduleRes`，再 `setObjectReplacement` | 模块内求值后登记固定结果，不绑定宿主资源池 |
| 宿主绑定 | 显式 `inject`；或兼容入口 `setResReplacement` 在命中时注入 | 模块 APK 进入宿主资源池，引用、Theme、命名空间都受宿主环境约束 |

**直接值：**

```kotlin
EzResources.setObjectReplacement("com.example.host", "color", "bg_color", Color.RED)
EzResources.setDensityReplacement("com.example.host", "dimen", "bar_height", 8f)
```

`setDensityReplacement` 只接受 `dimen`，按数值乘屏幕密度计算，即 dp 语义。

**模块预求值：**

```kotlin
val title = EzXposed.moduleRes.getString(R.string.module_title)
EzResources.setObjectReplacement("com.example.host", "string", "title", title)
```

这个 `title` 固定到下一次登记，**不会自动跟随宿主 locale、配置或 Theme 重新求值**。
需要更新时由模块重新读取并登记；不是新增的自动隔离求值 API，也不把任意 Drawable 等对象冻结。

**宿主绑定：**

```kotlin
check(EzResources.inject(context))
// 也可注入指定 Resources：EzResources.inject(resources)。
// 兼容便利入口：EzXposed.addModuleAssetPath(context)。

EzResources.setResReplacement("com.example.host", "drawable", "ic_launcher", R.drawable.my_icon)
```

`inject` 只使宿主能解析模块 `R.xxx`，本身不登记替换。R+ 优先使用 `ResourcesLoader`，低版本或相应回退走
`AssetManager.addAssetPath`；资源 ID / 包命名空间须避免与宿主及其他模块冲突。
`addModuleAssetPath(Context / Resources)` 委托给 `inject`，失败抛异常。

legacy `setResReplacement` **仍会在首次命中时注入宿主**，不是模块内隔离求值：它记录模块资源名称，
取值时对当前资源表解析 ID，并保留 getter 的 density、Theme、fraction、quantity 和格式化参数。
这不保证模块与宿主的主题属性或命名空间兼容。

`inject(..., onMainLooper = true)` 在非主线程变为异步投递，返回 `true` 不代表已挂载成功；
热重载恢复期间禁止该选项，必须同步注入。注入失败会记录，getter 热路径不会反复重试。

### getter 覆盖与值类型

| type | 对应 getter |
| --- | --- |
| `color` | `Resources` / `TypedArray` 的 `getColor`、`getColorStateList` |
| `drawable`、`mipmap` | `Resources.getDrawable` / `getDrawableForDensity`，`TypedArray.getDrawable` |
| `string` | 两类的 `getText` / `getString`；含 `Resources.getText(id, default)` 与格式化重载 |
| `plurals` | `Resources.getQuantityText` / `getQuantityString` |
| `array` | `Resources.getStringArray` / `getTextArray` / `getIntArray`，`TypedArray.getTextArray` |
| `dimen` | 两类的 `getDimension` / `getDimensionPixelOffset` / `getDimensionPixelSize` |
| `integer`、`bool`、`fraction` | 两类存在的 `getInteger` / `getInt` / `getBoolean` / `getFloat` / `getFraction` |
| `layout`、`anim` | `Resources.getLayout` / `getAnimation`；相关引用仍须能由宿主资源池解析 |

TypedArray 只处理公开 `getResourceId` 能取得 ID 的条目，不还原原始 `?attr` 或内联字面值；
**只支持直接值和尺寸替换，不支持模块资源转发**。Theme / TypedValue 读取与布局内部引用不属于 getter 覆盖承诺。
直接文本替换 plurals 会让所有数量使用相同文本；Android 复数选择规则需使用模块资源转发。

值类型必须与 getter 相符：`getText` 要求 `CharSequence`，`getBoolean` 要求 `Boolean`，数值 getter 接受
`Number`；不匹配时警告并放行原值。整数和颜色不经过 Float 转换；pixel offset 截断，pixel size 按尺寸舍入，
保留非零尺寸的最小像素。数组在登记与返回时复制，Drawable、可变 CharSequence 等对象内部共享由调用者负责。

### 规则、缓存与绑定生命周期

- getter Hook 按类型按需安装，进程级复用；从未登记规则时不安装这些 Hook。102 用稳定 key 参与换代，101 使用匿名 Hook，
  模块无需持有或手动摘除这些 handle。
- 一次 getter 使用同一规则快照。定位缓存按 `Resources` 弱引用与 `AssetManager` 来源分区，每种定位表最多 4096 项。
  注入（含部分失败）和回退使来源失效，旧查询不能回填新表。
- 定位与最终值分开：不缓存 Theme、density、locale 等决定的最终值，转发按本次参数求值；这不改变上文预求值结果的固定性。
  外部直接修改同一 AssetManager 内容无法自动感知，应显式 `inject(resources)` 使库内定位失效。
- `clearReplacements()` 清除规则和类型告警，**不卸载 getter Hook、不解绑 loader**；来源未变时定位缓存仍有效。
- `fakeResId(name)` 只是兼容用 hash 标识，可能冲突，**不创建真实 Android 资源**，也不保证宿主能解析。

R+ 的 provider 随自有 loader 绑定保留；只有旧绑定全部迁移成功，才释放确认自有的旧 provider。
旧 saved state 没有所有权信息时不主动关闭。换代失败遵循下节的恢复边界，不以清规则代替释放资源绑定。

<a id="hot-reload"></a>

## 热重载与失败恢复

### 启用默认自动模式（仅 API 102）

默认模式由工具收集 Hook、逐条替换并收尾旧 handle；模块不用再登记或持有一份 handle 列表。
所有要迁移的 helper Hook 都应在 `onTargetReady` 的**同步窗口**内安装。

要求 API 102 并启用 APK 更新自动触发时，在 `META-INF/xposed/module.prop` 声明：

```properties
minApiVersion=102
targetApiVersion=102
autoHotReload=true
```

启用 `autoHotReload` 的模块应只保留一个 Java entry。在前文 libxposed 初始化片段之外，再桥接两个回调：

```kotlin
override fun onHotReloading(param: HotReloadingParam): Boolean =
    EzXposed.handleHotReloading(param)

override fun onHotReloaded(param: HotReloadedParam) {
    EzXposed.handleHotReloadedWithTargetReady(this, param, { installHooks() })
}
```

framework 不会重放 `onModuleLoaded`、package 或 system_server 生命周期；新代码只收到 `onHotReloaded`。
`handleHotReloadedWithTargetReady` 初始化新 generation、注册回调、恢复目标 snapshot 并重装 Hook，
返回 `AutomaticHotReloadResult`。已经自行初始化并注册回调的流程可调用 `handleHotReloaded`，
或使用返回统计结果的 `restoreHotReloadedAutomatically`。

`onHotReloading` 必须原样返回工具结果，`false` 意味着取消整个请求。目标未成功 ready、已有失败状态或当前 batch
不可恢复时会拒绝。首次从无 Hook ID 的旧版本迁移，需完整重启一次目标进程，不能强行把旧 handle 当成可迁移项。

`hotReloadEnabled` 默认 `true`。如需禁用，在 `initOnModuleLoaded` **之前**设置 `false`；之后不分配自动 ID、
不进入默认 batch，`handleHotReloading` 静默返回 `false`。`hotReloadActive` 等于该开关与 framework 能力同时满足。
禁用不阻止显式 `id` / `reloadKey` 或手动 `HookReloadBatch`；但 `HotReloadSession.prepare` 也走这个开关，会返回 `false`。
101 不提供热重载，普通 Hook 不受影响；按 102 编译保留的热重载覆写不会被 101 framework 调用，不能主动执行其中的 102 API。

### 安装、功能开关与多进程

默认 batch 按 `executable + priority + exceptionMode` 聚合无显式 ID 的 helper Hook。同步回调全部成功后才发布；
同组逻辑 Hook 的增删重排不改变物理 ID。显式 ID 也在 batch 收集结束后发布，但不与无 ID Hook 聚合。

提交时先安装新增 identity，再逐条替换相同 executable + ID，最后撤销新代码不再声明的旧项。
因此目标、priority / exceptionMode 组与显式 ID 都可以增删；只要 `onTargetReady` 确实执行，即使本代一个 Hook
也未声明，也能清理全部旧项。这是**单条原子替换**，不是跨多个 Hook 的整批事务。

```kotlin
private fun installHooks() {
    val switches = readHookSwitches()
    if (switches.loginReporter) {
        loginMethod.createBeforeHook {
            // 处理登录。
        }
        reportMethod.createBeforeHook {
            // 处理上报。
        }
    }
    if (switches.premium) {
        premiumMethod.createReplaceHook { true }
    }
}
```

更新 APK 时重新读取持久开关，把同一功能的全部同步 Hook 放进同一个分支即可。要在不更新模块时即时切换，
固定安装 Hook，让 callback 读取 remote preferences 或 listener 更新的线程安全状态；不能复用安装时的固定 snapshot。
before / after 关闭时直接返回，replace 类功能用 `intercept` 和 `chain.proceed()` 放行。

scope list 可含多个 app；主进程和 remote process 各自有 entry、snapshot 和结果，不会一次切换所有进程。
可以按恢复后的 `isSystemServer`、`packageName` 和 `processName` 分派：

```kotlin
override fun onSystemServerStarting(param: SystemServerStartingParam) {
    EzXposed.initOnSystemServerStarting(param)
}

private fun installHooks() {
    when {
        EzXposed.isSystemServer -> installSystemServerHooks()
        EzXposed.packageName == "com.example.alpha" &&
            EzXposed.processName.endsWith(":remote") -> installAlphaRemoteHooks()
        EzXposed.packageName == "com.example.alpha" -> installAlphaHooks()
        EzXposed.packageName == "com.example.beta" -> installBetaHooks()
    }
}
```

多 app 时把前文 package 回调的目标条件改为集合匹配，仍建议过滤 `isFirstPackage`。
默认恢复边界是一个进程的首个目标 package。shared UID 或 `createPackageContext(..., CONTEXT_INCLUDE_CODE)`
后来加载的第二个 package 属于另一个 ready 时点，不能塞入已提交的 batch，应使用显式 key 和自定义收尾。
异步、延迟、Hook 回调内才安装的规则，以及首次 ready 成功后的即时回调，也不属于默认可靠边界。
事务外 helper 虽有兜底 ID，仍应改成同步安装或明确自定义迁移。

### 自定义 identity、batch 与旧 handle

需要稳定的逐条 identity 时用 `reloadKey`。Java 有对应入口：

```java
Hooks.createHook(method, "license-check", methodHook);
Hooks.findAndHookMethodWithKey(target, "foo", "foo-hook", String.class, methodHook);
Hooks.findAndHookConstructorWithKey(target, "constructor-hook", String.class, methodHook);
```

只有需要自定义 namespace 或事务边界时才直接使用 `HookReloadBatch`：新 generation 在旧 handle 有效时
`captureOldHooks`，再 `install` 一次完整同步初始化，成功后 `finishHotReload`。默认流程已经完成这些工作，
不要与它或管理同一批 Hook 的 `HotReloadSession` 重叠。

完全自定义旧 handle 处置时，传 `onOldHooks`；工具不再默认替你全量 unhook：

```kotlin
EzXposed.handleHotReloaded(
    this,
    param,
    onOldHooks = java.util.function.Consumer { oldHandles ->
        // 自行替换、unhook 或保留；承担对应恢复边界。
    },
)
```

`id(null)` 关闭自动 ID，不能进入默认 batch；使用它必须在事务外定义旧 handle 收尾方式。
辅助函数保留：

- `oldHandles.groupById()` → `Map<String?, List<HookHandle>>`。
- `oldHandles.replaceAll(hooker)` → 按原顺序返回新 handle；中途失败不回滚已替换项。
- `oldHandles.unhookAll()` → 尝试全部清理，最后统一报告失败。

### 外部回调与 HotReloadSession.scope

listener、receiver、binder callback 和线程不是 HookHandle，模块必须停止或注销它们。
需要统一管理时选用 `HotReloadSession`，**替代**默认自动模式：一个 entry 使用一个 session，每条 helper Hook
都有显式 `reloadKey`，同一 executable 内不重复。它接管全部旧 handle，不与 raw libxposed Hook、其他 session
或其他旧 handle 管理方式混用。

每个 generation 在 `initOnModuleLoaded` 解析完 102 能力后再创建 session，不要在 entry 字段初始化时提前构造：

```kotlin
private lateinit var reloadSession: HotReloadSession

private fun registerTargetReady() {
    reloadSession = HotReloadSession()
    reloadSession.onTargetReady {
        watchedMethod.createHook {
            reloadKey("watched-method")
            before {
                // 处理本次调用。
            }
        }
        val registration = registerHostListener()
        reloadSession.scope.onReloading {
            registration.unregister()
            stopModuleThreads()
        }
        currentHostToken()?.let { reloadSession.scope.putState("host-token", it) }
    }
}

override fun onModuleLoaded(param: ModuleLoadedParam) {
    EzXposed.initOnModuleLoaded(this, param)
    registerTargetReady()
}

override fun onHotReloading(param: HotReloadingParam): Boolean =
    reloadSession.prepare(param)

override fun onHotReloaded(param: HotReloadedParam) {
    EzXposed.initOnModuleLoaded(this, param)
    registerTargetReady()
    reloadSession.restore(this, param) { extra ->
        val token = reloadSession.scope.state("host-token")
        // 在 target-ready callback 前恢复自己的状态。
    }
}
```

package / system_server 回调仍使用前文初始化入口。`prepare` 仅在 snapshot 可恢复时执行 cleanup，
按登记逆序尝试清理；成功后旧 generation 的外部注册已经停止，新代失败不能假设旧运行状态仍完整。
已开始的外部调用仍须自行结束。

**跨代数据不是任意对象的 deep freeze：**

- `handleHotReloading` 的业务 `extra` 与 scope 状态交接时，对 Map、Collection、对象数组和 primitive array 生成独立快照。
- 保留共享子对象别名及数组 runtime component type；最多 **32 层、4096 节点/槽位**，分配容器前检查预算。
  循环容器、不能保持数组类型、复制后会合并不同键或元素的情况明确拒绝。
- `IdentityHashMap` 保留身份键语义，其他集合转换为标准有序容器。
- 其他允许的 system / system_server / 目标 app 对象保留引用，不遍历、不隔离或撤销其内部状态。
  不得保存模块 data class、lambda、模块加载器，或通过容器带入模块对象；模块数组类型和依赖模块的子加载器也会拒绝。
  framework 仍执行最终校验。
- scope 开始准备后拒收新状态和 cleanup；准备失败恢复接收，成功后退休。清空旧 scope 不会清空已经交出的快照。
- Resources、loader、provider 的 framework 对象另走资源状态通道，不经过业务快照复制。

持久开关应来自 remote preferences、文件或系统服务，新 generation 自行读取，不要通过 saved state 搬运旧模块对象或 HookHandle。

### 资源迁移与失败后的处理

资源换代分三部分，不应因已注入资源而跳过 getter Hook 或否决整个请求：

1. getter Hook 由新一代按正常规则声明和替换，避免钉住旧模块加载器。
2. 注入过的宿主 Resources 与旧 loader / 自有 provider 经资源状态通道交接；在 `onTargetReady` 前先挂新 loader，再摘旧 loader。
3. 替换规则由新代重新登记，模块转发规则按名称解析当前 APK 的 ID。R 以下旧 `addAssetPath` 绑定不能撤回，需重启才能清除旧资源影响。

provider 仅在旧绑定全部迁移成功后释放。失败时遵循资源 journal 的回退或重启判断，不能关闭所有权不明的旧对象。
已 inflate 的 View、宿主静态缓存、SystemUI 缓存和非自有绑定不会自动刷新。

| 失败位置 | 保证与处理 |
| --- | --- |
| 默认 batch 仍在收集规则，未发布物理 Hook | 回调失败不发布该批 Hook；不撤销用户自己修改的状态或已停止的外部服务 |
| 新增 Hook 安装失败 | 尝试撤销本次已拿到 handle 的新增项；不能仅凭撤销成功断言安装无副作用 |
| 底层 `HookBuilder.intercept` 抛错 | **即使是首次物理安装，也可能已安装但未返回 handle**；结果不明，要求重启目标进程。101 普通安装同样适用 |
| 已开始旧 Hook 替换、自定义旧 handle 处置、直接 session 安装等不可逆操作 | 不把资源盲目退回旧版；失败保留当前资源并要求重启，可能已混合两代 Hook |
| 旧 Hook 清理失败 | 继续尝试其余清理，统一报告失败，阻止当前 entry 后续热重载；资源已提交，不再回滚 |

资源迁移、`onExtra`、自定义 `onOldHooks` 与首次 ready 回调共享异常处理边界。资源迁移失败不继续发布 Hook；
只有尚未发生不可逆或结果不明的操作时才尝试资源回退，撤销按逆序执行。若加回旧 loader 失败，不再摘对应新 loader，
避免移除最后一份可用资源，并要求重启。

这不是跨 generation 的全局失败锁。framework 如何继续处理失败 entry、真实 loader 是否按预期切换，仍需实机确认。
用户原生 API、异步任务与其他外部副作用不在库的回滚保证内；宿主没有可逆更新 API 时应重启目标进程。

<a id="validation"></a>

## 验证与兼容边界

纯 JVM 测试可验证查询计划、缓存界限、重载选择、回调状态、跨代快照、异常顺序与恢复控制流。
Android stub / fake **不能证明真实 Hook 生效、ResourcesLoader 切换或 Theme 效果**；这些行为尚未实机验证，
不能把逻辑测试通过写成 framework 兼容结论。

实机验收至少区分：

- 经典 82 与 libxposed 101 普通 Hook；101 不得触碰 102-only API。
- 102 的连续换代、目标增删、全部功能关闭，以及首次安装结果不明、部分替换和清理失败。
- Android 26 的 TypedArray 与旧注入路径；Q 的提前 target-ready；R+ 的 loader / provider 迁移和失败恢复。
- Theme / 命名空间冲突、locale / 复数、数组、mipmap、已 inflate View 和宿主缓存。
- 外部 listener / 线程的注销与结束，旧加载器是否仍被用户对象或宿主缓存引用。

源码验证入口是 `./gradlew build`，覆盖编译、lint、Core 单测与 `checkApi102Gateway` 等检查；
它不能替代上述实机验收。
