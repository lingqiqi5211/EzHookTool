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

    private data class ParsedType(val type: Class<*>, val endIndex: Int)

    private fun parseTypeAt(
        desc: String,
        startIndex: Int,
        classLoader: ClassLoader,
        allowVoid: Boolean,
    ): ParsedType {
        if (startIndex >= desc.length) throw IllegalArgumentException("Empty type descriptor")
        val primitive = when (desc[startIndex]) {
            'V' -> {
                if (!allowVoid) throw IllegalArgumentException("void is not valid here: $desc")
                Void.TYPE
            }
            'Z' -> Boolean::class.javaPrimitiveType!!
            'B' -> Byte::class.javaPrimitiveType!!
            'C' -> Char::class.javaPrimitiveType!!
            'S' -> Short::class.javaPrimitiveType!!
            'I' -> Int::class.javaPrimitiveType!!
            'J' -> Long::class.javaPrimitiveType!!
            'F' -> Float::class.javaPrimitiveType!!
            'D' -> Double::class.javaPrimitiveType!!
            else -> null
        }
        if (primitive != null) return ParsedType(primitive, startIndex + 1)

        return when (desc[startIndex]) {
            'L' -> {
                val end = desc.indexOf(';', startIndex + 1)
                if (end == -1) throw IllegalArgumentException("Invalid object type descriptor: $desc")
                val internalName = desc.substring(startIndex + 1, end)
                if (internalName.isEmpty()) throw IllegalArgumentException("Empty object type descriptor: $desc")
                ParsedType(loadClass(internalName.replace('/', '.'), classLoader), end + 1)
            }
            '[' -> {
                val component = parseTypeAt(desc, startIndex + 1, classLoader, allowVoid = false)
                ParsedType(java.lang.reflect.Array.newInstance(component.type, 0).javaClass, component.endIndex)
            }
            else -> throw IllegalArgumentException(
                "Unknown type descriptor '${desc[startIndex]}' at pos $startIndex in: $desc"
            )
        }
    }

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
        val parsed = parseTypeAt(desc, 0, classLoader, allowVoid = true)
        if (parsed.endIndex != desc.length) {
            throw IllegalArgumentException("Trailing content in type descriptor at pos ${parsed.endIndex}: $desc")
        }
        return parsed.type
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
            val parsed = parseTypeAt(paramDesc, i, classLoader, allowVoid = false)
            types += parsed.type
            i = parsed.endIndex
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
        if (parenOpen <= 0 || parenClose <= parenOpen || rest.indexOf('(', parenOpen + 1) != -1 ||
            rest.indexOf(')', parenClose + 1) != -1
        )
            throw IllegalArgumentException("Invalid method descriptor (no parentheses): $desc")

        val methodName = rest.substring(0, parenOpen)
        val paramSection = rest.substring(parenOpen + 1, parenClose)
        val returnSection = rest.substring(parenClose + 1)
        if (returnSection.isEmpty()) throw IllegalArgumentException("Missing return type in method descriptor: $desc")

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
        if (fieldName.isEmpty()) throw IllegalArgumentException("Missing field name in descriptor: $desc")
        if (typeSection.isEmpty()) throw IllegalArgumentException("Missing field type in descriptor: $desc")
        val fieldType = parseType(typeSection, classLoader)
        if (fieldType == Void.TYPE) throw IllegalArgumentException("Field type cannot be void: $desc")

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
 * 仅查找 `declaredMethods`；不会沿父类查找。这跟 `findMethod` 默认 smart 模式不同——
 * 因为 descriptor 已显式声明了 owner class。
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
 * 描述符格式错误会抛 [IllegalArgumentException]——这跟"目标不存在"是两类不同问题，
 * 应该尽早暴露而不是被吞成 null。
 *
 * @param desc 方法的 Dex/Smali 描述符
 * @param classLoader 用于解析描述符和加载目标类的 `ClassLoader`
 */
fun getMethodByDescOrNull(desc: String, classLoader: ClassLoader = EzReflect.classLoader): Method? {
    val parsed = try {
        DexDescriptor.parseMethodDesc(desc, classLoader)
    } catch (_: ClassNotFoundError) {
        return null
    } catch (_: ClassNotFoundException) {
        return null
    } catch (_: NoClassDefFoundError) {
        return null
    }
    return try {
        val clz = loadClass(parsed.className, classLoader)
        val method = clz.getDeclaredMethod(parsed.methodName, *parsed.paramTypes)
        if (method.returnType != parsed.returnType) return null
        method.isAccessible = true
        method
    } catch (_: NoSuchMethodException) {
        null
    } catch (_: ClassNotFoundError) {
        null
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoClassDefFoundError) {
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
 * 仅查找 `declaredFields`；不会沿父类查找。
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
 * 描述符格式错误会抛 [IllegalArgumentException]——这跟"目标不存在"是两类不同问题，
 * 应该尽早暴露而不是被吞成 null。
 *
 * @param desc 字段的 Dex/Smali 描述符
 * @param classLoader 用于解析描述符和加载目标类的 `ClassLoader`
 */
fun getFieldByDescOrNull(desc: String, classLoader: ClassLoader = EzReflect.classLoader): Field? {
    val parsed = try {
        DexDescriptor.parseFieldDesc(desc, classLoader)
    } catch (_: ClassNotFoundError) {
        return null
    } catch (_: ClassNotFoundException) {
        return null
    } catch (_: NoClassDefFoundError) {
        return null
    }
    return try {
        val clz = loadClass(parsed.className, classLoader)
        val field = clz.getDeclaredField(parsed.fieldName)
        if (field.type != parsed.fieldType) return null
        field.isAccessible = true
        field
    } catch (_: NoSuchFieldException) {
        null
    } catch (_: ClassNotFoundError) {
        null
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoClassDefFoundError) {
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
