package io.github.lingqiqi5211.ezhooktool.core.java

import io.github.lingqiqi5211.ezhooktool.core.MemberNotFoundException
import io.github.lingqiqi5211.ezhooktool.core.MemberType
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findConstructorOrNull
import io.github.lingqiqi5211.ezhooktool.core.newInstanceAuto
import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import java.lang.reflect.Constructor
import java.util.function.Predicate

/**
 * 供 Java 调用的构造器查找和实例创建入口。
 */
object Constructors {
    /**
     * 创建构造器查询器。
     *
     * @param clazz 目标类
     */
    @JvmStatic
    fun find(clazz: Class<*>): ConstructorSearch = ConstructorSearch(clazz)

    /**
     * 自动匹配构造器参数并创建实例。
     *
     * @param clazz 目标类
     * @param args 构造器实参
     */
    @JvmStatic
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any = clazz.newInstanceAuto(*args)
}

/**
 * 供 Java 链式组合构造器查询条件。
 *
 * `first()` 返回第一个匹配构造器，`toList()` 返回全部匹配构造器。
 */
class ConstructorSearch internal constructor(
    private val clazz: Class<*>,
) {
    private val conditions = mutableListOf<ConstructorQuery.() -> Unit>()

    private fun add(condition: ConstructorQuery.() -> Unit): ConstructorSearch = apply {
        conditions += condition
    }

    /** 限定参数数量。 */
    fun filterByParamCount(value: Int): ConstructorSearch = add { paramCount(value) }

    /** [filterByParamCount] 的短名称。 */
    fun paramCount(value: Int): ConstructorSearch = filterByParamCount(value)

    /** 限定参数数量范围。 */
    fun filterByParamCountIn(min: Int, max: Int): ConstructorSearch = add { paramCountIn(min..max) }

    /** 限定为无参数构造器。 */
    fun filterEmptyParam(): ConstructorSearch = add { noParams() }

    /** 限定为有参数构造器。 */
    fun filterNotEmptyParam(): ConstructorSearch = add { hasParams() }

    /** 限定完整参数类型，数量和顺序都必须一致。 */
    fun filterByParamTypes(vararg types: Class<*>): ConstructorSearch = add { parameterTypes(*types) }

    /** [filterByParamTypes] 的同义名称。 */
    fun filterByParameterTypes(vararg types: Class<*>): ConstructorSearch = filterByParamTypes(*types)

    /** [filterByParamTypes] 的短名称。 */
    fun parameterTypes(vararg types: Class<*>): ConstructorSearch = filterByParamTypes(*types)

    /** [filterByParamTypes] 的短名称。 */
    fun params(vararg types: Class<*>): ConstructorSearch = filterByParamTypes(*types)

    /** 限定构造器参数能接收指定类型。 */
    fun filterByAssignableParamTypes(vararg types: Class<*>): ConstructorSearch =
        add { parameterTypesAssignableFrom(*types) }

    /** 限定声明的异常类型。 */
    fun filterByExceptionTypes(vararg types: Class<*>): ConstructorSearch = add { exceptionTypes(*types) }

    /** 限定为 public 构造器。 */
    fun filterPublic(): ConstructorSearch = add { isPublic() }

    /** 限定为非 public 构造器。 */
    fun filterNonPublic(): ConstructorSearch = add { notPublic() }

    /** 限定为 private 构造器。 */
    fun filterPrivate(): ConstructorSearch = add { isPrivate() }

    /** 限定为非 private 构造器。 */
    fun filterNonPrivate(): ConstructorSearch = add { notPrivate() }

    /** 限定为 protected 构造器。 */
    fun filterProtected(): ConstructorSearch = add { isProtected() }

    /** 限定为非 protected 构造器。 */
    fun filterNonProtected(): ConstructorSearch = add { notProtected() }

    /** 限定为可变参数构造器。 */
    fun filterVarargs(): ConstructorSearch = add { isVarArgs() }

    /** [filterVarargs] 的同义名称。 */
    fun filterVarArgs(): ConstructorSearch = filterVarargs()

    /** 限定为非可变参数构造器。 */
    fun filterNonVarargs(): ConstructorSearch = add { notVarArgs() }

    /** [filterNonVarargs] 的同义名称。 */
    fun filterNonVarArgs(): ConstructorSearch = filterNonVarargs()

    /** 限定为 synthetic 构造器。 */
    fun filterSynthetic(): ConstructorSearch = add { isSynthetic() }

    /** 限定为非 synthetic 构造器。 */
    fun filterNonSynthetic(): ConstructorSearch = add { notSynthetic() }

    /** 添加自定义条件。 */
    fun filter(predicate: Predicate<Constructor<*>>): ConstructorSearch = add { filter(predicate) }

    /** 返回第一个匹配的构造器，找不到时抛出异常。 */
    fun first(): Constructor<*> = firstOrNull()
        ?: throw MemberNotFoundException(MemberType.CONSTRUCTOR, clazz.name, false)

    /** 返回第一个匹配的构造器，找不到时返回 `null`。 */
    fun firstOrNull(): Constructor<*>? {
        val query = conditions.toList()
        return findConstructorOrNull(clazz) {
            query.forEach { it() }
        }
    }

    /** 返回全部匹配的构造器。 */
    fun toList(): List<Constructor<*>> {
        val query = conditions.toList()
        return findAllConstructors(clazz) {
            query.forEach { it() }
        }
    }
}
