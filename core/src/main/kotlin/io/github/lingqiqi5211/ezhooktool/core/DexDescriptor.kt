@file:JvmName("DexDescriptorUtils")

package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Internal Dex/Smali signature parser.
 *
 * Method format: `Lcom/example/Foo;->doTask(Ljava/lang/String;I)V`
 * Field format:  `Lcom/example/Foo;->name:Ljava/lang/String;`
 */
internal object DexDescriptor {

    /**
     * Parse a Dex type descriptor to a Class.
     *
     * - `V` → void, `Z` → boolean, `B` → byte, `C` → char, `S` → short
     * - `I` → int, `J` → long, `F` → float, `D` → double
     * - `Ljava/lang/String;` → String
     * - `[I` → int[]
     *
     * @param desc Dex 类型描述符
     * @param classLoader 用于解析对象类型的 `ClassLoader`
     */
    fun parseType(desc: String, classLoader: ClassLoader): Class<*> {
        if (desc.isEmpty()) throw IllegalArgumentException("Empty type descriptor")
        return when (desc[0]) {
            'V' -> Void.TYPE
            'Z' -> Boolean::class.javaPrimitiveType!!
            'B' -> Byte::class.javaPrimitiveType!!
            'C' -> Char::class.javaPrimitiveType!!
            'S' -> Short::class.javaPrimitiveType!!
            'I' -> Int::class.javaPrimitiveType!!
            'J' -> Long::class.javaPrimitiveType!!
            'F' -> Float::class.javaPrimitiveType!!
            'D' -> Double::class.javaPrimitiveType!!
            'L' -> {
                // Lcom/example/Foo; → com.example.Foo
                val end = desc.indexOf(';')
                if (end == -1) throw IllegalArgumentException("Invalid object type descriptor: $desc")
                val className = desc.substring(1, end).replace('/', '.')
                loadClass(className, classLoader)
            }

            '[' -> {
                // Array: recursively resolve component type
                val componentType = parseType(desc.substring(1), classLoader)
                java.lang.reflect.Array.newInstance(componentType, 0).javaClass
            }

            else -> throw IllegalArgumentException("Unknown type descriptor: $desc")
        }
    }

    /**
     * Parse parameter types from a method descriptor's parameter section.
     * Input example: `Ljava/lang/String;I` (between `(` and `)`)
     *
     * @param paramDesc 方法描述符中 `(` 与 `)` 之间的参数片段
     * @param classLoader 用于解析对象类型的 `ClassLoader`
     */
    fun parseParamTypes(paramDesc: String, classLoader: ClassLoader): Array<Class<*>> {
        if (paramDesc.isEmpty()) return emptyArray()
        val types = mutableListOf<Class<*>>()
        var i = 0
        while (i < paramDesc.length) {
            when (paramDesc[i]) {
                'V', 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> {
                    types.add(parseType(paramDesc[i].toString(), classLoader))
                    i++
                }

                'L' -> {
                    val end = paramDesc.indexOf(';', i)
                    if (end == -1) throw IllegalArgumentException("Invalid param descriptor at pos $i: $paramDesc")
                    types.add(parseType(paramDesc.substring(i, end + 1), classLoader))
                    i = end + 1
                }

                '[' -> {
                    // Find the end of the array type
                    var arrayEnd = i + 1
                    while (arrayEnd < paramDesc.length && paramDesc[arrayEnd] == '[') arrayEnd++
                    if (arrayEnd >= paramDesc.length) throw IllegalArgumentException("Invalid array descriptor at pos $i: $paramDesc")
                    val componentEnd = if (paramDesc[arrayEnd] == 'L') {
                        val semi = paramDesc.indexOf(';', arrayEnd)
                        if (semi == -1) throw IllegalArgumentException("Invalid array object descriptor at pos $i: $paramDesc")
                        semi + 1
                    } else {
                        arrayEnd + 1
                    }
                    types.add(parseType(paramDesc.substring(i, componentEnd), classLoader))
                    i = componentEnd
                }

                else -> throw IllegalArgumentException("Unknown char '${paramDesc[i]}' at pos $i in: $paramDesc")
            }
        }
        return types.toTypedArray()
    }

    data class MethodDesc(
        val className: String,
        val methodName: String,
        val paramTypes: Array<Class<*>>,
        val returnType: Class<*>,
    )

    data class FieldDesc(
        val className: String,
        val fieldName: String,
        val fieldType: Class<*>,
    )

    /**
     * Parse: `Lcom/example/Foo;->doTask(Ljava/lang/String;I)V`
     *
     * @param desc 完整方法 Dex/Smali 描述符
     * @param classLoader 用于解析类名和参数类型的 `ClassLoader`
     */
    fun parseMethodDesc(desc: String, classLoader: ClassLoader): MethodDesc {
        val arrowIdx = desc.indexOf("->")
        if (arrowIdx == -1) throw IllegalArgumentException("Invalid method descriptor (no '->'): $desc")

        val classDesc = desc.substring(0, arrowIdx)
        if (!classDesc.startsWith("L") || !classDesc.endsWith(";"))
            throw IllegalArgumentException("Invalid class in descriptor: $classDesc")
        val className = classDesc.substring(1, classDesc.length - 1).replace('/', '.')

        val rest = desc.substring(arrowIdx + 2)
        val parenOpen = rest.indexOf('(')
        val parenClose = rest.indexOf(')')
        if (parenOpen == -1 || parenClose == -1)
            throw IllegalArgumentException("Invalid method descriptor (no parentheses): $desc")

        val methodName = rest.substring(0, parenOpen)
        val paramSection = rest.substring(parenOpen + 1, parenClose)
        val returnSection = rest.substring(parenClose + 1)

        val paramTypes = parseParamTypes(paramSection, classLoader)
        val returnType = parseType(returnSection, classLoader)

        return MethodDesc(className, methodName, paramTypes, returnType)
    }

    /**
     * Parse: `Lcom/example/Foo;->name:Ljava/lang/String;`
     *
     * @param desc 完整字段 Dex/Smali 描述符
     * @param classLoader 用于解析类名和字段类型的 `ClassLoader`
     */
    fun parseFieldDesc(desc: String, classLoader: ClassLoader): FieldDesc {
        val arrowIdx = desc.indexOf("->")
        if (arrowIdx == -1) throw IllegalArgumentException("Invalid field descriptor (no '->'): $desc")

        val classDesc = desc.substring(0, arrowIdx)
        if (!classDesc.startsWith("L") || !classDesc.endsWith(";"))
            throw IllegalArgumentException("Invalid class in descriptor: $classDesc")
        val className = classDesc.substring(1, classDesc.length - 1).replace('/', '.')

        val rest = desc.substring(arrowIdx + 2)
        val colonIdx = rest.indexOf(':')
        if (colonIdx == -1) throw IllegalArgumentException("Invalid field descriptor (no ':'): $desc")

        val fieldName = rest.substring(0, colonIdx)
        val typeSection = rest.substring(colonIdx + 1)
        val fieldType = parseType(typeSection, classLoader)

        return FieldDesc(className, fieldName, fieldType)
    }
}

// ═══════════════════════ Public API ═══════════════════════

/**
 * 通过 Dex/Smali 签名获取方法。
 *
 * ```kotlin
 * val m = getMethodByDesc("Lcom/example/Foo;->doTask(Ljava/lang/String;I)V")
 * ```
 *
 * 签名格式: `L<class>;-><method>(<param_types>)<return_type>`
 *
 * @param desc 方法的 Dex/Smali 描述符
 * @param classLoader 用于解析描述符和加载目标类的 `ClassLoader`
 */
fun getMethodByDesc(desc: String, classLoader: ClassLoader = EzReflect.classLoader): Method {
    return getMethodByDescOrNull(desc, classLoader)
        ?: throw MemberNotFoundException(
            memberType = MemberType.METHOD,
            targetClass = desc,
            searchedSuper = false,
            conditionDesc = "Dex descriptor: $desc"
        )
}

/**
 * 通过 Dex/Smali 签名获取方法，找不到返回 null。
 *
 * @param desc 方法的 Dex/Smali 描述符
 * @param classLoader 用于解析描述符和加载目标类的 `ClassLoader`
 */
fun getMethodByDescOrNull(desc: String, classLoader: ClassLoader = EzReflect.classLoader): Method? {
    return try {
        val parsed = DexDescriptor.parseMethodDesc(desc, classLoader)
        val clz = loadClass(parsed.className, classLoader)
        val method = clz.getDeclaredMethod(parsed.methodName, *parsed.paramTypes)
        method.isAccessible = true
        method
    } catch (_: Throwable) {
        null
    }
}

/**
 * 通过 Dex/Smali 签名获取字段。
 *
 * ```kotlin
 * val f = getFieldByDesc("Lcom/example/Foo;->name:Ljava/lang/String;")
 * ```
 *
 * 签名格式: `L<class>;-><field>:<type>`
 *
 * @param desc 字段的 Dex/Smali 描述符
 * @param classLoader 用于解析描述符和加载目标类的 `ClassLoader`
 */
fun getFieldByDesc(desc: String, classLoader: ClassLoader = EzReflect.classLoader): Field {
    return getFieldByDescOrNull(desc, classLoader)
        ?: throw MemberNotFoundException(
            memberType = MemberType.FIELD,
            targetClass = desc,
            searchedSuper = false,
            conditionDesc = "Dex descriptor: $desc"
        )
}

/**
 * 通过 Dex/Smali 签名获取字段，找不到返回 null。
 *
 * @param desc 字段的 Dex/Smali 描述符
 * @param classLoader 用于解析描述符和加载目标类的 `ClassLoader`
 */
fun getFieldByDescOrNull(desc: String, classLoader: ClassLoader = EzReflect.classLoader): Field? {
    return try {
        val parsed = DexDescriptor.parseFieldDesc(desc, classLoader)
        val clz = loadClass(parsed.className, classLoader)
        val field = clz.getDeclaredField(parsed.fieldName)
        field.isAccessible = true
        field
    } catch (_: Throwable) {
        null
    }
}

// ═══════════════════════ ClassLoader 扩展 ═══════════════════════

/**
 * ClassLoader 扩展：通过 Dex 签名获取方法。
 *
 * @param desc 方法的 Dex/Smali 描述符
 */
fun ClassLoader.getMethodByDesc(desc: String): Method = getMethodByDesc(desc, this)

/**
 * ClassLoader 扩展：通过 Dex 签名获取方法，找不到返回 null。
 *
 * @param desc 方法的 Dex/Smali 描述符
 */
fun ClassLoader.getMethodByDescOrNull(desc: String): Method? = getMethodByDescOrNull(desc, this)

/**
 * ClassLoader 扩展：通过 Dex 签名获取字段。
 *
 * @param desc 字段的 Dex/Smali 描述符
 */
fun ClassLoader.getFieldByDesc(desc: String): Field = getFieldByDesc(desc, this)

/**
 * ClassLoader 扩展：通过 Dex 签名获取字段，找不到返回 null。
 *
 * @param desc 字段的 Dex/Smali 描述符
 */
fun ClassLoader.getFieldByDescOrNull(desc: String): Field? = getFieldByDescOrNull(desc, this)
