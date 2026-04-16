@file:JvmName("HookHelper")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.argTypes
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findConstructorBestMatch
import io.github.lingqiqi5211.ezhooktool.core.findAllMethodsBy
import io.github.lingqiqi5211.ezhooktool.core.findMethodBestMatch
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.core.methodOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Kotlin 优先的 hook 扩展入口。
 *
 * Java 调用方请改用 `xposed.java.EzXposedHelpers`。
 */

typealias HookBlock = HookParam.() -> Unit
typealias ReplaceHookBlock = HookParam.() -> Any?
typealias ChainInterceptor = XposedInterface.Chain.() -> Any?
typealias HookExceptionMode = XposedInterface.ExceptionMode
typealias HookDsl = HookFactory.() -> Unit

fun beforeHook(block: HookBlock): HookFactory.() -> Unit = {
    before { it.block() }
}

fun afterHook(block: HookBlock): HookFactory.() -> Unit = {
    after { it.block() }
}

fun replaceHook(block: ReplaceHookBlock): HookFactory.() -> Unit = {
    replace { it.block() }
}

private fun Class<*>.resolveHookParameterType(parameterType: Any): Class<*> = when (parameterType) {
    is Class<*> -> parameterType
    is String -> loadClass(parameterType, classLoader ?: HookClassLoader.currentOrDefault())
    else -> parameterType.javaClass
}

private fun Class<*>.resolveHookMethod(methodName: String, parameterTypes: Array<out Any?>): Method {
    require(parameterTypes.none { it == null }) { "Null parameter type is not supported in hook helpers" }
    if (parameterTypes.any { it !is Class<*> && it !is String }) {
        return findMethodBestMatch(this, methodName, *parameterTypes)
    }
    val resolvedTypes = Array(parameterTypes.size) { index -> resolveHookParameterType(parameterTypes[index]!!) }
    return methodOrNull(methodName, argTypes(*resolvedTypes))
        ?: findMethodBestMatch(this, methodName, *resolvedTypes)
}

private fun Class<*>.resolveHookConstructor(parameterTypes: Array<out Any?>): Constructor<*> {
    require(parameterTypes.none { it == null }) { "Null parameter type is not supported in hook helpers" }
    if (parameterTypes.any { it !is Class<*> && it !is String }) {
        return findConstructorBestMatch(this, *parameterTypes)
    }
    val resolvedTypes = Array(parameterTypes.size) { index -> resolveHookParameterType(parameterTypes[index]!!) }
    return runCatching { getDeclaredConstructor(*resolvedTypes).also { it.isAccessible = true } }.getOrNull()
        ?: findConstructorBestMatch(this, *resolvedTypes)
}

/**
 * 为 [Method] 安装 intercept hook。
 *
 * 适合需要自行决定是否 `proceed()` 的场景。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback around 回调，可自行决定是否继续原始调用
 */
fun Method.hook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = HookFactory(this).apply {
    priority(priority)
    exceptionMode(exceptionMode)
    intercept(callback)
}.create()

/**
 * 为 [Method] 创建 hook DSL。
 *
 * 需要组合多个阶段时优先用这个入口。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 用于声明 hook 行为的 DSL 块
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

fun Method.createBeforeHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { before(callback) }

fun Method.createAfterHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { after(callback) }

fun Method.hookBefore(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createBeforeHook(priority, exceptionMode, callback)

fun Method.hookAfter(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createAfterHook(priority, exceptionMode, callback)

fun Method.hookReplace(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Any?,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { replace(callback) }

/**
 * 让方法直接返回固定值。
 *
 * @param value 要直接返回给调用方的值
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 */
fun Method.hookReturnConstant(
    value: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { returnConstant(value) }

fun Method.hookIntercept(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = hook(priority, exceptionMode, callback)

/**
 * 为 [Constructor] 安装 intercept hook。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback around 回调，可自行决定是否继续原始调用
 */
fun Constructor<*>.hook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = HookFactory(this).apply {
    priority(priority)
    exceptionMode(exceptionMode)
    intercept(callback)
}.create()

/**
 * 为 [Constructor] 创建 hook DSL。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 用于声明 hook 行为的 DSL 块
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

fun Constructor<*>.createBeforeHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { before(callback) }

fun Constructor<*>.createAfterHook(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { after(callback) }

fun Constructor<*>.hookBefore(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createBeforeHook(priority, exceptionMode, callback)

fun Constructor<*>.hookAfter(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): XposedInterface.HookHandle = createAfterHook(priority, exceptionMode, callback)

fun Constructor<*>.hookReplace(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Any?,
): XposedInterface.HookHandle = createHook(priority, exceptionMode) { replace(callback) }

fun Constructor<*>.hookIntercept(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = hook(priority, exceptionMode, callback)

fun Iterable<Method>.hooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (XposedInterface.Chain) -> Any?,
): List<XposedInterface.HookHandle> = map { it.hook(priority, exceptionMode, callback) }

fun Iterable<Method>.createHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = map { it.createHook(priority, exceptionMode, block) }

fun Iterable<Method>.createBeforeHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = map { it.createBeforeHook(priority, exceptionMode, callback) }

fun Iterable<Method>.createAfterHooks(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = map { it.createAfterHook(priority, exceptionMode, callback) }

fun Iterable<Method>.hookBefore(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = createBeforeHooks(priority, exceptionMode, callback)

fun Iterable<Method>.hookAfter(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: (HookParam) -> Unit,
): List<XposedInterface.HookHandle> = createAfterHooks(priority, exceptionMode, callback)

/**
 * Hook 当前类中所有同名方法。
 *
 * 只搜索当前类，不搜索父类。
 *
 * @param methodName 要批量匹配的方法名
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 对每个匹配方法应用的 hook DSL
 */
fun Class<*>.hookAllMethods(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = findAllMethodsBy(this, findSuper = false) { name == methodName }
    .createHooks(priority, exceptionMode, block)

/**
 * Hook 当前类的全部构造器。
 *
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 对每个构造器应用的 hook DSL
 */
fun Class<*>.hookAllConstructors(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: HookFactory.() -> Unit,
): List<XposedInterface.HookHandle> = findAllConstructors(this)
    .map { it.createHook(priority, exceptionMode, block) }

fun Class<*>.beforeMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = resolveHookMethod(methodName, parameterTypes)
    .hookBefore(priority, exceptionMode) { it.callback() }

fun Class<*>.afterMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = resolveHookMethod(methodName, parameterTypes)
    .hookAfter(priority, exceptionMode) { it.callback() }

fun Class<*>.hookMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: HookDsl,
): XposedInterface.HookHandle = resolveHookMethod(methodName, parameterTypes)
    .createHook(priority, exceptionMode, block)

fun Class<*>.replaceMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: ReplaceHookBlock,
): XposedInterface.HookHandle = resolveHookMethod(methodName, parameterTypes)
    .hookReplace(priority, exceptionMode) { it.callback() }

fun Class<*>.interceptMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: ChainInterceptor,
): XposedInterface.HookHandle = resolveHookMethod(methodName, parameterTypes)
    .hook(priority, exceptionMode) { chain -> chain.block() }

fun Class<*>.beforeConstructor(
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = resolveHookConstructor(parameterTypes)
    .hookBefore(priority, exceptionMode) { it.callback() }

fun Class<*>.afterConstructor(
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = resolveHookConstructor(parameterTypes)
    .hookAfter(priority, exceptionMode) { it.callback() }

fun Class<*>.hookConstructor(
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: HookDsl,
): XposedInterface.HookHandle = resolveHookConstructor(parameterTypes)
    .createHook(priority, exceptionMode, block)

fun Class<*>.interceptConstructor(
    vararg parameterTypes: Any?,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: ChainInterceptor,
): XposedInterface.HookHandle = resolveHookConstructor(parameterTypes)
    .hook(priority, exceptionMode) { chain -> chain.block() }

fun Class<*>.beforeAllMethods(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookAllMethods(methodName, priority, exceptionMode, beforeHook(callback))

fun Class<*>.afterAllMethods(
    methodName: String,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookAllMethods(methodName, priority, exceptionMode, afterHook(callback))

fun Class<*>.beforeAllConstructors(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookAllConstructors(priority, exceptionMode, beforeHook(callback))

fun Class<*>.afterAllConstructors(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): List<XposedInterface.HookHandle> = hookAllConstructors(priority, exceptionMode, afterHook(callback))

fun String.beforeMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .beforeMethod(methodName, *parameterTypes, priority = priority, exceptionMode = exceptionMode, callback = callback)

fun String.afterMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .afterMethod(methodName, *parameterTypes, priority = priority, exceptionMode = exceptionMode, callback = callback)

fun String.hookMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: HookDsl,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .hookMethod(methodName, *parameterTypes, priority = priority, exceptionMode = exceptionMode, block = block)

fun String.replaceMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: ReplaceHookBlock,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .replaceMethod(methodName, *parameterTypes, priority = priority, exceptionMode = exceptionMode, callback = callback)

fun String.interceptMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: ChainInterceptor,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .interceptMethod(methodName, *parameterTypes, priority = priority, exceptionMode = exceptionMode, block = block)

fun String.beforeConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .beforeConstructor(*parameterTypes, priority = priority, exceptionMode = exceptionMode, callback = callback)

fun String.afterConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    callback: HookBlock,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .afterConstructor(*parameterTypes, priority = priority, exceptionMode = exceptionMode, callback = callback)

fun String.hookConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: HookDsl,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .hookConstructor(*parameterTypes, priority = priority, exceptionMode = exceptionMode, block = block)

fun String.interceptConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: HookExceptionMode = HookExceptionMode.DEFAULT,
    block: ChainInterceptor,
): XposedInterface.HookHandle = loadClass(this, classLoader)
    .interceptConstructor(*parameterTypes, priority = priority, exceptionMode = exceptionMode, block = block)
