@file:JvmName("ResolveUtils")

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 成员枚举策略。
 *
 * 默认实现直接读取 `declaredMethods`、`declaredFields` 和 `declaredConstructors`，
 * 如需兼容特殊运行时，可替换为自定义实现后赋给 [EzReflect.memberResolver]。
 */
interface MemberResolver {
    /**
     * 返回类中可参与查找的方法列表。
     *
     * @param clz 目标类
     */
    fun methodsOf(clz: Class<*>): Array<Method>

    /**
     * 返回类中可参与查找的字段列表。
     *
     * @param clz 目标类
     */
    fun fieldsOf(clz: Class<*>): Array<Field>

    /**
     * 返回类中可参与查找的构造器列表。
     *
     * @param clz 目标类
     */
    fun constructorsOf(clz: Class<*>): Array<Constructor<*>>
}

internal object DefaultMemberResolver : MemberResolver {
    override fun methodsOf(clz: Class<*>): Array<Method> = clz.declaredMethods
    override fun fieldsOf(clz: Class<*>): Array<Field> = clz.declaredFields
    override fun constructorsOf(clz: Class<*>): Array<Constructor<*>> = clz.declaredConstructors
}

/**
 * 绑定类或实例的链式解析会话。
 *
 * 适合把方法、字段、构造器查找以及调用集中在一条链上表达。
 */
class ResolveSession private constructor(
    private val targetClass: Class<*>,
    private val targetInstance: Any? = null,
    private val optionalMode: Boolean = false,
    private val findSuper: Boolean? = null,
) {
    /** 切换到可选模式，未命中时返回 `null` 风格结果。 */
    fun optional(): ResolveSession = copy(optionalMode = true)

    /**
     * 设置是否搜索父类。
     *
     * @param enabled `null` 表示智能模式，`false` 只查当前类，`true` 强制沿继承链查找
     */
    fun superclass(enabled: Boolean? = true): ResolveSession = copy(findSuper = enabled)

    /** 按查询条件解析单个方法。 */
    fun method(query: MethodQuery.() -> Unit): Method =
        if (optionalMode) methodOrNull(query) ?: throw MemberNotFoundException(MemberType.METHOD, targetClass.name, findSuper != false)
        else findMethod(targetClass, findSuper, query)

    /** 按查询条件解析单个方法，未命中时返回 `null`。 */
    fun methodOrNull(query: MethodQuery.() -> Unit): Method? = findMethodOrNull(targetClass, findSuper, query)

    /** 按查询条件解析全部匹配的方法。 */
    fun methods(query: MethodQuery.() -> Unit): List<Method> = findAllMethods(targetClass, findSuper, query)

    /** 解析全部方法。 */
    fun methods(): List<Method> = findAllMethods(targetClass, findSuper)

    /** 按查询条件解析单个字段。 */
    fun field(query: FieldQuery.() -> Unit): Field =
        if (optionalMode) fieldOrNull(query) ?: throw MemberNotFoundException(MemberType.FIELD, targetClass.name, findSuper != false)
        else findField(targetClass, findSuper, query)

    /** 按查询条件解析单个字段，未命中时返回 `null`。 */
    fun fieldOrNull(query: FieldQuery.() -> Unit): Field? = findFieldOrNull(targetClass, findSuper, query)

    /** 按查询条件解析全部匹配的字段。 */
    fun fields(query: FieldQuery.() -> Unit): List<Field> = findAllFields(targetClass, findSuper, query)

    /** 解析全部字段。 */
    fun fields(): List<Field> = findAllFields(targetClass, findSuper)

    /** 按查询条件解析单个构造器。 */
    fun constructor(query: ConstructorQuery.() -> Unit): Constructor<*> =
        if (optionalMode) constructorOrNull(query) ?: throw MemberNotFoundException(MemberType.CONSTRUCTOR, targetClass.name, false)
        else findConstructor(targetClass, query)

    /** 按查询条件解析单个构造器，未命中时返回 `null`。 */
    fun constructorOrNull(query: ConstructorQuery.() -> Unit): Constructor<*>? = findConstructorOrNull(targetClass, query)

    /** 按查询条件解析全部匹配的构造器。 */
    fun constructors(query: ConstructorQuery.() -> Unit): List<Constructor<*>> = findAllConstructors(targetClass, query)

    /** 解析全部构造器。 */
    fun constructors(): List<Constructor<*>> = findAllConstructors(targetClass)

    /**
     * 对绑定实例按名称执行实例方法自动匹配调用。
     *
     * @param methodName 目标方法名
     * @param args 传给目标方法的实参
     */
    fun call(methodName: String, vararg args: Any?): Any? {
        val instance = targetInstance ?: error("ResolveSession.call requires bound instance")
        return instance.callMethod(methodName, *args)
    }

    /**
     * 对绑定类按名称执行静态方法自动匹配调用。
     *
     * @param methodName 目标静态方法名
     * @param args 传给目标方法的实参
     */
    fun callStatic(methodName: String, vararg args: Any?): Any? = targetClass.callStaticMethod(methodName, *args)

    private fun copy(
        targetClass: Class<*> = this.targetClass,
        targetInstance: Any? = this.targetInstance,
        optionalMode: Boolean = this.optionalMode,
        findSuper: Boolean? = this.findSuper,
    ) = ResolveSession(targetClass, targetInstance, optionalMode, findSuper)

    companion object {
        /**
         * 从 [Class] 创建解析会话。
         *
         * @param clz 绑定的目标类
         */
        @JvmStatic
        fun of(clz: Class<*>): ResolveSession = ResolveSession(clz)

        /**
         * 从实例创建解析会话，并允许后续调用 [call]。
         *
         * @param instance 绑定的目标实例
         */
        @JvmStatic
        fun of(instance: Any): ResolveSession = ResolveSession(instance.javaClass, instance)
    }
}

/** 从 [Class] 打开链式解析会话。 */
fun Class<*>.resolve(): ResolveSession = ResolveSession.of(this)

/** 从实例打开链式解析会话。 */
fun Any.resolve(): ResolveSession = ResolveSession.of(this)
