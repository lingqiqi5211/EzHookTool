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

```kotlin
val handle = method.createHook {
    id("license-check")
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

是否允许热重载由 framework 在调用 `onHotReloading` 时决定，模块侧不再持有显式的查询入口（上游
102 SNAPSHOT 已移除 `PROP_RT_HOT_RELOAD`）。

EzHookTool 把热重载里跨代 snapshot、`EzReflect.classLoader` 重建、旧 handle unhook、
重新分发到使用者的「目标进程准备好后跑什么」逻辑都收在 `EzXposed` 里。模块作者只需要：

1. 用 `EzXposed.onTargetReady { ... }` 注册「目标进程就绪后跑什么」。
2. 覆写 `onHotReloading` 和 `onHotReloaded`，各一行调用。

```kotlin
class MainHook : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // 初次加载和热重载后，EzXposed 都会自动触发一次这里。
        EzXposed.onTargetReady {
            installHooks()
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TargetApp) return
        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) =
        EzXposed.handleHotReloaded(this, param)
}
```

`EzXposed.onTargetReady { ... }` 在初次 `initOnPackageReady` 或 `initOnSystemServerStarting`
末尾触发；热重载时 `handleHotReloaded` 还原 snapshot 后再触发一次。允许多次注册，按注册顺序执行；
如果在 snapshot 已存在的状态下注册，会立即执行一次，避免错过当前进程。

`handleHotReloaded` 内部做的事：

1. 用 [initOnModuleLoaded] 等价逻辑重置 `base`、`modulePath`、`moduleRes`、`processName`、`isSystemServer`。
2. 把 `param.oldHookHandles` 全部 unhook。
3. 还原 `handleHotReloading` 透传的 snapshot，重建 `EzReflect.classLoader` 与 `packageName`。
4. 重新触发 [onTargetReady] 已注册的回调。

### 自定义 saved state（进阶）

`handleHotReloading` 把工具库自己的 snapshot 塞到 `setSavedInstanceState`。如果还想夹带自己的
跨代状态，跳过这个 helper 直接覆写 `onHotReloading`，并在 `setSavedInstanceState` 里自行管理。

跨代 `setSavedInstanceState` 接受 system / system_server / app classloader 创建的对象
（包含 `String`、`ClassLoader`、`ApplicationInfo`、`Bundle` 等），但**拒绝**旧 module
classloader 创建的对象——比如模块自己定义的 data class、lambda、持有模块类引用的容器。
如果有这类自定义状态，用 `Bundle` 序列化成基础类型来绕开 module classloader。

### 按 id 替换 hook（进阶）

`EzXposed.handleHotReloaded` 默认把所有旧 handle unhook，新 code 在 `onTargetReady` 里重挂。
如果想保留部分 hook 不重挂、改用 [HookHandle.replaceHook] 平滑迁移，跳过 helper 自行处理
`param.oldHookHandles`：

```kotlin
override fun onHotReloaded(param: HotReloadedParam) {
    // 自行处理，不调用 EzXposed.handleHotReloaded
    val byId = param.oldHookHandles.groupById()
    byId["license-check"]?.replaceAll(LicenseHooker())
    (byId[null].orEmpty()).unhookAll()
    // 还原 EzXposed 内部状态需要自己来：从 param.savedInstanceState 取 snapshot……
    // 此时建议自己读上游 spec，工具库不再帮忙。
}
```

`HookHandle` 列表辅助函数：

- `oldHandles.groupById()` → `Map<String?, List<HookHandle>>`
- `oldHandles.replaceAll(hooker)` → `List<HookHandle>`，按原顺序返回新 handle
- `oldHandles.unhookAll()`：全部 unhook
