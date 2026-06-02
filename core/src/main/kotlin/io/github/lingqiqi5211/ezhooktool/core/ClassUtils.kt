@file:JvmName("ClassUtils")

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.ClassQuery
import io.github.lingqiqi5211.ezhooktool.core.query.QueryFilterContext
import io.github.lingqiqi5211.ezhooktool.core.query.classQuery
import kotlin.reflect.KProperty

private data class ClassNameCacheKey(val name: String)

private data class FirstClassNameCacheKey(val names: List<String>)

private data class ClassIfCacheKey(
    val queryKey: List<Any>,
    val resultMode: String,
)

private fun Throwable.isRecoverableClassLoadError(): Boolean = when (this) {
    is ClassNotFoundException,
    is NoClassDefFoundError,
    is TypeNotPresentException,
    is LinkageError -> true
    else -> false
}

private fun String.withLastDotAsDollar(): String? {
    val index = lastIndexOf('.')
    return if (index >= 0) replaceRange(index, index + 1, "\$") else null
}

private fun tryLoadClass(name: String, classLoader: ClassLoader): Class<*>? =
    try {
        Class.forName(name, false, classLoader)
    } catch (throwable: Throwable) {
        if (!throwable.isRecoverableClassLoadError()) throw throwable
        null
    }

// ═══════════════════════ 延迟加载 ═══════════════════════

/**
 * 延迟加载类。
 *
 * 适合目标类只在特定流程中才需要使用的场景。
 * 类加载仍使用 [loadClassFirstOrNull]，会复用 EzReflect 的缓存和内部类名兜底规则。
 */
class LazyClass internal constructor(
    private val names: List<String>,
    private val classLoaderProvider: () -> ClassLoader,
) {
    /** 加载并返回类，找不到时抛出 [ClassNotFoundError]。 */
    fun resolve(): Class<*> =
        loadClassFirst(
            *names.toTypedArray(),
            classLoader = classLoaderProvider(),
        )

    /** 加载并返回类，找不到时返回 null。 */
    fun resolveOrNull(): Class<*>? =
        loadClassFirstOrNull(
            *names.toTypedArray(),
            classLoader = classLoaderProvider(),
        )

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Class<*> = resolve()
}

/**
 * 可空的延迟加载类。
 */
class NullableLazyClass internal constructor(
    private val delegate: LazyClass,
) {
    fun resolveOrNull(): Class<*>? = delegate.resolveOrNull()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Class<*>? = resolveOrNull()
}

/**
 * 创建延迟加载类。默认使用当前 [EzReflect.classLoader]。
 */
fun lazyClass(vararg names: String): LazyClass =
    LazyClass(names.toList(), { EzReflect.classLoader })

/**
 * 创建延迟加载类，使用指定 [ClassLoader]。
 */
fun lazyClass(
    classLoader: ClassLoader,
    vararg names: String,
): LazyClass =
    LazyClass(names.toList(), { classLoader })

/**
 * 创建延迟加载类，找不到时返回 null。
 */
fun lazyClassOrNull(vararg names: String): NullableLazyClass =
    NullableLazyClass(lazyClass(*names))

/**
 * 创建延迟加载类，使用指定 [ClassLoader]，找不到时返回 null。
 */
fun lazyClassOrNull(
    classLoader: ClassLoader,
    vararg names: String,
): NullableLazyClass =
    NullableLazyClass(lazyClass(classLoader, *names))

// ═══════════════════════ 基础加载 ═══════════════════════

/**
 * 加载类。找不到时抛出 [ClassNotFoundError]。
 *
 * ```kotlin
 * val clz = loadClass("com.example.Target")
 * ```
 *
 * 同样支持 `com.example.Outer.Inner` 点号写法的嵌套类兑底，详见 [loadClassOrNull]。
 *
 * @param name 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 */
@JvmOverloads
fun loadClass(
    name: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): Class<*> =
    loadClassOrNull(name, classLoader)
        ?: throw ClassNotFoundError(name, classLoader.toString())

/**
 * 加载类。找不到时返回 null。
 *
 * ```kotlin
 * val clz = loadClassOrNull("com.example.Target")
 * ```
 *
 * 当 [name] 按字面无法加载时，会自动把最后一个 `.` 替换为 `$` 再尝试一次，
 * 以便支持 `com.example.Outer.Inner` 这种「点号写法」直接命中嵌套类。
 * 已经写成标准 binary name（`com.example.Outer$Inner`）的不会受影响。
 *
 * @param name 目标类名，支持 `Outer$Inner` 与 `Outer.Inner` 两种嵌套类写法
 * @param classLoader 用于加载目标类的 `ClassLoader`
 */
@JvmOverloads
fun loadClassOrNull(
    name: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): Class<*>? {
    val key = ClassNameCacheKey(name)
    EzReflect.classCacheGet(classLoader, key)?.let { return it }

    val clz = tryLoadClass(name, classLoader)
        ?: name.withLastDotAsDollar()?.let { tryLoadClass(it, classLoader) }

    if (clz != null) {
        EzReflect.classCachePut(classLoader, key, clz)
    }
    return clz
}

private fun findClassesMatching(
    classLoader: ClassLoader,
    query: ClassQuery,
    collectAll: Boolean,
): List<Class<*>> {
    val queryKey = query.cacheKeyOrNull()
    val resultMode = if (collectAll) "all" else "first"
    val cacheKey = queryKey?.let { ClassIfCacheKey(it, resultMode) }
    if (cacheKey != null) {
        val cached = EzReflect.classQueryCacheGet(classLoader, cacheKey)
        if (collectAll && cached is List<*>) {
            @Suppress("UNCHECKED_CAST")
            return cached as List<Class<*>>
        }
        if (!collectAll && cached is Class<*>) return listOf(cached)
    }

    val results = mutableListOf<Class<*>>()
    for (name in EzReflect.classResolver.classNamesOf(classLoader).distinct()) {
        if (!query.matchesName(name)) continue
        val clz = loadClassOrNull(name, classLoader) ?: continue
        if (!query.matchesClass(clz)) continue

        results += clz
        if (!collectAll && !query.requiresSingleResult) break
        if (!collectAll && results.size > 1) {
            throw SingleResultExpectedException(
                target = "class in $classLoader",
                conditionDesc = query.describe(),
            )
        }
    }

    if (cacheKey != null) {
        if (collectAll) {
            EzReflect.classQueryCachePut(classLoader, cacheKey, results.toList())
        } else {
            results.firstOrNull()?.let { EzReflect.classQueryCachePut(classLoader, cacheKey, it) }
        }
    }
    return results
}

/**
 * 按条件查找类，默认返回第一个匹配项。
 *
 * 查询块内调用 `findSingle()` 时，要求只能命中一个类。
 */
@JvmOverloads
fun findClassIf(
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ClassQuery.() -> Unit,
): Class<*> {
    QueryFilterContext.warnNestedFind("findClassIf")
    val builtQuery = classQuery(query)
    return findClassesMatching(classLoader, builtQuery, collectAll = false).firstOrNull()
        ?: throw ClassNotFoundError(
            className = builtQuery.describe() ?: "<condition>",
            classLoaderInfo = classLoader.toString(),
        )
}

/**
 * 按条件查找类，找不到时返回 null。
 */
@JvmOverloads
fun findClassIfOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ClassQuery.() -> Unit,
): Class<*>? {
    QueryFilterContext.warnNestedFind("findClassIfOrNull")
    return findClassesMatching(classLoader, classQuery(query), collectAll = false).firstOrNull()
}

/**
 * 按条件查找全部类。
 */
@JvmOverloads
fun findAllClassesIf(
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ClassQuery.() -> Unit,
): List<Class<*>> {
    QueryFilterContext.warnNestedFind("findAllClassesIf")
    return findClassesMatching(classLoader, classQuery(query), collectAll = true)
}

// ═══════════════════════ 多名称兜底 ═══════════════════════

/**
 * 依次尝试多个类名，返回第一个成功加载的类。
 * 适用于混淆后类名在不同版本间变化的场景。
 *
 * ```kotlin
 * val clz = loadClassFirst("com.example.Foo", "com.example.a", "c.d.e")
 * ```
 *
 * 每个候选名都会走 [loadClassOrNull] 的逻辑，同样支持 `Outer.Inner` 点号写法。
 *
 * @param names 按优先顺序尝试的候选类名
 * @param classLoader 用于加载候选类的 `ClassLoader`
 * @throws ClassNotFoundError 所有名称都找不到时抛出
 */
fun loadClassFirst(
    vararg names: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): Class<*> =
    loadClassFirstOrNull(*names, classLoader = classLoader)
        ?: throw ClassNotFoundError(
            className = names.firstOrNull() ?: "<empty>",
            classLoaderInfo = classLoader.toString(),
            triedNames = names.toList()
        )

/**
 * 同 [loadClassFirst]，全部找不到时返回 null。
 *
 * ```kotlin
 * val clz = loadClassFirstOrNull("com.example.Foo", "com.example.a")
 * ```
 *
 * @param names 按优先顺序尝试的候选类名
 * @param classLoader 用于加载候选类的 `ClassLoader`
 */
fun loadClassFirstOrNull(
    vararg names: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): Class<*>? {
    val key = FirstClassNameCacheKey(names.toList())
    EzReflect.classCacheGet(classLoader, key)?.let { return it }

    for (name in names) {
        val clz = loadClassOrNull(name, classLoader)
        if (clz != null) {
            EzReflect.classCachePut(classLoader, key, clz)
            return clz
        }
    }
    return null
}

// ═══════════════════════ 批量加载 ═══════════════════════

/**
 * 批量加载类。跳过找不到的，不抛异常。
 *
 * ```kotlin
 * val classes = loadClasses("com.example.A", "com.example.B", "not.exist.C")
 * // 结果: [A, B]，C 被跳过
 * ```
 *
 * @param names 要尝试加载的类名列表
 * @param classLoader 用于加载这些类的 `ClassLoader`
 */
fun loadClasses(
    vararg names: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): List<Class<*>> =
    names.mapNotNull { loadClassOrNull(it, classLoader) }

// ═══════════════════════ String 扩展 ═══════════════════════

/**
 * 字符串直接转 Class。
 *
 * ```kotlin
 * val clz = "com.example.Target".toClass()
 * ```
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 */
@JvmOverloads
fun String.toClass(
    classLoader: ClassLoader = EzReflect.classLoader,
): Class<*> =
    loadClass(this, classLoader)

/**
 * 字符串直接转 Class，找不到返回 null。
 *
 * ```kotlin
 * val clz = "com.example.Target".toClassOrNull()
 * ```
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 */
@JvmOverloads
fun String.toClassOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
): Class<*>? =
    loadClassOrNull(this, classLoader)

// ═══════════════════════ Class 工具扩展 ═══════════════════════

/**
 * 判断当前类是否是 [parent] 的子类（含相等）。
 *
 * ```kotlin
 * if (clz.isSubclassOf(Activity::class.java)) { ... }
 * ```
 *
 * @param parent 要比较的父类或接口
 */
fun Class<*>.isSubclassOf(parent: Class<*>): Boolean = parent.isAssignableFrom(this)

/**
 * 按类名判断是否为子类。
 *
 * ```kotlin
 * if (clz.isSubclassOf("android.app.Activity")) { ... }
 * ```
 *
 * @param parentName 父类或接口的类名
 * @param classLoader 用于加载 [parentName] 的 `ClassLoader`
 */
@JvmOverloads
fun Class<*>.isSubclassOf(
    parentName: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): Boolean {
    val parent = loadClassOrNull(parentName, classLoader) ?: return false
    return isSubclassOf(parent)
}

/**
 * ClassLoader 是否能加载指定类。
 *
 * ```kotlin
 * if (classLoader.hasClass("com.example.Foo")) { ... }
 * ```
 *
 * @param name 要检测的类名
 */
fun ClassLoader.hasClass(name: String): Boolean =
    loadClassOrNull(name, this) != null
