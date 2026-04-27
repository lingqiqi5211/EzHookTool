package io.github.lingqiqi5211.ezhooktool.xposed.java

import de.robv.android.xposed.XC_MethodHook
import io.github.lingqiqi5211.ezhooktool.core.findConstructorBestMatch
import io.github.lingqiqi5211.ezhooktool.core.findMethodBestMatch
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 供 Java 调用的 hook 入口。
 *
 * 已经拿到 `Method` / `Constructor` 时使用 `createHook`。
 * 想按名称和参数类型查找并立即 hook 时使用 `findAndHookMethod` 或 `findAndHookConstructor`。
 *
 * `findAndHookMethod` 和 `findAndHookConstructor` 的最后一个可变参数必须是
 * `IMethodHook` 或 `IReplaceHook`。
 */
object Hooks {
    /**
     * 为方法创建普通 hook。
     *
     * @param method 目标方法
     * @param callback before / after 回调
     */
    @JvmStatic
    fun createHook(method: Method, callback: IMethodHook): XC_MethodHook.Unhook =
        method.createHook {
            before { callback.before(it) }
            after { callback.after(it) }
        }

    /**
     * 为构造器创建普通 hook。
     *
     * @param constructor 目标构造器
     * @param callback before / after 回调
     */
    @JvmStatic
    fun createHook(constructor: Constructor<*>, callback: IMethodHook): XC_MethodHook.Unhook =
        constructor.createHook {
            before { callback.before(it) }
            after { callback.after(it) }
        }

    /**
     * 为方法创建替换 hook。
     *
     * @param method 目标方法
     * @param callback replace 回调，返回值会作为原方法结果
     */
    @JvmStatic
    fun createHook(method: Method, callback: IReplaceHook): XC_MethodHook.Unhook =
        method.createHook {
            replace { callback.replace(it) }
        }

    /**
     * 为构造器创建替换 hook。
     *
     * @param constructor 目标构造器
     * @param callback replace 回调，返回值会作为原构造器结果
     */
    @JvmStatic
    fun createHook(constructor: Constructor<*>, callback: IReplaceHook): XC_MethodHook.Unhook =
        constructor.createHook {
            replace { callback.replace(it) }
        }

    /**
     * 为方法列表批量创建普通 hook。
     *
     * @param methods 目标方法列表
     * @param callback before / after 回调
     */
    @JvmStatic
    fun createHooks(methods: Iterable<Method>, callback: IMethodHook): List<XC_MethodHook.Unhook> =
        methods.map { createHook(it, callback) }

    /**
     * 为方法列表批量创建替换 hook。
     *
     * @param methods 目标方法列表
     * @param callback replace 回调，返回值会作为原方法结果
     */
    @JvmStatic
    fun createHooks(methods: Iterable<Method>, callback: IReplaceHook): List<XC_MethodHook.Unhook> =
        methods.map { createHook(it, callback) }

    /**
     * 为构造器列表批量创建普通 hook。
     *
     * @param constructors 目标构造器列表
     * @param callback before / after 回调
     */
    @JvmStatic
    fun createConstructorHooks(
        constructors: Iterable<Constructor<*>>,
        callback: IMethodHook,
    ): List<XC_MethodHook.Unhook> = constructors.map { createHook(it, callback) }

    /**
     * 为构造器列表批量创建替换 hook。
     *
     * @param constructors 目标构造器列表
     * @param callback replace 回调，返回值会作为原构造器结果
     */
    @JvmStatic
    fun createConstructorHooks(
        constructors: Iterable<Constructor<*>>,
        callback: IReplaceHook,
    ): List<XC_MethodHook.Unhook> = constructors.map { createHook(it, callback) }

    /**
     * 按方法名和参数类型查找方法并立即创建 hook。
     *
     * @param clazz 目标类
     * @param methodName 方法名
     * @param parameterTypesAndCallback 参数类型列表，最后一项必须是 `IMethodHook` 或 `IReplaceHook`
     */
    @JvmStatic
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook {
        val callback = requireCallback(parameterTypesAndCallback)
        val method = resolveMethod(clazz, methodName, parameterTypesAndCallback)
        return installCallback(method, callback)
    }

    /**
     * 按类名、方法名和参数类型查找方法并立即创建 hook。
     *
     * 默认使用当前 hook 运行时的 `ClassLoader`。
     *
     * @param className 目标类名
     * @param methodName 方法名
     * @param parameterTypesAndCallback 参数类型列表，最后一项必须是 `IMethodHook` 或 `IReplaceHook`
     */
    @JvmStatic
    fun findAndHookMethod(
        className: String,
        methodName: String,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook =
        findAndHookMethod(loadClass(className, HookClassLoader.currentOrDefault()), methodName, *parameterTypesAndCallback)

    /**
     * 按类名、指定 `ClassLoader`、方法名和参数类型查找方法并立即创建 hook。
     *
     * @param className 目标类名
     * @param classLoader 用于加载目标类的 `ClassLoader`
     * @param methodName 方法名
     * @param parameterTypesAndCallback 参数类型列表，最后一项必须是 `IMethodHook` 或 `IReplaceHook`
     */
    @JvmStatic
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook =
        findAndHookMethod(loadClass(className, classLoader), methodName, *parameterTypesAndCallback)

    /**
     * 按参数类型查找构造器并立即创建 hook。
     *
     * @param clazz 目标类
     * @param parameterTypesAndCallback 参数类型列表，最后一项必须是 `IMethodHook` 或 `IReplaceHook`
     */
    @JvmStatic
    fun findAndHookConstructor(
        clazz: Class<*>,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook {
        val callback = requireCallback(parameterTypesAndCallback)
        val constructor = resolveConstructor(clazz, parameterTypesAndCallback)
        return installCallback(constructor, callback)
    }

    /**
     * 按类名和参数类型查找构造器并立即创建 hook。
     *
     * 默认使用当前 hook 运行时的 `ClassLoader`。
     *
     * @param className 目标类名
     * @param parameterTypesAndCallback 参数类型列表，最后一项必须是 `IMethodHook` 或 `IReplaceHook`
     */
    @JvmStatic
    fun findAndHookConstructor(
        className: String,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook =
        findAndHookConstructor(loadClass(className, HookClassLoader.currentOrDefault()), *parameterTypesAndCallback)

    /**
     * 按类名、指定 `ClassLoader` 和参数类型查找构造器并立即创建 hook。
     *
     * @param className 目标类名
     * @param classLoader 用于加载目标类的 `ClassLoader`
     * @param parameterTypesAndCallback 参数类型列表，最后一项必须是 `IMethodHook` 或 `IReplaceHook`
     */
    @JvmStatic
    fun findAndHookConstructor(
        className: String,
        classLoader: ClassLoader,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook =
        findAndHookConstructor(loadClass(className, classLoader), *parameterTypesAndCallback)

    private fun resolveMethod(clazz: Class<*>, methodName: String, args: Array<out Any>): Method {
        val types = args.dropLast(1).map { resolveType(clazz, it) }.toTypedArray()
        return runCatching { clazz.getDeclaredMethod(methodName, *types).also { it.isAccessible = true } }.getOrNull()
            ?: findMethodBestMatch(clazz, methodName, *types)
    }

    private fun resolveConstructor(clazz: Class<*>, args: Array<out Any>): Constructor<*> {
        val types = args.dropLast(1).map { resolveType(clazz, it) }.toTypedArray()
        return runCatching { clazz.getDeclaredConstructor(*types).also { it.isAccessible = true } }.getOrNull()
            ?: findConstructorBestMatch(clazz, *types)
    }

    private fun resolveType(owner: Class<*>, value: Any): Class<*> = when (value) {
        is Class<*> -> value
        is String -> loadClass(value, owner.classLoader ?: HookClassLoader.currentOrDefault())
        else -> throw IllegalArgumentException("Parameter type must be Class or class name String, got ${value.javaClass.name}")
    }

    private fun requireCallback(args: Array<out Any>): Any {
        require(args.isNotEmpty()) { "findAndHook requires parameter types followed by a callback" }
        return args.last()
    }

    private fun installCallback(member: Any, callback: Any): XC_MethodHook.Unhook = when (callback) {
        is IMethodHook -> when (member) {
            is Method -> createHook(member, callback)
            is Constructor<*> -> createHook(member, callback)
            else -> error("Unsupported member: $member")
        }
        is IReplaceHook -> when (member) {
            is Method -> createHook(member, callback)
            is Constructor<*> -> createHook(member, callback)
            else -> error("Unsupported member: $member")
        }
        else -> throw IllegalArgumentException("Unsupported callback type: ${callback.javaClass.name}")
    }
}
