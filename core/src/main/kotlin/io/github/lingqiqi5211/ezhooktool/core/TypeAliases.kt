@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.github.lingqiqi5211.ezhooktool.core

/**
 * Java 包装类型别名。
 *
 * 在 Kotlin 中，`Int::class.java` 得到的是 primitive `int.class`，
 * 而非 `java.lang.Integer.class`。在反射场景下经常需要包装类型。
 *
 * ```kotlin
 * findMethod(clz) {
 *     parameterTypes contentEquals arrayOf(JInteger::class.java, JBoolean::class.java)
 * }
 * ```
 */

typealias JBoolean = java.lang.Boolean

typealias JByte = java.lang.Byte

typealias JChar = java.lang.Character

typealias JShort = java.lang.Short

typealias JInteger = java.lang.Integer

typealias JLong = java.lang.Long

typealias JFloat = java.lang.Float

typealias JDouble = java.lang.Double

typealias JVoid = java.lang.Void

typealias JString = java.lang.String
