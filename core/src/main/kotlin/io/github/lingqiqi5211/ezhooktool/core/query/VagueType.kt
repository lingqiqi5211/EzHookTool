package io.github.lingqiqi5211.ezhooktool.core.query

/**
 * 参数列表中的模糊占位符，用于 [MethodQuery.parameterTypesVague] / [ConstructorQuery.parameterTypesVague]。
 *
 * 表示"这一位接受任意类型"，但参数数量仍然固定——不代表可变参数，也不会跳过整段参数。
 *
 * ```kotlin
 * clazz.findMethod {
 *     name("bind")
 *     parameterTypesVague(String::class.java, VagueType, Boolean::class.javaObjectType)
 * }
 * ```
 */
object VagueType
