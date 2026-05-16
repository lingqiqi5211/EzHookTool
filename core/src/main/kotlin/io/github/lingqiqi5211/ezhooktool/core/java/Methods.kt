package io.github.lingqiqi5211.ezhooktool.core.java

import io.github.lingqiqi5211.ezhooktool.core.MemberNotFoundException
import io.github.lingqiqi5211.ezhooktool.core.MemberType
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.callMethodAs
import io.github.lingqiqi5211.ezhooktool.core.callStaticMethod
import io.github.lingqiqi5211.ezhooktool.core.callStaticMethodAs
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import java.lang.reflect.Method
import java.util.function.Predicate

/**
 * 供 Java 调用的方法查找和调用入口。
 *
 * 示例：
 *
 * ```java
 * Method method = Methods.find(target)
 *         .filterByName("foo")
 *         .filterByParamCount(2)
 *         .filterByReturnType(String.class)
 *         .first();
 * ```
 */
object Methods {
    /**
     * 创建方法查询器。
     *
     * 默认先查当前类，找不到再查父类。
     *
     * @param clazz 目标类
     */
    @JvmStatic
    fun find(clazz: Class<*>): MethodSearch = MethodSearch(clazz)

    /**
     * 创建方法查询器，并指定父类查找策略。
     *
     * @param clazz 目标类
     * @param findSuper `null` 表示智能查找，`false` 只查当前类，`true` 查当前类和全部父类
     */
    @JvmStatic
    fun find(clazz: Class<*>, findSuper: Boolean?): MethodSearch = MethodSearch(clazz, findSuper)

    /**
     * 自动匹配参数并调用实例方法。
     *
     * @param obj 目标实例
     * @param methodName 方法名
     * @param args 实参
     */
    @JvmStatic
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? =
        obj.callMethod(methodName, *args)

    /**
     * 自动匹配参数并调用实例方法，并将结果转为指定类型。
     *
     * @param obj 目标实例
     * @param methodName 方法名
     * @param args 实参
     */
    @JvmStatic
    fun <T> callMethodAs(obj: Any, methodName: String, vararg args: Any?): T? =
        obj.callMethodAs(methodName, *args)

    /**
     * 自动匹配参数并调用静态方法。
     *
     * @param clazz 目标类
     * @param methodName 方法名
     * @param args 实参
     */
    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        clazz.callStaticMethod(methodName, *args)

    /**
     * 自动匹配参数并调用静态方法，并将结果转为指定类型。
     *
     * @param clazz 目标类
     * @param methodName 方法名
     * @param args 实参
     */
    @JvmStatic
    fun <T> callStaticMethodAs(clazz: Class<*>, methodName: String, vararg args: Any?): T? =
        clazz.callStaticMethodAs(methodName, *args)
}

/**
 * 供 Java 链式组合方法查询条件。
 *
 * `first()` 返回第一个匹配方法，`toList()` 返回全部匹配方法。
 */
class MethodSearch internal constructor(
    private val clazz: Class<*>,
    private var findSuper: Boolean? = null,
) {
    private val conditions = mutableListOf<MethodQuery.() -> Unit>()

    private fun add(condition: MethodQuery.() -> Unit): MethodSearch = apply {
        conditions += condition
    }

    /** 限定方法名。 */
    fun filterByName(value: String): MethodSearch = add { name(value) }

    /** [filterByName] 的短名称。 */
    fun name(value: String): MethodSearch = filterByName(value)

    /** 限定方法名包含指定文本。 */
    @JvmOverloads
    fun filterByNameContains(value: String, ignoreCase: Boolean = false): MethodSearch =
        add { nameContains(value, ignoreCase) }

    /** 限定方法名以指定文本开头。 */
    @JvmOverloads
    fun filterByNameStartsWith(value: String, ignoreCase: Boolean = false): MethodSearch =
        add { nameStartsWith(value, ignoreCase) }

    /** 限定方法名以指定文本结尾。 */
    @JvmOverloads
    fun filterByNameEndsWith(value: String, ignoreCase: Boolean = false): MethodSearch =
        add { nameEndsWith(value, ignoreCase) }

    /** 限定参数数量。 */
    fun filterByParamCount(value: Int): MethodSearch = add { paramCount(value) }

    /** [filterByParamCount] 的短名称。 */
    fun paramCount(value: Int): MethodSearch = filterByParamCount(value)

    /** 限定参数数量范围。 */
    fun filterByParamCountIn(min: Int, max: Int): MethodSearch = add { paramCountIn(min..max) }

    /** 限定为无参数方法。 */
    fun filterEmptyParam(): MethodSearch = add { noParams() }

    /** 限定为有参数方法。 */
    fun filterNotEmptyParam(): MethodSearch = add { hasParams() }

    /** 限定返回值类型。 */
    fun filterByReturnType(value: Class<*>): MethodSearch = add { returnType(value) }

    /** [filterByReturnType] 的短名称。 */
    fun returnType(value: Class<*>): MethodSearch = filterByReturnType(value)

    /** 限定返回值为 void。 */
    fun filterVoidReturnType(): MethodSearch = add { voidReturnType() }

    /** 限定返回值类型是 [value] 本身或子类。 */
    fun filterByReturnTypeExtendsFrom(value: Class<*>): MethodSearch = add { returnTypeExtendsFrom(value) }

    /** 限定完整参数类型，数量和顺序都必须一致。 */
    fun filterByParamTypes(vararg types: Class<*>): MethodSearch = add { parameterTypes(*types) }

    /** [filterByParamTypes] 的同义名称。 */
    fun filterByParameterTypes(vararg types: Class<*>): MethodSearch = filterByParamTypes(*types)

    /** [filterByParamTypes] 的短名称。 */
    fun parameterTypes(vararg types: Class<*>): MethodSearch = filterByParamTypes(*types)

    /** [filterByParamTypes] 的短名称。 */
    fun params(vararg types: Class<*>): MethodSearch = filterByParamTypes(*types)

    /** 限定方法参数能接收指定类型。 */
    fun filterByAssignableParamTypes(vararg types: Class<*>): MethodSearch =
        add { parameterTypesAssignableFrom(*types) }

    /** 限定声明的异常类型。 */
    fun filterByExceptionTypes(vararg types: Class<*>): MethodSearch = add { exceptionTypes(*types) }

    /** 限定为 static 方法。 */
    fun filterStatic(): MethodSearch = add { isStatic() }

    /** 限定为非 static 方法。 */
    fun filterNonStatic(): MethodSearch = add { notStatic() }

    /** 限定是否为 static 方法。 */
    fun isStatic(value: Boolean): MethodSearch = add { isStatic(value) }

    /** 限定为 static 方法。 */
    fun isStatic(): MethodSearch = filterStatic()

    /** 限定为非 static 方法。 */
    fun notStatic(): MethodSearch = filterNonStatic()

    /** 限定为 public 方法。 */
    fun filterPublic(): MethodSearch = add { isPublic() }

    /** 限定为非 public 方法。 */
    fun filterNonPublic(): MethodSearch = add { notPublic() }

    /** 限定为 private 方法。 */
    fun filterPrivate(): MethodSearch = add { isPrivate() }

    /** 限定为非 private 方法。 */
    fun filterNonPrivate(): MethodSearch = add { notPrivate() }

    /** 限定为 protected 方法。 */
    fun filterProtected(): MethodSearch = add { isProtected() }

    /** 限定为非 protected 方法。 */
    fun filterNonProtected(): MethodSearch = add { notProtected() }

    /** 限定为 final 方法。 */
    fun filterFinal(): MethodSearch = add { isFinal() }

    /** 限定为非 final 方法。 */
    fun filterNonFinal(): MethodSearch = add { notFinal() }

    /** 限定为 abstract 方法。 */
    fun filterAbstract(): MethodSearch = add { isAbstract() }

    /** 限定为非 abstract 方法。 */
    fun filterNonAbstract(): MethodSearch = add { notAbstract() }

    /** 限定为 native 方法。 */
    fun filterNative(): MethodSearch = add { isNative() }

    /** 限定为非 native 方法。 */
    fun filterNonNative(): MethodSearch = add { notNative() }

    /** 限定为 synchronized 方法。 */
    fun filterSynchronized(): MethodSearch = add { isSynchronized() }

    /** 限定为非 synchronized 方法。 */
    fun filterNonSynchronized(): MethodSearch = add { notSynchronized() }

    /** 限定为可变参数方法。 */
    fun filterVarargs(): MethodSearch = add { isVarArgs() }

    /** [filterVarargs] 的同义名称。 */
    fun filterVarArgs(): MethodSearch = filterVarargs()

    /** 限定为非可变参数方法。 */
    fun filterNonVarargs(): MethodSearch = add { notVarArgs() }

    /** [filterNonVarargs] 的同义名称。 */
    fun filterNonVarArgs(): MethodSearch = filterNonVarargs()

    /** 限定为 synthetic 方法。 */
    fun filterSynthetic(): MethodSearch = add { isSynthetic() }

    /** 限定为非 synthetic 方法。 */
    fun filterNonSynthetic(): MethodSearch = add { notSynthetic() }

    /** 限定为 bridge 方法。 */
    fun filterBridge(): MethodSearch = add { isBridge() }

    /** 限定为非 bridge 方法。 */
    fun filterNonBridge(): MethodSearch = add { notBridge() }

    /** 限定为 interface default 方法。 */
    fun filterDefault(): MethodSearch = add { isDefault() }

    /** 限定为非 interface default 方法。 */
    fun filterNonDefault(): MethodSearch = add { notDefault() }

    /** 只在当前类中查找。 */
    fun currentClassOnly(): MethodSearch = apply {
        findSuper = false
    }

    /** 查找当前类和全部父类。 */
    fun includeSuper(): MethodSearch = apply {
        findSuper = true
    }

    /** 添加自定义条件。 */
    fun filter(predicate: Predicate<Method>): MethodSearch = add { filter(predicate) }

    /** 返回第一个匹配的方法，找不到时抛出异常。 */
    fun first(): Method = firstOrNull()
        ?: throw MemberNotFoundException(MemberType.METHOD, clazz.name, findSuper != false)

    /** 返回第一个匹配的方法，找不到时返回 `null`。 */
    fun firstOrNull(): Method? {
        val query = conditions.toList()
        return findMethodOrNull(clazz, findSuper) {
            query.forEach { it() }
        }
    }

    /** 返回全部匹配的方法。 */
    fun toList(): List<Method> {
        val query = conditions.toList()
        return findAllMethods(clazz, findSuper) {
            query.forEach { it() }
        }
    }
}
