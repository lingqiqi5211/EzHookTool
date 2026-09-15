@file:JvmName("BestMatchUtils")

package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private enum class CallMode { ANY, INSTANCE, STATIC }

private data class BestMethodCacheKey(
    val name: String,
    val types: List<Class<*>?>,
    val mode: CallMode,
)

private data class BestConstructorCacheKey(
    val types: List<Class<*>?>,
)

private fun matchRank(
    actual: Class<*>?,
    expected: Class<*>,
): Int =
    when {
        actual == null -> if (expected.isPrimitive) -1 else 2
        actual == expected -> 0
        !isTypeMatch(actual, expected) -> -1
        actual.isPrimitive || expected.isPrimitive -> 1
        else -> 2
    }

private fun moreSpecific(
    candidate: Executable,
    other: Executable,
    actual: List<Class<*>?>,
): Boolean {
    val left = candidate.parameterTypes
    val right = other.parameterTypes
    var narrower = false
    for (index in actual.indices) {
        val leftRank = matchRank(actual[index], left[index])
        val rightRank = matchRank(actual[index], right[index])
        if (leftRank > rightRank) return false
        if (leftRank < rightRank) {
            narrower = true
        } else if (left[index] != right[index]) {
            if (!right[index].isAssignableFrom(left[index])) return false
            narrower = true
        }
    }
    if (narrower) return true
    if (candidate.declaringClass != other.declaringClass) {
        return other.declaringClass.isAssignableFrom(candidate.declaringClass)
    }
    if (candidate is Method && other is Method) {
        if (candidate.isBridge != other.isBridge) return !candidate.isBridge
        return candidate.returnType != other.returnType && other.returnType.isAssignableFrom(candidate.returnType)
    }
    return false
}

private fun <T : Executable> selectBest(
    candidates: List<T>,
    actual: List<Class<*>?>,
    owner: Class<*>,
    type: MemberType,
    condition: String,
): T {
    val applicable =
        candidates.distinct().filter { member ->
            val params = member.parameterTypes
            params.size == actual.size && params.indices.all { matchRank(actual[it], params[it]) >= 0 }
        }
    if (applicable.isEmpty()) {
        throw MemberNotFoundException(
            type,
            owner.name,
            type == MemberType.METHOD,
            condition,
            if (EzReflect.debugMode) candidates.map { it.toString() } else emptyList(),
        )
    }
    val best =
        applicable.filter { candidate ->
            applicable.none { other -> other != candidate && moreSpecific(other, candidate, actual) }
        }
    if (best.size != 1) {
        throw SingleResultExpectedException(owner.name, "$condition; ambiguous candidates=${best.map { it.toString() }.sorted()}")
    }
    return best.single().also { it.isAccessible = true }
}

private fun bestMethod(
    clz: Class<*>,
    methodName: String,
    types: List<Class<*>?>,
    mode: CallMode = CallMode.ANY,
): Method =
    EzReflect.withQuery {
        val key = BestMethodCacheKey(methodName, types, mode)
        (EzReflect.cacheGet(clz, ReflectCacheBucket.METHOD, key) as? Method)?.let {
            it.isAccessible = true
            return@withQuery it
        }
        val resolver = EzReflect.memberResolver
        val candidates = mutableListOf<Method>()
        var current: Class<*>? = clz
        while (current != null) {
            for (method in resolver.methodsOf(current)) {
                if (method.name != methodName) continue
                if (current != clz && Modifier.isPrivate(method.modifiers)) continue
                if (mode != CallMode.ANY && Modifier.isStatic(method.modifiers) != (mode == CallMode.STATIC)) continue
                candidates += method
            }
            current = current.superclass
        }
        selectBest(candidates, types, clz, MemberType.METHOD, "bestMatch name=$methodName, argTypes=$types, mode=$mode")
            .also { EzReflect.cachePut(clz, ReflectCacheBucket.METHOD, key, it) }
    }

private fun bestConstructor(
    clz: Class<*>,
    types: List<Class<*>?>,
): Constructor<*> =
    EzReflect.withQuery {
        val key = BestConstructorCacheKey(types)
        (EzReflect.cacheGet(clz, ReflectCacheBucket.CONSTRUCTOR, key) as? Constructor<*>)?.let {
            it.isAccessible = true
            return@withQuery it
        }
        selectBest(
            EzReflect.memberResolver.constructorsOf(clz).toList(),
            types,
            clz,
            MemberType.CONSTRUCTOR,
            "bestMatch argTypes=$types",
        ).also { EzReflect.cachePut(clz, ReflectCacheBucket.CONSTRUCTOR, key, it) }
    }

/**
 * 按类型选择方法：精确类型优先于 primitive/wrapper 对应，其次选择更具体的引用类型。
 * 多参数必须逐位不劣于其它候选；无法唯一选择时抛 [SingleResultExpectedException]，不依赖枚举顺序。
 * 不自动做数值拓宽或打包 vararg；可变参数需显式提供数组类型。
 * Java 调用方用 `Class<?>...` 数组选择此重载；此入口不限制 static/instance。
 */
fun findMethodBestMatch(
    clz: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
): Method = bestMethod(clz, methodName, parameterTypes.toList())

/**
 * 按运行时实参类型选择方法，规则同类型重载；null 只匹配引用类型，并选其最具体候选。
 * 不相关的 null 候选会报告歧义；vararg 数组必须作为单个实参传入。
 */
fun findMethodBestMatch(
    clz: Class<*>,
    methodName: String,
    vararg args: Any?,
): Method = bestMethod(clz, methodName, args.map { it?.javaClass })

/** 按参数类型选择构造器；兼容、歧义及 vararg 规则与 [findMethodBestMatch] 相同。 */
fun findConstructorBestMatch(
    clz: Class<*>,
    vararg parameterTypes: Class<*>,
): Constructor<*> = bestConstructor(clz, parameterTypes.toList())

/** 按运行时实参选择构造器；null 和歧义规则与 [findMethodBestMatch] 相同。 */
fun findConstructorBestMatch(
    clz: Class<*>,
    vararg args: Any?,
): Constructor<*> = bestConstructor(clz, args.map { it?.javaClass })

internal fun findMethodBestMatchForCall(
    clz: Class<*>,
    methodName: String,
    args: Array<out Any?>,
    staticOnly: Boolean,
): Method = bestMethod(clz, methodName, args.map { it?.javaClass }, if (staticOnly) CallMode.STATIC else CallMode.INSTANCE)
