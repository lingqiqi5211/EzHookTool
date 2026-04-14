package io.github.lingqiqi5211.ezhooktool.xposed101.helper

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.findConstructorBestMatch as coreFindConstructorBestMatch
import io.github.lingqiqi5211.ezhooktool.core.findMethodBestMatch as coreFindMethodBestMatch
import io.github.lingqiqi5211.ezhooktool.core.getField
import io.github.lingqiqi5211.ezhooktool.core.getStaticField
import io.github.lingqiqi5211.ezhooktool.core.invokeMethodAuto
import io.github.lingqiqi5211.ezhooktool.core.invokeStaticMethodAuto
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.core.newInstanceAuto
import io.github.lingqiqi5211.ezhooktool.core.putField
import io.github.lingqiqi5211.ezhooktool.core.putStaticField
import io.github.lingqiqi5211.ezhooktool.xposed101.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed101.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed101.internal.AdditionalFields
import io.github.lingqiqi5211.ezhooktool.xposed101.internal.HookClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Consumer
import java.util.function.Function

/**
 * 兼容 libxposed 101 场景下 `XposedHelpers` 命名风格的桥接 API。
 *
 * 内部委托给 EzHookTool 的反射和 hook helper，方便旧风格调用迁移。
 */
object XposedHelpers {
    @JvmStatic
    /** 按类名加载类，失败时抛异常。 */
    fun findClass(className: String, classLoader: ClassLoader = HookClassLoader.currentOrDefault()): Class<*> =
        loadClass(className, classLoader)

    @JvmStatic
    /** 按类名加载类，失败时返回 `null`。 */
    fun findClassIfExists(className: String, classLoader: ClassLoader = HookClassLoader.currentOrDefault()): Class<*>? =
        loadClassOrNull(className, classLoader)

    @JvmStatic
    /**
     * 按精确参数类型查找方法。
     *
     * @param parameterTypes 目标方法的精确参数类型列表
     */
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method =
        clazz.getDeclaredMethod(methodName, *parameterTypes).also { it.isAccessible = true }

    @JvmStatic
    /** 按精确参数类型查找方法，找不到返回 `null`。 */
    fun findMethodExactIfExists(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { findMethodExact(clazz, methodName, *parameterTypes) }.getOrNull()

    @JvmStatic
    /** 按运行时参数最佳匹配方法。 */
    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg args: Any?): Method =
        coreFindMethodBestMatch(clazz, methodName, *args)

    @JvmStatic
    /**
     * 按精确参数类型查找构造器。
     *
     * @param parameterTypes 目标构造器的精确参数类型列表
     */
    fun findConstructorExact(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> =
        clazz.getDeclaredConstructor(*parameterTypes).also { it.isAccessible = true }

    @JvmStatic
    /** 按精确参数类型查找构造器，找不到返回 `null`。 */
    fun findConstructorExactIfExists(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*>? =
        runCatching { findConstructorExact(clazz, *parameterTypes) }.getOrNull()

    @JvmStatic
    /** 按运行时参数最佳匹配构造器。 */
    fun findConstructorBestMatch(clazz: Class<*>, vararg args: Any?): Constructor<*> =
        coreFindConstructorBestMatch(clazz, *args)

    @JvmStatic
    /** 按字段名查找字段。 */
    fun findField(clazz: Class<*>, fieldName: String): Field = clazz.getDeclaredField(fieldName).also { it.isAccessible = true }

    @JvmStatic
    /** 按字段名查找字段，找不到返回 `null`。 */
    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? = runCatching { findField(clazz, fieldName) }.getOrNull()

    @JvmStatic
    /** 按精确类型查找当前类中第一个字段。 */
    fun findFirstFieldByExactType(clazz: Class<*>, type: Class<*>): Field =
        clazz.declaredFields.firstOrNull { it.type == type }?.also { it.isAccessible = true }
            ?: throw NoSuchFieldError("${clazz.name} first field by type ${type.name}")

    @JvmStatic
    /**
     * 直接为方法创建 DSL 风格 hook。
     *
     * @param method 要 hook 的方法
     * @param block 用于声明 hook 行为的 DSL 块
     */
    fun hookMethod(method: Method, block: io.github.lingqiqi5211.ezhooktool.xposed101.dsl.HookFactory.() -> Unit): XposedInterface.HookHandle =
        method.createHook(block = block)

    @JvmStatic
    /**
     * 直接为构造器创建 DSL 风格 hook。
     *
     * @param constructor 要 hook 的构造器
     * @param block 用于声明 hook 行为的 DSL 块
     */
    fun hookConstructor(constructor: Constructor<*>, block: io.github.lingqiqi5211.ezhooktool.xposed101.dsl.HookFactory.() -> Unit): XposedInterface.HookHandle =
        constructor.createHook(block = block)

    @JvmStatic
    /**
     * 查找并 hook 方法。
     *
     * `parameterTypesAndCallback` 的最后一个参数必须是回调，其余参数是参数类型或类名。
     *
     * @param clazz 目标类
     * @param methodName 目标方法名
     * @param parameterTypesAndCallback 前面是参数类型描述，最后一个元素是 hook 回调
     */
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypesAndCallback: Any): XposedInterface.HookHandle {
        val method = resolveMethod(clazz, methodName, parameterTypesAndCallback)
        return installCallback(method, parameterTypesAndCallback.last())
    }

    @JvmStatic
    /**
     * 按类名查找并 hook 方法。
     *
     * @param className 目标类名
     * @param classLoader 用于解析 [className] 的 `ClassLoader`
     * @param methodName 目标方法名
     * @param parameterTypesAndCallback 前面是参数类型描述，最后一个元素是 hook 回调
     */
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
        methodName: String,
        vararg parameterTypesAndCallback: Any,
    ): XposedInterface.HookHandle = findAndHookMethod(findClass(className, classLoader), methodName, *parameterTypesAndCallback)

    @JvmStatic
    /**
     * 查找并 hook 构造器。
     *
     * @param clazz 目标类
     * @param parameterTypesAndCallback 前面是参数类型描述，最后一个元素是 hook 回调
     */
    fun findAndHookConstructor(clazz: Class<*>, vararg parameterTypesAndCallback: Any): XposedInterface.HookHandle {
        val constructor = resolveConstructor(clazz, parameterTypesAndCallback)
        return installCallback(constructor, parameterTypesAndCallback.last())
    }

    @JvmStatic
    /**
     * 按类名查找并 hook 构造器。
     *
     * @param className 目标类名
     * @param classLoader 用于解析 [className] 的 `ClassLoader`
     * @param parameterTypesAndCallback 前面是参数类型描述，最后一个元素是 hook 回调
     */
    fun findAndHookConstructor(
        className: String,
        classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
        vararg parameterTypesAndCallback: Any,
    ): XposedInterface.HookHandle = findAndHookConstructor(findClass(className, classLoader), *parameterTypesAndCallback)

    /** 自动匹配参数并调用实例方法。 */
    @JvmStatic
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? = obj.invokeMethodAuto(methodName, *args)

    /** 自动匹配参数并调用静态方法。 */
    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? = clazz.invokeStaticMethodAuto(methodName, *args)

    /** 自动匹配参数并创建实例。 */
    @JvmStatic
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any = clazz.newInstanceAuto(*args)

    /** 读取对象字段值。 */
    @JvmStatic
    fun getObjectField(obj: Any, fieldName: String): Any? = obj.getField(fieldName)

    /** 设置对象字段值。 */
    @JvmStatic
    fun setObjectField(obj: Any, fieldName: String, value: Any?) = obj.putField(fieldName, value)

    /** 读取静态字段值。 */
    @JvmStatic
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? = clazz.getStaticField(fieldName)

    /** 设置静态字段值。 */
    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) = clazz.putStaticField(fieldName, value)

    /** 设置额外实例字段。 */
    @JvmStatic
    fun setAdditionalInstanceField(obj: Any, key: String, value: Any?): Any? = AdditionalFields.setInstance(obj, key, value)

    /** 读取额外实例字段。 */
    @JvmStatic
    fun getAdditionalInstanceField(obj: Any, key: String): Any? = AdditionalFields.getInstance(obj, key)

    /** 移除额外实例字段。 */
    @JvmStatic
    fun removeAdditionalInstanceField(obj: Any, key: String): Any? = AdditionalFields.removeInstance(obj, key)

    /** 设置额外静态字段。 */
    @JvmStatic
    fun setAdditionalStaticField(clazz: Class<*>, key: String, value: Any?): Any? = AdditionalFields.setStatic(clazz, key, value)

    /** 读取额外静态字段。 */
    @JvmStatic
    fun getAdditionalStaticField(clazz: Class<*>, key: String): Any? = AdditionalFields.getStatic(clazz, key)

    /** 移除额外静态字段。 */
    @JvmStatic
    fun removeAdditionalStaticField(clazz: Class<*>, key: String): Any? = AdditionalFields.removeStatic(clazz, key)

    /**
     * 对方法或构造器做去优化处理。
     *
     * @param executable 目标方法或构造器
     */
    @JvmStatic
    fun deoptimize(executable: Executable): Boolean = EzXposed.deoptimize(executable)

    private fun resolveMethod(clazz: Class<*>, methodName: String, args: Array<out Any>): Method {
        val types = args.dropLast(1).map { resolveType(clazz, it) }.toTypedArray()
        return findMethodExactIfExists(clazz, methodName, *types) ?: coreFindMethodBestMatch(clazz, methodName, *types)
    }

    private fun resolveConstructor(clazz: Class<*>, args: Array<out Any>): Constructor<*> {
        val types = args.dropLast(1).map { resolveType(clazz, it) }.toTypedArray()
        return findConstructorExactIfExists(clazz, *types) ?: coreFindConstructorBestMatch(clazz, *types)
    }

    private fun resolveType(owner: Class<*>, value: Any): Class<*> = when (value) {
        is Class<*> -> value
        is String -> findClass(value, owner.classLoader ?: HookClassLoader.currentOrDefault())
        else -> throw IllegalArgumentException("Parameter type must be Class or class name String, got ${value.javaClass.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun installCallback(member: Any, callback: Any): XposedInterface.HookHandle = when (callback) {
        is XposedInterface.Hooker -> when (member) {
            is Method -> member.hook(callback = { callback.intercept(it) })
            is Constructor<*> -> member.hook(callback = { callback.intercept(it) })
            else -> error("Unsupported member: $member")
        }
        is Function<*, *> -> when (member) {
            is Method -> member.hookReplace { (callback as Function<HookParam, Any?>).apply(it) }
            is Constructor<*> -> member.hookReplace { (callback as Function<HookParam, Any?>).apply(it) }
            else -> error("Unsupported member: $member")
        }
        is Consumer<*> -> when (member) {
            is Method -> member.hookBefore { (callback as Consumer<HookParam>).accept(it) }
            is Constructor<*> -> member.hookBefore { (callback as Consumer<HookParam>).accept(it) }
            else -> error("Unsupported member: $member")
        }
        is Function1<*, *> -> when (member) {
            is Method -> member.hookBefore { (callback as (HookParam) -> Unit).invoke(it) }
            is Constructor<*> -> member.hookBefore { (callback as (HookParam) -> Unit).invoke(it) }
            else -> error("Unsupported member: $member")
        }
        else -> throw IllegalArgumentException("Unsupported callback type: ${callback.javaClass.name}")
    }
}
