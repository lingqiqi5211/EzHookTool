@file:JvmName("HookHelper")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Kotlin hook 扩展入口。
 *
 * Java 调用方请改用 `xposed.java.Hooks`。
 */

/**
 * before / after 阶段使用的 Kotlin 回调。
 *
 * 回调接收者是当前 hook 参数，可直接读取或修改参数、结果和异常。
 */
typealias HookBlock = HookParam.() -> Unit

/**
 * replace 阶段使用的 Kotlin 回调。
 *
 * 回调返回值会作为原方法或构造器调用结果。
 */
typealias ReplaceHookBlock = HookParam.() -> Any?

/**
 * intercept 阶段使用的 libxposed chain 回调。
 *
 * 回调中可自行决定是否调用 `proceed()`。
 */
typealias ChainInterceptor = XposedInterface.Chain.() -> Any?

/**
 * 创建 before 阶段声明块。
 *
 * 适合把通用 before 逻辑拆出来复用。
 */
fun beforeHook(block: HookBlock): HookFactory.() -> Unit = {
    before { it.block() }
}

/**
 * 创建 after 阶段声明块。
 *
 * 适合把通用 after 逻辑拆出来复用。
 */
fun afterHook(block: HookBlock): HookFactory.() -> Unit = {
    after { it.block() }
}

/**
 * 创建 replace 阶段声明块。
 *
 * @return 返回值会作为原方法调用结果
 */
fun replaceHook(block: ReplaceHookBlock): HookFactory.() -> Unit = {
    replace { it.block() }
}

/**
 * 为 [Method] 创建 hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 用于声明 before、after、replace 等行为的 DSL 块
 */
fun Method.createHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): XposedInterface.HookHandle = HookFactory(this).apply {
    priority(priority)
    exceptionMode(exceptionMode)
    block()
}.create()

/**
 * 为 [Method] 创建 before hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 原方法执行前调用
 */
fun Method.createBeforeHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { before(callback) }

/**
 * 为 [Method] 创建 after hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 原方法执行后调用
 */
fun Method.createAfterHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { after(callback) }

/**
 * 为 [Method] 创建 replace hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 返回值会作为原方法调用结果
 */
fun Method.createReplaceHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Any?,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { replace(callback) }

/**
 * 为 [Method] 创建 intercept hook。
 *
 * 适合需要自行决定是否 `proceed()` 的场景。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback around 回调，可自行决定是否继续原始调用
 */
fun Method.createInterceptHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = HookFactory(this).apply {
    priority(priority)
    exceptionMode(exceptionMode)
    intercept(callback)
}.create()

/**
 * 让 [Method] 直接返回固定值。
 *
 * @param value 要直接返回给调用方的值
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 */
fun Method.createReturnConstantHook(
    value: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { returnConstant(value) }

/**
 * 为 [Constructor] 创建 hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 用于声明 before、after、replace 等行为的 DSL 块
 */
fun Constructor<*>.createHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): XposedInterface.HookHandle = HookFactory(this).apply {
    priority(priority)
    exceptionMode(exceptionMode)
    block()
}.create()

/**
 * 为 [Constructor] 创建 before hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 构造器执行前调用
 */
fun Constructor<*>.createBeforeHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { before(callback) }

/**
 * 为 [Constructor] 创建 after hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 构造器执行后调用
 */
fun Constructor<*>.createAfterHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { after(callback) }

/**
 * 为 [Constructor] 创建 replace hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 返回值会作为原构造器调用结果
 */
fun Constructor<*>.createReplaceHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Any?,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { replace(callback) }

/**
 * 为 [Constructor] 创建 intercept hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback around 回调，可自行决定是否继续原始调用
 */
fun Constructor<*>.createInterceptHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = HookFactory(this).apply {
    priority(priority)
    exceptionMode(exceptionMode)
    intercept(callback)
}.create()

/**
 * 为指定类中同名的全部方法批量创建 hook。
 *
 * 仅匹配当前类声明的方法，不包含父类继承而来的方法。
 *
 * @param methodName 目标方法名
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 每个方法都会使用同一份 hook 声明
 */
fun Class<*>.hookAllMethods(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> =
    findAllMethods(this, findSuper = false) { name(methodName) }.createHooks(priority, exceptionMode, block)

/**
 * 为指定类中同名的全部方法批量创建 hook。
 */
fun Class<*>.hookMethod(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = hookAllMethods(methodName, priority, exceptionMode, block)

/**
 * 为指定类中同名的全部方法批量创建 before hook。
 */
fun Class<*>.beforeHookMethod(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookMethod(methodName, priority, exceptionMode) {
    before { it.callback() }
}

/**
 * 为指定类中同名的全部方法批量创建 after hook。
 */
fun Class<*>.afterHookMethod(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookMethod(methodName, priority, exceptionMode) {
    after { it.callback() }
}

/**
 * 为指定类中同名的全部方法批量创建 replace hook。
 */
fun Class<*>.replaceHookMethod(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ReplaceHookBlock,
): List<XposedInterface.HookHandle> = hookMethod(methodName, priority, exceptionMode) {
    replace { it.callback() }
}

/**
 * 为指定类中同名的全部方法批量创建 intercept hook。
 */
fun Class<*>.interceptHookMethod(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ChainInterceptor,
): List<XposedInterface.HookHandle> = hookMethod(methodName, priority, exceptionMode) {
    intercept { it.callback() }
}

/**
 * 让指定类中同名的全部方法直接返回固定值。
 */
fun Class<*>.returnConstantHookMethod(
    methodName: String,
    value: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
): List<XposedInterface.HookHandle> = hookMethod(methodName, priority, exceptionMode) {
    returnConstant(value)
}

/**
 * 按类名为同名的全部方法批量创建 hook。
 *
 * 默认使用当前 hook 运行时的 `ClassLoader`。
 *
 * @param methodName 目标方法名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 每个方法都会使用同一份 hook 声明
 */
fun String.hookAllMethods(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).hookAllMethods(methodName, priority, exceptionMode, block)

/**
 * 按类名为同名的全部方法批量创建 hook。
 */
fun String.hookMethod(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).hookMethod(methodName, priority, exceptionMode, block)

/**
 * 按类名为同名的全部方法批量创建 before hook。
 */
fun String.beforeHookMethod(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).beforeHookMethod(methodName, priority, exceptionMode, callback)

/**
 * 按类名为同名的全部方法批量创建 after hook。
 */
fun String.afterHookMethod(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).afterHookMethod(methodName, priority, exceptionMode, callback)

/**
 * 按类名为同名的全部方法批量创建 replace hook。
 */
fun String.replaceHookMethod(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ReplaceHookBlock,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).replaceHookMethod(methodName, priority, exceptionMode, callback)

/**
 * 按类名为同名的全部方法批量创建 intercept hook。
 */
fun String.interceptHookMethod(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ChainInterceptor,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).interceptHookMethod(methodName, priority, exceptionMode, callback)

/**
 * 让指定类名中同名的全部方法直接返回固定值。
 */
fun String.returnConstantHookMethod(
    methodName: String,
    value: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).returnConstantHookMethod(methodName, value, priority, exceptionMode)

/**
 * 为指定类的全部构造器批量创建 hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 每个构造器都会使用同一份 hook 声明
 */
fun Class<*>.hookAllConstructors(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = findAllConstructors(this).createHooks(priority, exceptionMode, block)

/**
 * 为指定类的全部构造器批量创建 hook。
 */
fun Class<*>.hookConstructor(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = hookAllConstructors(priority, exceptionMode, block)

/**
 * 为指定类的全部构造器批量创建 before hook。
 */
fun Class<*>.beforeHookConstructor(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookConstructor(priority, exceptionMode) {
    before { it.callback() }
}

/**
 * 为指定类的全部构造器批量创建 after hook。
 */
fun Class<*>.afterHookConstructor(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookConstructor(priority, exceptionMode) {
    after { it.callback() }
}

/**
 * 为指定类的全部构造器批量创建 replace hook。
 */
fun Class<*>.replaceHookConstructor(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ReplaceHookBlock,
): List<XposedInterface.HookHandle> = hookConstructor(priority, exceptionMode) {
    replace { it.callback() }
}

/**
 * 为指定类的全部构造器批量创建 intercept hook。
 */
fun Class<*>.interceptHookConstructor(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ChainInterceptor,
): List<XposedInterface.HookHandle> = hookConstructor(priority, exceptionMode) {
    intercept { it.callback() }
}

/**
 * 按类名为全部构造器批量创建 hook。
 *
 * 默认使用当前 hook 运行时的 `ClassLoader`。
 *
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 每个构造器都会使用同一份 hook 声明
 */
fun String.hookAllConstructors(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = loadClass(this, classLoader).hookAllConstructors(priority, exceptionMode, block)

/**
 * 按类名为全部构造器批量创建 hook。
 */
fun String.hookConstructor(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = loadClass(this, classLoader).hookConstructor(priority, exceptionMode, block)

/**
 * 按类名为全部构造器批量创建 before hook。
 */
fun String.beforeHookConstructor(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).beforeHookConstructor(priority, exceptionMode, callback)

/**
 * 按类名为全部构造器批量创建 after hook。
 */
fun String.afterHookConstructor(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).afterHookConstructor(priority, exceptionMode, callback)

/**
 * 按类名为全部构造器批量创建 replace hook。
 */
fun String.replaceHookConstructor(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ReplaceHookBlock,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).replaceHookConstructor(priority, exceptionMode, callback)

/**
 * 按类名为全部构造器批量创建 intercept hook。
 */
fun String.interceptHookConstructor(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: ChainInterceptor,
): List<XposedInterface.HookHandle> =
    loadClass(this, classLoader).interceptHookConstructor(priority, exceptionMode, callback)

/**
 * 为方法列表批量创建 hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 每个方法都会使用同一份 hook 声明
 */
@JvmName("createMethodHooksByIterable")
fun Iterable<Method>.createHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = map { it.createHook(priority, exceptionMode, block) }

/**
 * 为方法列表批量创建 before hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 每个方法执行前调用
 */
@JvmName("createMethodBeforeHooksByIterable")
fun Iterable<Method>.createBeforeHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = map { it.createBeforeHook(priority, exceptionMode, callback) }

/**
 * 为方法列表批量创建 after hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 每个方法执行后调用
 */
@JvmName("createMethodAfterHooksByIterable")
fun Iterable<Method>.createAfterHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = map { it.createAfterHook(priority, exceptionMode, callback) }

/**
 * 为方法列表批量创建 replace hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 每个方法的返回值都会由该回调决定
 */
@JvmName("createMethodReplaceHooksByIterable")
fun Iterable<Method>.createReplaceHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Any?,
): List<XposedInterface.HookHandle> = map { it.createReplaceHook(priority, exceptionMode, callback) }

/**
 * 为方法列表批量创建 intercept hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback around 回调，可自行决定是否继续原始调用
 */
@JvmName("createMethodInterceptHooksByIterable")
fun Iterable<Method>.createInterceptHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): List<XposedInterface.HookHandle> = map { it.createInterceptHook(priority, exceptionMode, callback) }

/**
 * 为构造器列表批量创建 hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 每个构造器都会使用同一份 hook 声明
 */
@JvmName("createConstructorHooksByIterable")
fun Iterable<Constructor<*>>.createHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = map { it.createHook(priority, exceptionMode, block) }

/**
 * 为构造器列表批量创建 before hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 每个构造器执行前调用
 */
@JvmName("createConstructorBeforeHooksByIterable")
fun Iterable<Constructor<*>>.createBeforeHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = map { it.createBeforeHook(priority, exceptionMode, callback) }

/**
 * 为构造器列表批量创建 after hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 每个构造器执行后调用
 */
@JvmName("createConstructorAfterHooksByIterable")
fun Iterable<Constructor<*>>.createAfterHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = map { it.createAfterHook(priority, exceptionMode, callback) }

/**
 * 为构造器列表批量创建 replace hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback 每个构造器的返回值都会由该回调决定
 */
@JvmName("createConstructorReplaceHooksByIterable")
fun Iterable<Constructor<*>>.createReplaceHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Any?,
): List<XposedInterface.HookHandle> = map { it.createReplaceHook(priority, exceptionMode, callback) }

/**
 * 为构造器列表批量创建 intercept hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback around 回调，可自行决定是否继续原始调用
 */
@JvmName("createConstructorInterceptHooksByIterable")
fun Iterable<Constructor<*>>.createInterceptHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): List<XposedInterface.HookHandle> = map { it.createInterceptHook(priority, exceptionMode, callback) }
