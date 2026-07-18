package io.github.lingqiqi5211.ezhooktool.core.query

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

/**
 * 泛型条件匹配器，用于 [MethodQuery.genericParameterTypes] / [MethodQuery.genericReturnType] /
 * [ConstructorQuery.genericParameterTypes]。
 *
 * `Method.parameterTypes` / `Constructor.parameterTypes` 拿到的是泛型擦除后的 `Class`；
 * `genericParameterTypes` / `genericReturnType` 才保留类型变量名、参数化类型的 raw type 等信息，
 * 可以用来区分桥接方法（bridge method，参数已擦除为具体类型）和真正声明的泛型方法
 * （参数仍是 [TypeVariable]）。
 */
class GenericTypeMatcher internal constructor(
    private val description: String,
    private val predicate: (Type) -> Boolean,
) {
    internal fun matches(type: Type): Boolean = predicate(type)

    override fun toString(): String = description

    companion object {
        /**
         * 匹配类型变量名，例如类或方法声明的 `<T>`，且该位置的参数/返回值仍是同名类型变量。
         *
         * 只对未被擦除的声明方法命中；桥接方法此处会是具体 `Class`，不会匹配。
         */
        @JvmStatic
        fun typeVariableNamed(name: String): GenericTypeMatcher =
            GenericTypeMatcher("typeVar($name)") { type ->
                type is TypeVariable<*> && type.name == name
            }

        /** 匹配参数化类型的 raw type，例如 `List<T>`、`Map<String, T>`，忽略具体类型参数。 */
        @JvmStatic
        fun rawType(rawType: Class<*>): GenericTypeMatcher =
            GenericTypeMatcher("rawType(${rawType.name})") { type ->
                type is ParameterizedType && type.rawType == rawType
            }

        /** 精确匹配 [Type]，包括 `Class`、[ParameterizedType]、[TypeVariable] 等。 */
        @JvmStatic
        fun exact(expected: Type): GenericTypeMatcher =
            GenericTypeMatcher("exact($expected)") { type -> type == expected }
    }
}
