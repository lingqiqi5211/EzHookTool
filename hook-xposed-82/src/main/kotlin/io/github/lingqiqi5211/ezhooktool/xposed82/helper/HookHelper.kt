@file:JvmName("HookHelper")

package io.github.lingqiqi5211.ezhooktool.xposed82.helper

import de.robv.android.xposed.XC_MethodHook
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.xposed82.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed82.dsl.HookFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.function.Consumer

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
    findAllMethods(this, findSuper = false) { name == methodName }.createHooks(block)

fun Class<*>.hookAllMethodsBefore(methodName: String, callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    findAllMethods(this, findSuper = false) { name == methodName }.createBeforeHooks(callback)

fun Class<*>.hookAllMethodsAfter(methodName: String, callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    findAllMethods(this, findSuper = false) { name == methodName }.createAfterHooks(callback)

/**
 * Hook 当前类的全部构造器。
 *
 * @param block 对每个构造器应用的 hook DSL
 */
fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit): List<XC_MethodHook.Unhook> =
    findAllConstructors(this) { true }.createHooks(block)

fun Class<*>.hookAllConstructorsBefore(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    findAllConstructors(this) { true }.createBeforeHooks(callback)

fun Class<*>.hookAllConstructorsAfter(callback: (HookParam) -> Unit): List<XC_MethodHook.Unhook> =
    findAllConstructors(this) { true }.createAfterHooks(callback)

/**
 * Java 入口：为 [method] 创建 hook DSL。
 *
 * @param method 要 hook 的方法
 * @param block 用于配置 hook 行为的 Java `Consumer`
 */
@JvmName("createMethodHook")
fun createHook(method: Method, block: Consumer<HookFactory>): XC_MethodHook.Unhook =
    HookFactory(method).also { block.accept(it) }.create()

@JvmName("createMethodBeforeHook")
fun createBeforeHook(method: Method, callback: Consumer<HookParam>): XC_MethodHook.Unhook =
    method.createBeforeHook { callback.accept(it) }

@JvmName("createMethodAfterHook")
fun createAfterHook(method: Method, callback: Consumer<HookParam>): XC_MethodHook.Unhook =
    method.createAfterHook { callback.accept(it) }

/**
 * Java 入口：为 [constructor] 创建 hook DSL。
 *
 * @param constructor 要 hook 的构造器
 * @param block 用于配置 hook 行为的 Java `Consumer`
 */
@JvmName("createConstructorHook")
fun createHook(constructor: Constructor<*>, block: Consumer<HookFactory>): XC_MethodHook.Unhook =
    HookFactory(constructor).also { block.accept(it) }.create()

@JvmName("hookMethodBefore")
fun hookBefore(method: Method, callback: Consumer<HookParam>): XC_MethodHook.Unhook =
    method.hookBefore { callback.accept(it) }

@JvmName("hookMethodAfter")
fun hookAfter(method: Method, callback: Consumer<HookParam>): XC_MethodHook.Unhook =
    method.hookAfter { callback.accept(it) }

/**
 * Java 入口：Hook [clazz] 中所有名为 [methodName] 的方法。
 *
 * @param clazz 目标类
 * @param methodName 要批量匹配的方法名
 * @param block 应用于每个匹配方法的 hook 配置
 */
@JvmName("hookMethodAll")
fun hookAllMethods(clazz: Class<*>, methodName: String, block: Consumer<HookFactory>): List<XC_MethodHook.Unhook> =
    clazz.hookAllMethods(methodName) { block.accept(this) }

internal fun Any.toMemberOrThrow(): Member = this as? Member ?: error("Expected Member, got $javaClass")
