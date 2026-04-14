@file:JvmName("HookHelper")

package io.github.lingqiqi5211.ezhooktool.xposed.helper

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.xposed.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.function.Consumer

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
): List<XposedInterface.HookHandle> = findAllMethods(this, findSuper = false) { name == methodName }
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
): List<XposedInterface.HookHandle> = findAllConstructors(this) { true }
    .map { it.createHook(priority, exceptionMode, block) }

/**
 * Java 入口：为 [method] 创建 hook DSL。
 *
 * @param method 要 hook 的方法
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param block 用于配置 hook 行为的 Java `Consumer`
 */
@JvmName("createMethodHook")
fun createHook(
    method: Method,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    block: Consumer<HookFactory>,
): XposedInterface.HookHandle = HookFactory(method).apply {
    this.priority(priority)
    this.exceptionMode(exceptionMode)
    block.accept(this)
}.create()

/**
 * Java 入口：以 libxposed 原生 [XposedInterface.Hooker] hook [method]。
 *
 * @param method 要 hook 的方法
 * @param priority hook 优先级，数值越大越先执行
 * @param exceptionMode hook 过程中异常的处理策略
 * @param callback libxposed 原生 hooker
 */
@JvmName("hookMethod")
fun hook(
    method: Method,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT,
    callback: XposedInterface.Hooker,
): XposedInterface.HookHandle = HookFactory(method).apply {
    this.priority(priority)
    this.exceptionMode(exceptionMode)
    intercept(callback)
}.create()
