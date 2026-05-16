@file:JvmName("HookHelper")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import de.robv.android.xposed.XC_MethodHook
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
 * @param block 用于声明 before、after、replace 等行为的 DSL 块
 */
fun Method.createHook(block: HookFactory.() -> Unit): XC_MethodHook.Unhook =
    HookFactory(this).apply(block).create()

/**
 * 为 [Method] 创建 before hook。
 *
 * @param callback 原方法执行前调用
 */
fun Method.createBeforeHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { before(callback) }

/**
 * 为 [Method] 创建 after hook。
 *
 * @param callback 原方法执行后调用
 */
fun Method.createAfterHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { after(callback) }

/**
 * 为 [Method] 创建 replace hook。
 *
 * @param callback 返回值会作为原方法调用结果
 */
fun Method.createReplaceHook(callback: (HookParam) -> Any?): XC_MethodHook.Unhook =
    createHook { replace(callback) }

/**
 * 让 [Method] 直接返回固定值。
 *
 * @param value 要直接返回给调用方的值
 */
fun Method.createReturnConstantHook(value: Any?): XC_MethodHook.Unhook =
    createHook { returnConstant(value) }

/**
 * 为 [Constructor] 创建 hook。
 *
 * @param block 用于声明 before、after、replace 等行为的 DSL 块
 */
fun Constructor<*>.createHook(block: HookFactory.() -> Unit): XC_MethodHook.Unhook =
    HookFactory(this).apply(block).create()

/**
 * 为 [Constructor] 创建 before hook。
 *
 * @param callback 构造器执行前调用
 */
fun Constructor<*>.createBeforeHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { before(callback) }

/**
 * 为 [Constructor] 创建 after hook。
 *
 * @param callback 构造器执行后调用
 */
fun Constructor<*>.createAfterHook(callback: (HookParam) -> Unit): XC_MethodHook.Unhook =
    createHook { after(callback) }

/**
 * 为 [Constructor] 创建 replace hook。
 *
 * @param callback 返回值会作为原构造器调用结果
 */
fun Constructor<*>.createReplaceHook(callback: (HookParam) -> Any?): XC_MethodHook.Unhook =
    createHook { replace(callback) }

/**
 * 为指定类中同名的全部方法批量创建 hook。
 *
 * 仅匹配当前类声明的方法，不包含父类继承而来的方法。
 *
 * @param methodName 目标方法名
 * @param block 每个方法都会使用同一份 hook 声明
 */
fun Class<*>.hookAllMethods(
    methodName: String,
    block: HookFactory.() -> Unit,
): List<XC_MethodHook.Unhook> =
    findAllMethods(this, findSuper = false) { name(methodName) }.createHooks(block)

/**
 * 按类名为同名的全部方法批量创建 hook。
 *
 * 默认使用当前 hook 运行时的 `ClassLoader`。
 *
 * @param methodName 目标方法名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param block 每个方法都会使用同一份 hook 声明
 */
fun String.hookAllMethods(
    methodName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    block: HookFactory.() -> Unit,
): List<XC_MethodHook.Unhook> = loadClass(this, classLoader).hookAllMethods(methodName, block)

/**
 * 为指定类的全部构造器批量创建 hook。
 *
 * @param block 每个构造器都会使用同一份 hook 声明
 */
fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    findAllConstructors(this).createHooks(block)

/**
 * 按类名为全部构造器批量创建 hook。
 *
 * 默认使用当前 hook 运行时的 `ClassLoader`。
 *
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param block 每个构造器都会使用同一份 hook 声明
 */
fun String.hookAllConstructors(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    block: HookFactory.() -> Unit,
): List<XC_MethodHook.Unhook> = loadClass(this, classLoader).hookAllConstructors(block)

/**
 * 为方法列表批量创建 hook。
 *
 * @param block 每个方法都会使用同一份 hook 声明
 */
@JvmName("createMethodHooksByIterable")
fun Iterable<Method>.createHooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createHook(block) }

/**
 * 为方法列表批量创建 before hook。
 *
 * @param callback 每个方法执行前调用
 */
@JvmName("createMethodBeforeHooksByIterable")
fun Iterable<Method>.createBeforeHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createBeforeHook(callback) }

/**
 * 为方法列表批量创建 after hook。
 *
 * @param callback 每个方法执行后调用
 */
@JvmName("createMethodAfterHooksByIterable")
fun Iterable<Method>.createAfterHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createAfterHook(callback) }

/**
 * 为方法列表批量创建 replace hook。
 *
 * @param callback 每个方法的返回值都会由该回调决定
 */
@JvmName("createMethodReplaceHooksByIterable")
fun Iterable<Method>.createReplaceHooks(callback: (HookParam) -> Any?): List<XC_MethodHook.Unhook> =
    map { it.createReplaceHook(callback) }

/**
 * 为构造器列表批量创建 hook。
 *
 * @param block 每个构造器都会使用同一份 hook 声明
 */
@JvmName("createConstructorHooksByIterable")
fun Iterable<Constructor<*>>.createHooks(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createHook(block) }

/**
 * 为构造器列表批量创建 before hook。
 *
 * @param callback 每个构造器执行前调用
 */
@JvmName("createConstructorBeforeHooksByIterable")
fun Iterable<Constructor<*>>.createBeforeHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createBeforeHook(callback) }

/**
 * 为构造器列表批量创建 after hook。
 *
 * @param callback 每个构造器执行后调用
 */
@JvmName("createConstructorAfterHooksByIterable")
fun Iterable<Constructor<*>>.createAfterHooks(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    map { it.createAfterHook(callback) }

/**
 * 为构造器列表批量创建 replace hook。
 *
 * @param callback 每个构造器的返回值都会由该回调决定
 */
@JvmName("createConstructorReplaceHooksByIterable")
fun Iterable<Constructor<*>>.createReplaceHooks(callback: (HookParam) -> Any?): List<XC_MethodHook.Unhook> =
    map { it.createReplaceHook(callback) }
