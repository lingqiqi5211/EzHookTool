@file:JvmName("ClassUtils")

package io.github.lingqiqi5211.ezhooktool.core

// ═══════════════════════ 基础加载 ═══════════════════════

/**
 * 加载类。找不到时抛出 [ClassNotFoundError]。
 *
 * ```kotlin
 * val clz = loadClass("com.example.Target")
 * ```
 *
 * @param name 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 */
fun loadClass(name: String, classLoader: ClassLoader = EzReflect.classLoader): Class<*> =
    loadClassOrNull(name, classLoader)
        ?: throw ClassNotFoundError(name, classLoader.toString())

/**
 * 加载类。找不到时返回 null。
 *
 * ```kotlin
 * val clz = loadClassOrNull("com.example.Target")
 * ```
 *
 * @param name 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 */
fun loadClassOrNull(name: String, classLoader: ClassLoader = EzReflect.classLoader): Class<*>? =
    try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
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
 * @param names 按优先顺序尝试的候选类名
 * @param classLoader 用于加载候选类的 `ClassLoader`
 * @throws ClassNotFoundError 所有名称都找不到时抛出
 */
fun loadClassFirst(vararg names: String, classLoader: ClassLoader = EzReflect.classLoader): Class<*> =
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
fun loadClassFirstOrNull(vararg names: String, classLoader: ClassLoader = EzReflect.classLoader): Class<*>? {
    for (name in names) {
        val clz = loadClassOrNull(name, classLoader)
        if (clz != null) return clz
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
fun loadClasses(vararg names: String, classLoader: ClassLoader = EzReflect.classLoader): List<Class<*>> =
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
fun String.toClass(classLoader: ClassLoader = EzReflect.classLoader): Class<*> =
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
fun String.toClassOrNull(classLoader: ClassLoader = EzReflect.classLoader): Class<*>? =
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
fun Class<*>.isSubclassOf(parentName: String, classLoader: ClassLoader = EzReflect.classLoader): Boolean {
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
fun ClassLoader.hasClass(name: String): Boolean = loadClassOrNull(name, this) != null
