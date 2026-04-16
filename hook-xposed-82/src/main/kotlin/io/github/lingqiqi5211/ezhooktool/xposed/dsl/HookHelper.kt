@file:JvmName("HookHelper")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import de.robv.android.xposed.XC_MethodHook
import io.github.lingqiqi5211.ezhooktool.core.argTypes
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findAllMethodsBy
import io.github.lingqiqi5211.ezhooktool.core.findConstructorBestMatch
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
 * 基于 [Method] 创建 hook DSL。
 *
 * 需要组合 `before`、`after`、`replace` 等行为时，优先用这个入口。
 *
 * @param block 用于声明 hook 行为的 DSL 块
 */
fun Method.createHook(block: HookFactory.() -> Unit): XC_MethodHook.Unhook =
    HookFactory(this).apply(block).create()

fun Method.createBeforeHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { before(callback) }

fun Method.createAfterHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { after(callback) }

fun Method.hook(block: HookFactory.() -> Unit): XC_MethodHook.Unhook = createHook(block)

fun Method.hookBefore(callback: (HookParam) -> Unit): XC_MethodHook.Unhook = createBeforeHook(callback)

fun Method.hookAfter(callback: (HookParam) -> Unit): XC_MethodHook.Unhook = createAfterHook(callback)

fun Method.hookReplace(callback: (HookParam) -> Any?): XC_MethodHook.Unhook =
    createHook { replace(callback) }

/**
 * 让方法直接返回固定值。
 *
 * @param value 要直接返回给调用方的值
 */
fun Method.hookReturnConstant(value: Any?): XC_MethodHook.Unhook =
    createHook { returnConstant(value) }

/**
 * 基于 [Constructor] 创建 hook DSL。
 *
 * @param block 用于声明 hook 行为的 DSL 块
 */
fun Constructor<*>.createHook(block: HookFactory.() -> Unit): XC_MethodHook.Unhook =
    HookFactory(this).apply(block).create()

fun Constructor<*>.createBeforeHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { before(callback) }

fun Constructor<*>.createAfterHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { after(callback) }

fun Constructor<*>.hook(block: HookFactory.() -> Unit): XC_MethodHook.Unhook = createHook(block)

fun Constructor<*>.hookBefore(callback: (HookParam) -> Unit): XC_MethodHook.Unhook = createBeforeHook(callback)

fun Constructor<*>.hookAfter(callback: (HookParam) -> Unit): XC_MethodHook.Unhook = createAfterHook(callback)

fun Constructor<*>.hookReplace(callback: (HookParam) -> Any?): XC_MethodHook.Unhook =
    createHook { replace(callback) }

@JvmName("createMethodHooksByIterable")
fun Iterable<Method>.createHooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createHook(block) }

@JvmName("createMethodBeforeHooksByIterable")
fun Iterable<Method>.createBeforeHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createBeforeHook(callback) }

@JvmName("createMethodAfterHooksByIterable")
fun Iterable<Method>.createAfterHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createAfterHook(callback) }

@JvmName("hookMethodsByIterable")
fun Iterable<Method>.hooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> = createHooks(block)

@JvmName("hookMethodBeforeByIterable")
fun Iterable<Method>.hookBefore(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> = createBeforeHooks(callback)

@JvmName("hookMethodAfterByIterable")
fun Iterable<Method>.hookAfter(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> = createAfterHooks(callback)

@JvmName("createMethodHooksByArray")
fun Array<Method>.createHooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> = asIterable().createHooks(block)

@JvmName("createMethodBeforeHooksByArray")
fun Array<Method>.createBeforeHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    asIterable().createBeforeHooks(callback)

@JvmName("createMethodAfterHooksByArray")
fun Array<Method>.createAfterHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    asIterable().createAfterHooks(callback)

@JvmName("hookMethodsByArray")
fun Array<Method>.hooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> = asIterable().hooks(block)

@JvmName("createConstructorHooksByIterable")
fun Iterable<Constructor<*>>.createHooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createHook(block) }

@JvmName("createConstructorBeforeHooksByIterable")
fun Iterable<Constructor<*>>.createBeforeHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createBeforeHook(callback) }

@JvmName("createConstructorAfterHooksByIterable")
fun Iterable<Constructor<*>>.createAfterHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createAfterHook(callback) }

@JvmName("hookConstructorsByIterable")
fun Iterable<Constructor<*>>.hooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> = createHooks(block)

@JvmName("createConstructorHooksByArray")
fun Array<Constructor<*>>.createHooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    asIterable().createHooks(block)

@JvmName("createConstructorBeforeHooksByArray")
fun Array<Constructor<*>>.createBeforeHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    asIterable().createBeforeHooks(callback)

@JvmName("createConstructorAfterHooksByArray")
fun Array<Constructor<*>>.createAfterHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    asIterable().createAfterHooks(callback)

@JvmName("hookConstructorsByArray")
fun Array<Constructor<*>>.hooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> = asIterable().hooks(block)

/**
 * Hook 当前类里所有同名方法。
 *
 * 只搜索当前类；需要更细粒度条件时，先自行查找 [Method] 再调用对应 helper。
 *
 * @param methodName 要批量匹配的方法名
 * @param block 对每个匹配方法应用的 hook DSL
 */
fun Class<*>.hookAllMethods(methodName: String, block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
findAllMethodsBy(this, findSuper = false) { name == methodName }.createHooks(block)

fun Class<*>.hookAllMethodsBefore(methodName: String, callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
findAllMethodsBy(this, findSuper = false) { name == methodName }.createBeforeHooks(callback)

fun Class<*>.hookAllMethodsAfter(methodName: String, callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
findAllMethodsBy(this, findSuper = false) { name == methodName }.createAfterHooks(callback)

/**
 * Hook 当前类的全部构造器。
 *
 * @param block 对每个构造器应用的 hook DSL
 */
fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
findAllConstructors(this).createHooks(block)

fun Class<*>.hookAllConstructorsBefore(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
findAllConstructors(this).createBeforeHooks(callback)

fun Class<*>.hookAllConstructorsAfter(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
findAllConstructors(this).createAfterHooks(callback)

fun Class<*>.beforeMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    callback: HookBlock,
): XC_MethodHook.Unhook = resolveHookMethod(methodName, parameterTypes)
    .hookBefore { it.callback() }

fun Class<*>.afterMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    callback: HookBlock,
): XC_MethodHook.Unhook = resolveHookMethod(methodName, parameterTypes)
    .hookAfter { it.callback() }

fun Class<*>.hookMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    block: HookDsl,
): XC_MethodHook.Unhook = resolveHookMethod(methodName, parameterTypes)
    .createHook(block)

fun Class<*>.replaceMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    callback: ReplaceHookBlock,
): XC_MethodHook.Unhook = resolveHookMethod(methodName, parameterTypes)
    .hookReplace { it.callback() }

fun Class<*>.beforeConstructor(
    vararg parameterTypes: Any?,
    callback: HookBlock,
): XC_MethodHook.Unhook = resolveHookConstructor(parameterTypes)
    .hookBefore { it.callback() }

fun Class<*>.afterConstructor(
    vararg parameterTypes: Any?,
    callback: HookBlock,
): XC_MethodHook.Unhook = resolveHookConstructor(parameterTypes)
    .hookAfter { it.callback() }

fun Class<*>.hookConstructor(
    vararg parameterTypes: Any?,
    block: HookDsl,
): XC_MethodHook.Unhook = resolveHookConstructor(parameterTypes)
    .createHook(block)

fun Class<*>.beforeAllMethods(
    methodName: String,
    callback: HookBlock,
): List<XC_MethodHook.Unhook> = hookAllMethodsBefore(methodName) { it.callback() }

fun Class<*>.afterAllMethods(
    methodName: String,
    callback: HookBlock,
): List<XC_MethodHook.Unhook> = hookAllMethodsAfter(methodName) { it.callback() }

fun Class<*>.beforeAllConstructors(
    callback: HookBlock,
): List<XC_MethodHook.Unhook> = hookAllConstructorsBefore { it.callback() }

fun Class<*>.afterAllConstructors(
    callback: HookBlock,
): List<XC_MethodHook.Unhook> = hookAllConstructorsAfter { it.callback() }

fun String.beforeMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    callback: HookBlock,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .beforeMethod(methodName, *parameterTypes, callback = callback)

fun String.afterMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    callback: HookBlock,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .afterMethod(methodName, *parameterTypes, callback = callback)

fun String.hookMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    block: HookDsl,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .hookMethod(methodName, *parameterTypes, block = block)

fun String.replaceMethod(
    methodName: String,
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    callback: ReplaceHookBlock,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .replaceMethod(methodName, *parameterTypes, callback = callback)

fun String.beforeConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    callback: HookBlock,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .beforeConstructor(*parameterTypes, callback = callback)

fun String.afterConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    callback: HookBlock,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .afterConstructor(*parameterTypes, callback = callback)

fun String.hookConstructor(
    vararg parameterTypes: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    block: HookDsl,
): XC_MethodHook.Unhook = loadClass(this, classLoader)
    .hookConstructor(*parameterTypes, block = block)
