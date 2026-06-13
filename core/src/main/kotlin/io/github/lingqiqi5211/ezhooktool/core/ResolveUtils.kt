@file:JvmName("ResolveUtils")

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import java.lang.reflect.InvocationTargetException
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

private val hiddenMemberBooleanArg = arrayOf(Boolean::class.javaPrimitiveType!!)

private fun Throwable.unwrapMemberResolveCause(): Throwable =
    if (this is InvocationTargetException) targetException ?: this else this

private fun Throwable.isRecoverableMemberResolveError(): Boolean = when (unwrapMemberResolveCause()) {
    is NoClassDefFoundError,
    is TypeNotPresentException,
    is LinkageError -> true
    else -> false
}

internal fun <T> resolveDeclaredMembersFallback(
    directAccess: () -> Array<T>,
    hiddenAccess: () -> Array<T>? = { null },
    emptyAccess: () -> Array<T>,
): Array<T> = try {
    directAccess()
} catch (throwable: Throwable) {
    if (!throwable.isRecoverableMemberResolveError()) throw throwable
    hiddenAccess() ?: emptyAccess()
}

@Suppress("UNCHECKED_CAST")
private fun <T> Class<*>.hiddenDeclaredMembersOrNull(
    componentType: Class<*>,
    vararg accessorNames: String,
): Array<T>? {
    for (accessorName in accessorNames) {
        val accessor = Class::class.java.declaredMethods.firstOrNull {
            it.name == accessorName &&
                    it.parameterTypes.contentEquals(hiddenMemberBooleanArg) &&
                    it.returnType.isArray &&
                    it.returnType.componentType == componentType
        } ?: continue
        val members = runCatching {
            accessor.isAccessible = true
            accessor.invoke(this, false)
        }.getOrNull()
        if (members is Array<*>) return members as Array<T>
    }
    return null
}

internal object DefaultMemberResolver : MemberResolver {
    override fun methodsOf(clz: Class<*>): Array<Method> = resolveDeclaredMembersFallback(
        directAccess = { clz.declaredMethods },
        hiddenAccess = {
            clz.hiddenDeclaredMembersOrNull(
                componentType = Method::class.java,
                "getDeclaredMethodsUnchecked",
                "privateGetDeclaredMethods",
            )
        },
        emptyAccess = { emptyArray() },
    )

    override fun fieldsOf(clz: Class<*>): Array<Field> = resolveDeclaredMembersFallback(
        directAccess = { clz.declaredFields },
        hiddenAccess = {
            clz.hiddenDeclaredMembersOrNull(
                componentType = Field::class.java,
                "getDeclaredFieldsUnchecked",
                "privateGetDeclaredFields",
            )
        },
        emptyAccess = { emptyArray() },
    )

    override fun constructorsOf(clz: Class<*>): Array<Constructor<*>> = resolveDeclaredMembersFallback(
        directAccess = { clz.declaredConstructors },
        hiddenAccess = {
            clz.hiddenDeclaredMembersOrNull(
                componentType = Constructor::class.java,
                "getDeclaredConstructorsInternal",
                "privateGetDeclaredConstructors",
            )
        },
        emptyAccess = { emptyArray() },
    )
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
    /**
     * 保留以兼容旧代码，但**不再改变查找语义**。
     *
     * 历史版本里 `optional()` 试图把后续 `method()/field()/constructor()` 改成"未命中返回 null"，
     * 但这些方法返回类型非空，实现只能在未命中时抛 [MemberNotFoundException]——与名字预期相反。
     *
     * 想要"未命中返回 null" 的行为，直接使用 [methodOrNull]、[fieldOrNull]、[constructorOrNull] 即可。
     */
    @Deprecated(
        message = "optional() 不再改变查找语义；请改用 methodOrNull / fieldOrNull / constructorOrNull",
        level = DeprecationLevel.WARNING,
    )
    fun optional(): ResolveSession = copy(optionalMode = true)

    /**
     * 设置是否搜索父类。
     *
     * @param enabled `null` 表示智能模式，`false` 只查当前类，`true` 强制沿继承链查找
     */
    fun superclass(enabled: Boolean? = true): ResolveSession = copy(findSuper = enabled)

    private fun MethodQuery.applySearchScope() {
        when (findSuper) {
            false -> findOnlyClass()
            true -> findAndSuper()
            null -> Unit
        }
    }

    private fun FieldQuery.applySearchScope() {
        when (findSuper) {
            false -> findOnlyClass()
            true -> findAndSuper()
            null -> Unit
        }
    }

    /** 按查询条件解析单个方法。未命中抛 [MemberNotFoundException]，需要 null 结果请用 [methodOrNull]。 */
    fun method(query: MethodQuery.() -> Unit): Method = findMethod(targetClass) {
        applySearchScope()
        query()
    }

    /** 按查询条件解析单个方法，未命中时返回 `null`。 */
    fun methodOrNull(query: MethodQuery.() -> Unit): Method? = findMethodOrNull(targetClass) {
        applySearchScope()
        query()
    }

    /** 按查询条件解析全部匹配的方法。 */
    fun methods(query: MethodQuery.() -> Unit): List<Method> = findAllMethods(targetClass) {
        applySearchScope()
        query()
    }

    /** 解析全部方法。 */
    fun methods(): List<Method> = findAllMethods(targetClass) { applySearchScope() }

    /** 按查询条件解析单个字段。未命中抛 [MemberNotFoundException]，需要 null 结果请用 [fieldOrNull]。 */
    fun field(query: FieldQuery.() -> Unit): Field = findField(targetClass) {
        applySearchScope()
        query()
    }

    /** 按查询条件解析单个字段，未命中时返回 `null`。 */
    fun fieldOrNull(query: FieldQuery.() -> Unit): Field? = findFieldOrNull(targetClass) {
        applySearchScope()
        query()
    }

    /** 按查询条件解析全部匹配的字段。 */
    fun fields(query: FieldQuery.() -> Unit): List<Field> = findAllFields(targetClass) {
        applySearchScope()
        query()
    }

    /** 解析全部字段。 */
    fun fields(): List<Field> = findAllFields(targetClass) { applySearchScope() }

    /** 按查询条件解析单个构造器。未命中抛 [MemberNotFoundException]，需要 null 结果请用 [constructorOrNull]。 */
    fun constructor(query: ConstructorQuery.() -> Unit): Constructor<*> = findConstructor(targetClass, query)

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
