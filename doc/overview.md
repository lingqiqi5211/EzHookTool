# EzHookTool API Guide

EzHookTool 的 API 分成三类：

- `core`：反射、类查找、方法查找、字段读写、实例创建。
- `core.java`：给 Java 调用的入口，例如 `Classes`、`Methods`、`Fields`、`Constructors`。
- `xposed.dsl` / `xposed.java`：给 Kotlin / Java 使用的 hook 入口。

本项目不做 dex 扫描。类查找只处理已知类名或候选类名；成员查找只在给定 `Class` 的成员和父类成员中查找。

## Kotlin

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
    includeSuper()
}
```

不写条件时会返回当前查找范围内的全部结果：

```kotlin
val methods = clazz.findAllMethods()
val declaredFields = clazz.findAllFields {
    currentClassOnly()
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
    currentClassOnly()
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
    before {
        val text = argAs<String>(0)
        args[0] = text.trim()
    }

    after {
        result = "done"
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

Class<?> target = Classes.findClass("com.example.Target");
```

多个候选类名按顺序兜底：

```java
Class<?> target = Classes.findFirstClass(
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
        .includeSuper()
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
        .currentClassOnly()
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

- `currentClassOnly()`：只查当前类。
- `includeSuper()`：查当前类和全部父类。

不写这两个开关时，就是默认智能查找。

也可以在函数参数里直接指定：

```kotlin
clazz.findMethod(findSuper = false) {
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
- `filter { ... }`：自定义条件。

字段查询常用条件：

- `name("mContext")` / `nameContains("context")`
- `type(Context::class.java)` / `typeExtendsFrom(Context::class.java)`
- `isStatic()` / `notStatic()`
- `isPublic()` / `isPrivate()` / `isProtected()`
- `isFinal()` / `isVolatile()` / `isTransient()`
- `isEnumConstant()` / `isSynthetic()`
- `filter { ... }`：自定义条件。

构造器查询常用条件：

- `paramCount(2)` / `paramCountIn(1..3)` / `noParams()` / `hasParams()`
- `params(String::class.java)`：完整参数类型，数量和顺序必须一致。
- `paramsAssignableFrom(String::class.java)`：目标构造器参数能接收这些类型。
- `isPublic()` / `isPrivate()` / `isProtected()`
- `isVarArgs()` / `isSynthetic()`
- `filter { ... }`：自定义条件。

Java 入口使用同义的 `filterBy...`、`filterPublic()`、`filterStatic()` 等方法。

## 查询缓存

缓存保存的是最终查找结果，不保存查找过程。

会缓存的结果：

- `findClass` / `findClassOrNull`：按 `ClassLoader + 类名` 缓存成功加载的类。
- `findFirstClass` / `findFirstClassOrNull`：按 `ClassLoader + 候选类名列表` 缓存第一个成功结果。
- `findMethod` / `findField` / `findConstructor`：按结构化查询条件缓存第一个匹配结果。
- best-match 查找：按名称和参数类型缓存匹配结果。

不会缓存的内容：

- 找不到的类或成员。
- `findAllMethods` / `findAllFields` / `findAllConstructors` 的列表本身。
- 自定义 `filter { ... }` 的结果。

`findAll` 的行为：

1. 带查询条件的 `findAll` 会先走一次对应的单个查找，让单个查找缓存先命中或预热。
2. 随后再查出完整列表。
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

## libxposed 101 intercept

`intercept` 只用于需要直接操作 `XposedInterface.Chain` 的场景。

```java
Hooks.intercept(method, chain -> {
    Object[] args = chain.getArgs().toArray();
    return chain.proceed(args);
});
```
