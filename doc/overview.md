# EzHookTool API Guide

EzHookTool 的 API 分成三类：

- `core`：反射、类查找、方法查找、字段读写、实例创建。
- `core.java`：给 Java 调用的入口，例如 `Classes`、`Methods`、`Fields`、`Constructors`。
- `xposed.dsl` / `xposed.java`：给 Kotlin / Java 使用的 hook 入口。

本项目不做 dex 扫描。`findClassIf` 通过 `EzReflect.classResolver` 从目标 `ClassLoader` 获取可查询类名，再按条件筛选；成员查找只在给定 `Class` 的成员和父类成员中查找。

## Kotlin

按条件查找类：

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
    hasField {
        name("token")
    }
}
```

查找单个方法：

```kotlin
val method = clazz.findMethod {
    name("foo")
    paramCount(2)
    returnType(String::class.java)
}
```

查找多个方法：

```kotlin
val methods = clazz.findAllMethods {
    name("foo")
    paramCount(2)
    findAndSuper()
}
```

不写条件时会返回当前查找范围内的全部结果：

```kotlin
val methods = clazz.findAllMethods()
val declaredFields = clazz.findAllFields {
    findOnlyClass()
}
```

常用方法条件：

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

查找字段：

```kotlin
val field = clazz.findField {
    name("mContext")
    type(Context::class.java)
    findOnlyClass()
}
```

查找构造器：

```kotlin
val constructor = clazz.findConstructor {
    noParams()
    isPublic()
}
```

安装 hook：

```kotlin
method.createHook {
    before { param ->
        val text = param.argAs<String>(0)
        param.args[0] = text.trim()
    }

    after { param ->
        param.result = "done"
    }
}
```

批量 hook：

```kotlin
clazz.findAllMethods {
    name("foo")
    paramCount(1)
}.createHooks {
    before {
        // ...
    }
}
```

## Java

Java 代码建议使用 `core.java` 包里的入口。链式查询方法使用 `filterBy...` 命名，能直接看出每一步是在追加查找条件。

查找类：

```java
import io.github.lingqiqi5211.ezhooktool.core.java.Classes;

Class<?> target = Classes.loadClass("com.example.Target");
```

多个候选类名按顺序兜底：

```java
Class<?> target = Classes.loadClassFirst(
        "com.example.Target",
        "com.example.a"
);
```

> Java 端目前没有「按类名 + 内含成员条件挑选类」的条件查找入口（Kotlin 的 `findClassIf { ... }`）；
> 需要类似能力时请在 Kotlin 侧暴露便利方法或自行组合 [Classes.loadClass] + 反射检查。

查找方法：

```java
import io.github.lingqiqi5211.ezhooktool.core.java.Methods;

Method method = Methods.find(target)
        .filterByName("foo")
        .filterByParamCount(2)
        .filterByReturnType(String.class)
        .findAndSuper()
        .first();
```

更宽松的参数匹配：

```java
Method method = Methods.find(target)
        .filterByNameContains("open")
        .filterByAssignableParamTypes(String.class)
        .filterByReturnTypeExtendsFrom(CharSequence.class)
        .filterPublic()
        .first();
```

查找字段：

```java
import io.github.lingqiqi5211.ezhooktool.core.java.Fields;

Field field = Fields.find(target)
        .filterByName("mContext")
        .filterByType(Context.class)
        .findOnlyClass()
        .first();
```

查找构造器：

```java
import io.github.lingqiqi5211.ezhooktool.core.java.Constructors;

Constructor<?> constructor = Constructors.find(target)
        .filterEmptyParam()
        .filterPublic()
        .first();
```

安装 before / after hook：

```java
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.Hooks;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

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
```

安装 replace hook：

```java
import io.github.lingqiqi5211.ezhooktool.xposed.java.IReplaceHook;

Hooks.createHook(method, new IReplaceHook() {
    @Override
    public Object replace(HookParam param) {
        return null;
    }
});
```

查找并 hook 单个方法：

```java
Hooks.findAndHookMethod(target, "foo", String.class, new IMethodHook() {
    @Override
    public void before(HookParam param) {
        // ...
    }
});
```

批量 hook：

```java
List<Method> methods = Methods.find(target)
        .filterByName("foo")
        .filterByParamCount(1)
        .toList();

Hooks.createHooks(methods, new IMethodHook() {
    @Override
    public void before(HookParam param) {
        // ...
    }
});
```

## 查找范围

方法和字段默认使用智能查找：先查当前类，找不到再查父类。

- `findOnlyClass()`：只查当前类。
- `findAndSuper()`：查当前类和全部父类。

不写这两个开关时，就是默认智能查找。

旧名称 `currentClassOnly()` / `includeSuper()` 已标记弃用，最后推荐使用版本为 `1.0.4`。

查找范围写在查询块里：

```kotlin
clazz.findMethod {
    findOnlyClass()
    name("foo")
}
```

## 查询条件

方法查询常用条件：

- `name("foo")` / `nameContains("foo")` / `nameStartsWith("get")` / `nameEndsWith("Locked")`
- `paramCount(2)` / `paramCountIn(1..3)` / `noParams()` / `hasParams()`
- `params(String::class.java)`：完整参数类型，数量和顺序必须一致。
- `paramsAssignableFrom(String::class.java)`：目标方法参数能接收这些类型。
- `returnType(String::class.java)` / `returnTypeExtendsFrom(CharSequence::class.java)` / `voidReturnType()`
- `isStatic()` / `notStatic()` / `isPublic()` / `isPrivate()` / `isProtected()`
- `isFinal()` / `isAbstract()` / `isNative()` / `isSynchronized()`
- `isVarArgs()` / `isSynthetic()` / `isBridge()` / `isDefault()`
- `filter { ... }`：自定义条件。`filter` 里再调用查找器会产生警告，建议优先使用结构化条件。

字段查询常用条件：

- `name("mContext")` / `nameContains("context")`
- `type(Context::class.java)` / `typeExtendsFrom(Context::class.java)`
- `isStatic()` / `notStatic()`
- `isPublic()` / `isPrivate()` / `isProtected()`
- `isFinal()` / `isVolatile()` / `isTransient()`
- `isEnumConstant()` / `isSynthetic()`
- `filter { ... }`：自定义条件。`filter` 里再调用查找器会产生警告，建议优先使用结构化条件。

构造器查询常用条件：

- `paramCount(2)` / `paramCountIn(1..3)` / `noParams()` / `hasParams()`
- `params(String::class.java)`：完整参数类型，数量和顺序必须一致。
- `paramsAssignableFrom(String::class.java)`：目标构造器参数能接收这些类型。
- `isPublic()` / `isPrivate()` / `isProtected()`
- `isVarArgs()` / `isSynthetic()`
- `filter { ... }`：自定义条件。`filter` 里再调用查找器会产生警告，建议优先使用结构化条件。

Java 入口使用同义的 `filterBy...`、`filterPublic()`、`filterStatic()` 等方法。

## 查询缓存

缓存保存的是最终查找结果，不保存查找过程。

会缓存的结果：

- `loadClass` / `loadClassOrNull`：按 `ClassLoader + 类名` 缓存成功加载的类。
- `loadClassFirst` / `loadClassFirstOrNull`：按 `ClassLoader + 候选类名列表` 缓存第一个成功结果。
- `findClassIf` / `findClassIfOrNull` / `findAllClassesIf`：按结构化条件或 `cacheKey(...)` 缓存查询结果。
- `findMethod` / `findField` / `findConstructor`：按结构化查询条件缓存第一个匹配结果。
- `findAllMethods` / `findAllFields` / `findAllConstructors`：按结构化查询条件缓存完整列表。
- best-match 查找：按名称和参数类型缓存匹配结果。

不会缓存的内容：

- 找不到的类或成员。
- 没有 `cacheKey(...)` 的自定义 `filter` 查询。

`findAll` 的行为：

1. 带查询条件的 `findAll` 只扫描一次。
2. 查询可缓存，或手动指定 `cacheKey(...)` 时，会缓存完整列表。
3. 列表里的每个结果会按精确签名写入单个查找缓存。

缓存只保存在当前运行期内。高频命中的缓存会刷新访问记录；低频缓存会在内部清理条件达成后自动释放。

关闭缓存会释放已有缓存，也可以手动清除：

```kotlin
EzReflect.cacheEnabled = false
EzReflect.clearCache()
```

`init()`、`reset()` 和 `clearCache()` 都会清空缓存。

## 调用和字段读写

Kotlin：

```kotlin
val value = obj.callMethod("getValue")
obj.putField("enabled", true)
```

Java：

```java
Object value = Methods.callMethod(obj, "getValue");
Fields.setBooleanField(obj, "enabled", true);
```

## libxposed 102 intercept

`intercept` 只用于需要直接操作 `XposedInterface.Chain` 的场景。

```java
Hooks.intercept(method, chain -> {
    Object[] args = chain.getArgs().toArray();
    return chain.proceed(args);
});
```

## libxposed 102 hook id 与替换

`HookFactory.id(...)` 给当前 hook 分配一个模块和 executable 范围内唯一的 id。
之后用同一个 id 在同一个 executable 上创建新 hook，旧 hook 会被原子替换，原 handle 失效。
如果这个 id 是为了跨模块更新保持稳定，请使用语义更明确的 `reloadKey(...)`；底层仍完全使用
libxposed 原生的 id/原子替换机制。

```kotlin
val handle = method.createHook {
    reloadKey("license-check")
    before {
        // ...
    }
}
```

拿到旧 handle 后，用 `replaceWith` / `replaceIntercept` 用 lambda 直接替换：

```kotlin
val newHandle = handle.replaceWith { /* HookParam */ true }
```

也可以传 libxposed 原生 `Hooker`，直接走接口成员：

```kotlin
val newHandle = handle.replaceHook(myHooker)
```

替换会保留原 hook 的 `executable`、`priority`、`exceptionMode` 和 `id`；调用成功后原 handle 不再可用。
替换后的 hook 也会沿用 `EzXposed.safeMode` 的保护。

Java 调用方：

```java
HookHandle newHandle = Hooks.replaceHook(oldHandle, methodHook);  // IMethodHook
HookHandle newHandle = Hooks.replaceHook(oldHandle, replaceHook); // IReplaceHook
HookHandle newHandle = oldHandle.replaceHook(hooker);             // 原生 Hooker

// 新建、可参与 HotReloadSession 的 Java hook：
HookHandle handle = Hooks.createHook(method, "license-check", methodHook);
```

`HookHandle.id` 是 `getId()` 的 Kotlin 直通属性：

```kotlin
val current: String? = handle.id
```

## libxposed 102 entry detach

`EzXposed.detachCurrentEntry()` 停止 framework 向当前 module entry 分发后续生命周期回调；
已注册的 hook 与其它 `XposedInterface` API 不受影响。

适合的场景：

- 当前 entry 的初始化已完成，不再需要后续 `onPackageLoaded` 等回调。
- 多 entry 模块里，当前 entry 检测到自己不在目标 app 中，立即停止接收回调。

```kotlin
override fun onPackageReady(param: PackageReadyParam) {
    if (param.packageName != TargetApp) {
        EzXposed.detachCurrentEntry()
        return
    }
    EzXposed.initOnPackageReady(param)
    // ...
}
```

`detach()` 幂等，多次调用等价于一次。该入口需要 `EzXposed.initOnModuleLoaded` 传入的是
`XposedInterfaceWrapper`（即 `XposedModule` 或其子类）；否则会抛 `IllegalStateException`。

## libxposed 102 热重载

API 102 的热重载由 framework 在 `onHotReloading` 时发起。要让模块 APK 更新后自动触发，
在 `META-INF/xposed/module.prop` 中声明：

```properties
minApiVersion=102
targetApiVersion=102
autoHotReload=true
```

启用 `autoHotReload` 的模块应只保留一个 Java entry。framework 不会因为热重载重新分发
`onPackageLoaded`、`onPackageReady` 或 `onSystemServerStarting`；新 entry 会先收到
`onModuleLoaded`，随后收到 `onHotReloaded`。因此必须把当前目标进程的 classloader 等信息保存并恢复。

### 推荐：HotReloadSession

`HotReloadSession` 是 API 102 的推荐入口。它不重新实现 hook 替换：每个新 hook 仍通过
libxposed 原生 `executable + id` 规则原子替换旧 hook。session 只负责保存目标 snapshot、在新 hook
全部同步安装成功后清理未复建的旧 handle，并把初始化错误直接反馈给 framework。

```kotlin
class MainHook : XposedModule() {
    private val hotReload = HotReloadSession()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        hotReload.onTargetReady {
            installHooks()
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == TargetApp) {
            EzXposed.initOnPackageLoaded(param)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == TargetApp) {
            EzXposed.initOnPackageReady(param)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        hotReload.prepare(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        val result = hotReload.restore(this, param)
        // result 可用于模块自己的日志、通知或 Toast 反馈。
    }
}
```

在 `installHooks()` 中，每个会改变目标进程行为的 hook 都要显式声明稳定 `reloadKey`：

```kotlin
method.createHook {
    reloadKey("license-check") // 稳定字符串；不要用堆栈、lambda 或临时对象自动生成
    before {
        // ...
    }
}

// 单阶段快捷入口也可把 key 放在第一个参数：
method.createBeforeHook("license-check") { /* ... */ }
```

同一 `Executable` 内不能重复使用相同 key；不同目标方法可以复用同一个 key。session 会在同步安装时
立即拒绝空 key、重复 key 和安装异常。热重载阶段 `onTargetReady` 的异常不会再被吞掉：旧 hook 不会被
批量提前卸载，framework 能收到失败；成功时 `HotReloadResult` 给出新建、原子替换和最终清理数量。

从未设置 id 的旧版本首次迁移时，session 会拒绝热重载并提示重启目标进程一次；否则新旧 hook 会在
迁移窗口内同时生效。完成这一次重启后，后续更新即可按稳定 key 原子替换。

Java 对应使用带 key 的入口，例如 `Hooks.createHook(method, "license-check", callback)`、
`Hooks.findAndHookMethodWithKey(...)`、`Hooks.findAndHookConstructorWithKey(...)`。

### 外部回调与跨代宿主状态

hook 之外的 listener、receiver、binder callback、线程或资源 observer 不是 `HookHandle`，必须由模块自己
取消注册。把清理放进 `scope`，旧代码会在允许重载前执行它；scope 状态会在新代码的
`onTargetReady` 前恢复：

```kotlin
hotReload.scope.onReloading {
    hostObserver.unregister()
}
hotReload.scope.putState("host-view", targetView)

// 新 entry 的 hotReload.onTargetReady { ... } 中：
val oldView = hotReload.scope.state("host-view", View::class.java)
```

`scope` 与 `prepare(..., extra)` 只接受 system、system_server 或目标 app classloader 创建的对象。
模块自身的 data class、lambda、匿名对象，或包含这些对象的容器不能跨代保存；工具库会尽早报错，
framework 仍会做最终校验。

### 不适合承诺“无缝”的情况

会话能无缝替换的是**已经存在、同步声明且有稳定 key 的代码 hook**，不是任意运行时副作用：

- 首次调用、异步任务或回调内部才创建的延迟 hook，无法在 `onHotReloaded` 时知道它何时会出现。应改为
  在 `onTargetReady` 同步安装，或自行定义明确的延迟安装/清理边界。
- `Resources`、`AssetManager`、`ResourcesLoader`、已 inflate 的 View、静态缓存和 SystemUI 资源缓存
  可能仍指向旧 APK 或已计算结果。热重载不会自动重跑资源覆盖、重新 inflate UI 或清空宿主缓存；这类
  改动需要模块自行撤销并重新应用，若宿主没有可逆 API，就应回退到重启目标进程。
- 已启动的线程和向 framework/系统服务注册的外部回调必须在 `scope.onReloading` 中停止或注销，否则旧
  module classloader 仍可能被引用。

### 兼容旧入口

`EzXposed.handleHotReloading` / `handleHotReloaded` 仍保留，用于已有模块或完全自定义的迁移流程。
旧 `handleHotReloaded` 的默认行为是先 unhook 全部旧 handle、再触发新一代回调，存在短暂空窗，且为了
保护目标 app 会记录并继续处理 `onTargetReady` 异常。新模块应优先使用 `HotReloadSession`。

需要手动处理旧 handle 时，辅助函数仍可使用：

- `oldHandles.groupById()` → `Map<String?, List<HookHandle>>`
- `oldHandles.replaceAll(hooker)` → `List<HookHandle>`，按原顺序返回新 handle
- `oldHandles.unhookAll()`：全部 unhook
