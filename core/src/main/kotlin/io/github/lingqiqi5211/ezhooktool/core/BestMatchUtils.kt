@file:JvmName("BestMatchUtils")

package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private data class BestMethodCacheKey(
    val clz: Class<*>,
    val name: String,
    val types: List<Class<*>>,
)

private data class BestConstructorCacheKey(
    val clz: Class<*>,
    val types: List<Class<*>>,
    val nullMask: List<Boolean>,
)

private data class BestMethodArg(val type: Class<*>?, val isNull: Boolean)

private fun inferBestMatchArgs(args: Array<out Any?>): Array<BestMethodArg> = Array(args.size) { index ->
    val arg = args[index]
    if (arg == null) BestMethodArg(type = null, isNull = true) else BestMethodArg(arg.javaClass, isNull = false)
}

private fun paramTypesMatch(actual: Array<BestMethodArg>, expected: Array<Class<*>>): Boolean {
    if (actual.size != expected.size) return false
    for (i in actual.indices) {
        val current = actual[i]
        if (current.isNull) {
            if (expected[i].isPrimitive) return false
            continue
        }
        if (!isTypeMatch(current.type!!, expected[i])) return false
    }
    return true
}

private fun scoreMatch(actual: Array<BestMethodArg>, expected: Array<Class<*>>): Int {
    var score = 0
    for (i in actual.indices) {
        val current = actual[i]
        if (current.isNull) {
            score += 1
            continue
        }
        if (current.type == expected[i]) continue
        score += 1
        if (!isTypeMatch(current.type!!, expected[i])) score += 10
    }
    return score
}

private fun scoreMatch(actual: Array<Class<*>>, expected: Array<Class<*>>): Int {
    var score = 0
    for (i in actual.indices) {
        if (actual[i] == expected[i]) continue
        score += 1
        if (!isTypeMatch(actual[i], expected[i])) score += 10
    }
    return score
}

/**
 * 按参数类型查找最合适的方法。
 *
 * 优先尝试精确匹配，失败后再按 primitive/wrapper 兼容和继承关系选出最接近的候选。
 *
 * @param clz 目标类
 * @param methodName 目标方法名
 * @param parameterTypes 用于匹配的参数类型列表
 */
fun findMethodBestMatch(
    clz: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
): Method {
    if (EzReflect.cacheEnabled) {
        val key = BestMethodCacheKey(clz, methodName, parameterTypes.toList())
        val cached = EzReflect.cache[key]
        if (cached is Method) return cached
    }

    val exact = clz.methodOrNull(methodName, argTypes(*parameterTypes))
    if (exact != null) {
        if (EzReflect.cacheEnabled) {
            EzReflect.cache[BestMethodCacheKey(clz, methodName, parameterTypes.toList())] = exact
        }
        return exact
    }

    var best: Method? = null
    var bestScore = Int.MAX_VALUE
    var current: Class<*>? = clz
    var considerPrivate = true
    while (current != null) {
        for (method in current.declaredMethods) {
            if (method.name != methodName) continue
            if (!considerPrivate && Modifier.isPrivate(method.modifiers)) continue
            if (!paramTypesMatch(parameterTypes.toList().toTypedArray(), method.parameterTypes)) continue
            val score = scoreMatch(parameterTypes.toList().toTypedArray(), method.parameterTypes)
            if (score < bestScore) {
                method.isAccessible = true
                best = method
                bestScore = score
            }
        }
        current = current.superclass
        considerPrivate = false
    }

    return best?.also {
        if (EzReflect.cacheEnabled) {
            EzReflect.cache[BestMethodCacheKey(clz, methodName, parameterTypes.toList())] = it
        }
    } ?: throw MemberNotFoundException(
        memberType = MemberType.METHOD,
        targetClass = clz.name,
        searchedSuper = true,
        conditionDesc = "bestMatch name=$methodName, argTypes=${parameterTypes.map { it.simpleName }}",
        candidates = emptyList(),
    )
}

/**
 * 按实参数值推断最合适的方法。
 *
 * 与参数类型重载相比，此版本会根据运行时参数类型自动推断，`null` 参数会参与模糊匹配。
 *
 * @param clz 目标类
 * @param methodName 目标方法名
 * @param args 用于推断签名的运行时实参
 */
fun findMethodBestMatch(
    clz: Class<*>,
    methodName: String,
    vararg args: Any?,
): Method {
    val actual = inferBestMatchArgs(args)
    var best: Method? = null
    var bestScore = Int.MAX_VALUE
    var current: Class<*>? = clz
    var considerPrivate = true
    while (current != null) {
        for (method in EzReflect.memberResolver.methodsOf(current)) {
            if (method.name != methodName) continue
            if (!considerPrivate && Modifier.isPrivate(method.modifiers)) continue
            if (!paramTypesMatch(actual, method.parameterTypes)) continue
            val score = scoreMatch(actual, method.parameterTypes)
            if (score < bestScore) {
                method.isAccessible = true
                best = method
                bestScore = score
            }
        }
        current = current.superclass
        considerPrivate = false
    }
    return best ?: throw MemberNotFoundException(
        memberType = MemberType.METHOD,
        targetClass = clz.name,
        searchedSuper = true,
        conditionDesc = "bestMatch name=$methodName, args=${args.map { it?.javaClass?.simpleName ?: "null" }}",
        candidates = emptyList(),
    )
}

/**
 * 按参数类型查找最合适的构造器。
 *
 * 优先尝试精确匹配，失败后再按 primitive/wrapper 兼容和继承关系选出最接近的候选。
 *
 * @param clz 目标类
 * @param parameterTypes 用于匹配的构造器参数类型列表
 */
fun findConstructorBestMatch(
    clz: Class<*>,
    vararg parameterTypes: Class<*>,
): Constructor<*> {
    if (EzReflect.cacheEnabled) {
        val key = BestConstructorCacheKey(clz, parameterTypes.toList(), List(parameterTypes.size) { false })
        val cached = EzReflect.cache[key]
        if (cached is Constructor<*>) return cached
    }

    val exact = try {
        clz.getDeclaredConstructor(*parameterTypes).also { it.isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }
    if (exact != null) {
        if (EzReflect.cacheEnabled) {
            EzReflect.cache[BestConstructorCacheKey(clz, parameterTypes.toList(), List(parameterTypes.size) { false })] = exact
        }
        return exact
    }

    var best: Constructor<*>? = null
    var bestScore = Int.MAX_VALUE
    for (constructor in EzReflect.memberResolver.constructorsOf(clz)) {
        if (!paramTypesMatch(parameterTypes.toList().toTypedArray(), constructor.parameterTypes)) continue
        val score = scoreMatch(parameterTypes.toList().toTypedArray(), constructor.parameterTypes)
        if (score < bestScore) {
            constructor.isAccessible = true
            best = constructor
            bestScore = score
        }
    }

    return best?.also {
        if (EzReflect.cacheEnabled) {
            EzReflect.cache[BestConstructorCacheKey(clz, parameterTypes.toList(), List(parameterTypes.size) { false })] = it
        }
    } ?: throw MemberNotFoundException(
        memberType = MemberType.CONSTRUCTOR,
        targetClass = clz.name,
        searchedSuper = false,
        conditionDesc = "bestMatch argTypes=${parameterTypes.map { it.simpleName }}",
        candidates = emptyList(),
    )
}

/**
 * 按实参数值推断最合适的构造器。
 *
 * 与参数类型重载相比，此版本会根据运行时参数类型自动推断，`null` 参数会参与模糊匹配。
 *
 * @param clz 目标类
 * @param args 用于推断签名的运行时实参
 */
fun findConstructorBestMatch(
    clz: Class<*>,
    vararg args: Any?,
): Constructor<*> {
    val actual = inferBestMatchArgs(args)
    if (EzReflect.cacheEnabled) {
        val key = BestConstructorCacheKey(
            clz,
            actual.map { it.type ?: Any::class.java },
            actual.map { it.isNull },
        )
        val cached = EzReflect.cache[key]
        if (cached is Constructor<*>) return cached
    }

    var best: Constructor<*>? = null
    var bestScore = Int.MAX_VALUE
    for (constructor in EzReflect.memberResolver.constructorsOf(clz)) {
        if (!paramTypesMatch(actual, constructor.parameterTypes)) continue
        val score = scoreMatch(actual, constructor.parameterTypes)
        if (score < bestScore) {
            constructor.isAccessible = true
            best = constructor
            bestScore = score
        }
    }

    return best?.also {
        if (EzReflect.cacheEnabled) {
            EzReflect.cache[BestConstructorCacheKey(clz, actual.map { it.type ?: Any::class.java }, actual.map { it.isNull })] = it
        }
    } ?: throw MemberNotFoundException(
        memberType = MemberType.CONSTRUCTOR,
        targetClass = clz.name,
        searchedSuper = false,
        conditionDesc = "bestMatch args=${args.map { it?.javaClass?.simpleName ?: "null" }}",
        candidates = emptyList(),
    )
}
