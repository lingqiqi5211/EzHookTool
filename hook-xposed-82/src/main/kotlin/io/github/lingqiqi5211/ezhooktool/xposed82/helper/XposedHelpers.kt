package io.github.lingqiqi5211.ezhooktool.xposed82.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
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
import io.github.lingqiqi5211.ezhooktool.xposed82.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed82.internal.AdditionalFields
import io.github.lingqiqi5211.ezhooktool.xposed82.internal.DeoptimizeBridge
import io.github.lingqiqi5211.ezhooktool.xposed82.internal.HookClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Consumer
import java.util.function.Function

/**
 * 兼容经典 Xposed `XposedHelpers` 命名风格的桥接 API。
 *
 * 内部实现委托给 EzHookTool 的反射与 hook helper，方便旧代码平滑迁移。
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
     * 查找并 hook 方法。
     *
     * `parameterTypesAndCallback` 的最后一个参数必须是回调，其余参数是参数类型或类名。
     *
     * @param clazz 目标类
     * @param methodName 目标方法名
     * @param parameterTypesAndCallback 前面是参数类型描述，最后一个元素是 hook 回调
     */
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypesAndCallback: Any): XC_MethodHook.Unhook {
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
    ): XC_MethodHook.Unhook = findAndHookMethod(findClass(className, classLoader), methodName, *parameterTypesAndCallback)

    @JvmStatic
    /**
     * 查找并 hook 构造器。
     *
     * @param clazz 目标类
     * @param parameterTypesAndCallback 前面是参数类型描述，最后一个元素是 hook 回调
     */
    fun findAndHookConstructor(clazz: Class<*>, vararg parameterTypesAndCallback: Any): XC_MethodHook.Unhook {
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
    ): XC_MethodHook.Unhook = findAndHookConstructor(findClass(className, classLoader), *parameterTypesAndCallback)

    @JvmStatic
    /** 自动匹配参数并调用实例方法。 */
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? = obj.invokeMethodAuto(methodName, *args)

    @JvmStatic
    /** 自动匹配参数并调用静态方法。 */
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        clazz.invokeStaticMethodAuto(methodName, *args)

    @JvmStatic
    /** 自动匹配参数并创建实例。 */
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any = clazz.newInstanceAuto(*args)

    @JvmStatic
    /** 读取对象字段值。 */
    fun getObjectField(obj: Any, fieldName: String): Any? = obj.getField(fieldName)

    @JvmStatic
    /** 设置对象字段值。 */
    fun setObjectField(obj: Any, fieldName: String, value: Any?) = obj.putField(fieldName, value)

    @JvmStatic
    /** 读取静态字段值。 */
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? = clazz.getStaticField(fieldName)

    @JvmStatic
    /** 设置静态字段值。 */
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) = clazz.putStaticField(fieldName, value)

    /** 读取布尔对象字段。 */
    @JvmStatic
    fun getBooleanField(obj: Any, fieldName: String): Boolean = getObjectField(obj, fieldName) as Boolean

    /** 设置布尔对象字段。 */
    @JvmStatic
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) = setObjectField(obj, fieldName, value)

    /** 读取整型对象字段。 */
    @JvmStatic
    fun getIntField(obj: Any, fieldName: String): Int = getObjectField(obj, fieldName) as Int

    /** 设置整型对象字段。 */
    @JvmStatic
    fun setIntField(obj: Any, fieldName: String, value: Int) = setObjectField(obj, fieldName, value)

    /** 读取长整型对象字段。 */
    @JvmStatic
    fun getLongField(obj: Any, fieldName: String): Long = getObjectField(obj, fieldName) as Long

    /** 设置长整型对象字段。 */
    @JvmStatic
    fun setLongField(obj: Any, fieldName: String, value: Long) = setObjectField(obj, fieldName, value)

    /** 读取浮点对象字段。 */
    @JvmStatic
    fun getFloatField(obj: Any, fieldName: String): Float = getObjectField(obj, fieldName) as Float

    /** 设置浮点对象字段。 */
    @JvmStatic
    fun setFloatField(obj: Any, fieldName: String, value: Float) = setObjectField(obj, fieldName, value)

    /** 读取双精度对象字段。 */
    @JvmStatic
    fun getDoubleField(obj: Any, fieldName: String): Double = getObjectField(obj, fieldName) as Double

    /** 设置双精度对象字段。 */
    @JvmStatic
    fun setDoubleField(obj: Any, fieldName: String, value: Double) = setObjectField(obj, fieldName, value)

    /** 读取字节对象字段。 */
    @JvmStatic
    fun getByteField(obj: Any, fieldName: String): Byte = getObjectField(obj, fieldName) as Byte

    /** 设置字节对象字段。 */
    @JvmStatic
    fun setByteField(obj: Any, fieldName: String, value: Byte) = setObjectField(obj, fieldName, value)

    /** 读取短整型对象字段。 */
    @JvmStatic
    fun getShortField(obj: Any, fieldName: String): Short = getObjectField(obj, fieldName) as Short

    /** 设置短整型对象字段。 */
    @JvmStatic
    fun setShortField(obj: Any, fieldName: String, value: Short) = setObjectField(obj, fieldName, value)

    /** 读取字符对象字段。 */
    @JvmStatic
    fun getCharField(obj: Any, fieldName: String): Char = getObjectField(obj, fieldName) as Char

    /** 设置字符对象字段。 */
    @JvmStatic
    fun setCharField(obj: Any, fieldName: String, value: Char) = setObjectField(obj, fieldName, value)

    /** 读取布尔静态字段。 */
    @JvmStatic
    fun getStaticBooleanField(clazz: Class<*>, fieldName: String): Boolean = getStaticObjectField(clazz, fieldName) as Boolean

    /** 设置布尔静态字段。 */
    @JvmStatic
    fun setStaticBooleanField(clazz: Class<*>, fieldName: String, value: Boolean) = setStaticObjectField(clazz, fieldName, value)

    /** 读取整型静态字段。 */
    @JvmStatic
    fun getStaticIntField(clazz: Class<*>, fieldName: String): Int = getStaticObjectField(clazz, fieldName) as Int

    /** 设置整型静态字段。 */
    @JvmStatic
    fun setStaticIntField(clazz: Class<*>, fieldName: String, value: Int) = setStaticObjectField(clazz, fieldName, value)

    /** 读取长整型静态字段。 */
    @JvmStatic
    fun getStaticLongField(clazz: Class<*>, fieldName: String): Long = getStaticObjectField(clazz, fieldName) as Long

    /** 设置长整型静态字段。 */
    @JvmStatic
    fun setStaticLongField(clazz: Class<*>, fieldName: String, value: Long) = setStaticObjectField(clazz, fieldName, value)

    /** 读取浮点静态字段。 */
    @JvmStatic
    fun getStaticFloatField(clazz: Class<*>, fieldName: String): Float = getStaticObjectField(clazz, fieldName) as Float

    /** 设置浮点静态字段。 */
    @JvmStatic
    fun setStaticFloatField(clazz: Class<*>, fieldName: String, value: Float) = setStaticObjectField(clazz, fieldName, value)

    /** 读取双精度静态字段。 */
    @JvmStatic
    fun getStaticDoubleField(clazz: Class<*>, fieldName: String): Double = getStaticObjectField(clazz, fieldName) as Double

    /** 设置双精度静态字段。 */
    @JvmStatic
    fun setStaticDoubleField(clazz: Class<*>, fieldName: String, value: Double) = setStaticObjectField(clazz, fieldName, value)

    /** 读取字节静态字段。 */
    @JvmStatic
    fun getStaticByteField(clazz: Class<*>, fieldName: String): Byte = getStaticObjectField(clazz, fieldName) as Byte

    /** 设置字节静态字段。 */
    @JvmStatic
    fun setStaticByteField(clazz: Class<*>, fieldName: String, value: Byte) = setStaticObjectField(clazz, fieldName, value)

    /** 读取短整型静态字段。 */
    @JvmStatic
    fun getStaticShortField(clazz: Class<*>, fieldName: String): Short = getStaticObjectField(clazz, fieldName) as Short

    /** 设置短整型静态字段。 */
    @JvmStatic
    fun setStaticShortField(clazz: Class<*>, fieldName: String, value: Short) = setStaticObjectField(clazz, fieldName, value)

    /** 读取字符静态字段。 */
    @JvmStatic
    fun getStaticCharField(clazz: Class<*>, fieldName: String): Char = getStaticObjectField(clazz, fieldName) as Char

    /** 设置字符静态字段。 */
    @JvmStatic
    fun setStaticCharField(clazz: Class<*>, fieldName: String, value: Char) = setStaticObjectField(clazz, fieldName, value)

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

    /** 对方法做去优化处理。 */
    @JvmStatic
    fun deoptimize(method: Method): Boolean = DeoptimizeBridge.deoptimize(method)

    /** 对构造器做去优化处理。 */
    @JvmStatic
    fun deoptimize(constructor: Constructor<*>): Boolean = DeoptimizeBridge.deoptimize(constructor)

    /**
     * 对类中指定名称的方法批量去优化。
     *
     * @param clazz 目标类
     * @param names 要处理的方法名列表
     */
    @JvmStatic
    fun deoptimizeMethods(clazz: Class<*>, vararg names: String) = DeoptimizeBridge.deoptimizeMethods(clazz, *names)

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
    private fun installCallback(member: Any, callback: Any): XC_MethodHook.Unhook = when (callback) {
        is XC_MethodHook -> XposedBridge.hookMethod(member as java.lang.reflect.Member, callback)
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
