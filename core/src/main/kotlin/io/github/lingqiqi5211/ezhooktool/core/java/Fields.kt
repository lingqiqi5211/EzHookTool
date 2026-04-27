package io.github.lingqiqi5211.ezhooktool.core.java

import io.github.lingqiqi5211.ezhooktool.core.MemberNotFoundException
import io.github.lingqiqi5211.ezhooktool.core.MemberType
import io.github.lingqiqi5211.ezhooktool.core.findAllFields
import io.github.lingqiqi5211.ezhooktool.core.findFieldOrNull
import io.github.lingqiqi5211.ezhooktool.core.getField
import io.github.lingqiqi5211.ezhooktool.core.getStaticField
import io.github.lingqiqi5211.ezhooktool.core.putField
import io.github.lingqiqi5211.ezhooktool.core.putStaticField
import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import java.lang.reflect.Field
import java.util.function.Predicate

/**
 * 供 Java 调用的字段查找和读写入口。
 */
object Fields {
    /**
     * 创建字段查询器。
     *
     * 默认先查当前类，找不到再查父类。
     *
     * @param clazz 目标类
     */
    @JvmStatic
    fun find(clazz: Class<*>): FieldSearch = FieldSearch(clazz)

    /**
     * 创建字段查询器，并指定父类查找策略。
     *
     * @param clazz 目标类
     * @param findSuper `null` 表示智能查找，`false` 只查当前类，`true` 查当前类和全部父类
     */
    @JvmStatic
    fun find(clazz: Class<*>, findSuper: Boolean?): FieldSearch = FieldSearch(clazz, findSuper)

    /** 读取实例字段。 */
    @JvmStatic
    fun getObjectField(obj: Any, fieldName: String): Any? = obj.getField(fieldName)

    /** 写入实例字段。 */
    @JvmStatic
    fun setObjectField(obj: Any, fieldName: String, value: Any?) = obj.putField(fieldName, value)

    /** 读取静态字段。 */
    @JvmStatic
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? = clazz.getStaticField(fieldName)

    /** 写入静态字段。 */
    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) = clazz.putStaticField(fieldName, value)

    @JvmStatic
    fun getBooleanField(obj: Any, fieldName: String): Boolean = getObjectField(obj, fieldName) as Boolean

    @JvmStatic
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getIntField(obj: Any, fieldName: String): Int = getObjectField(obj, fieldName) as Int

    @JvmStatic
    fun setIntField(obj: Any, fieldName: String, value: Int) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getLongField(obj: Any, fieldName: String): Long = getObjectField(obj, fieldName) as Long

    @JvmStatic
    fun setLongField(obj: Any, fieldName: String, value: Long) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getFloatField(obj: Any, fieldName: String): Float = getObjectField(obj, fieldName) as Float

    @JvmStatic
    fun setFloatField(obj: Any, fieldName: String, value: Float) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getDoubleField(obj: Any, fieldName: String): Double = getObjectField(obj, fieldName) as Double

    @JvmStatic
    fun setDoubleField(obj: Any, fieldName: String, value: Double) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getByteField(obj: Any, fieldName: String): Byte = getObjectField(obj, fieldName) as Byte

    @JvmStatic
    fun setByteField(obj: Any, fieldName: String, value: Byte) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getShortField(obj: Any, fieldName: String): Short = getObjectField(obj, fieldName) as Short

    @JvmStatic
    fun setShortField(obj: Any, fieldName: String, value: Short) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getCharField(obj: Any, fieldName: String): Char = getObjectField(obj, fieldName) as Char

    @JvmStatic
    fun setCharField(obj: Any, fieldName: String, value: Char) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun getStaticBooleanField(clazz: Class<*>, fieldName: String): Boolean = getStaticObjectField(clazz, fieldName) as Boolean

    @JvmStatic
    fun setStaticBooleanField(clazz: Class<*>, fieldName: String, value: Boolean) =
        setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticIntField(clazz: Class<*>, fieldName: String): Int = getStaticObjectField(clazz, fieldName) as Int

    @JvmStatic
    fun setStaticIntField(clazz: Class<*>, fieldName: String, value: Int) = setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticLongField(clazz: Class<*>, fieldName: String): Long = getStaticObjectField(clazz, fieldName) as Long

    @JvmStatic
    fun setStaticLongField(clazz: Class<*>, fieldName: String, value: Long) = setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticFloatField(clazz: Class<*>, fieldName: String): Float = getStaticObjectField(clazz, fieldName) as Float

    @JvmStatic
    fun setStaticFloatField(clazz: Class<*>, fieldName: String, value: Float) = setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticDoubleField(clazz: Class<*>, fieldName: String): Double = getStaticObjectField(clazz, fieldName) as Double

    @JvmStatic
    fun setStaticDoubleField(clazz: Class<*>, fieldName: String, value: Double) =
        setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticByteField(clazz: Class<*>, fieldName: String): Byte = getStaticObjectField(clazz, fieldName) as Byte

    @JvmStatic
    fun setStaticByteField(clazz: Class<*>, fieldName: String, value: Byte) = setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticShortField(clazz: Class<*>, fieldName: String): Short = getStaticObjectField(clazz, fieldName) as Short

    @JvmStatic
    fun setStaticShortField(clazz: Class<*>, fieldName: String, value: Short) =
        setStaticObjectField(clazz, fieldName, value)

    @JvmStatic
    fun getStaticCharField(clazz: Class<*>, fieldName: String): Char = getStaticObjectField(clazz, fieldName) as Char

    @JvmStatic
    fun setStaticCharField(clazz: Class<*>, fieldName: String, value: Char) = setStaticObjectField(clazz, fieldName, value)
}

/**
 * 供 Java 链式组合字段查询条件。
 *
 * `first()` 返回第一个匹配字段，`toList()` 返回全部匹配字段。
 */
class FieldSearch internal constructor(
    private val clazz: Class<*>,
    private var findSuper: Boolean? = null,
) {
    private val conditions = mutableListOf<FieldQuery.() -> Unit>()

    private fun add(condition: FieldQuery.() -> Unit): FieldSearch = apply {
        conditions += condition
    }

    /** 限定字段名。 */
    fun filterByName(value: String): FieldSearch = add { name(value) }

    /** [filterByName] 的短名称。 */
    fun name(value: String): FieldSearch = filterByName(value)

    /** 限定字段名包含指定文本。 */
    @JvmOverloads
    fun filterByNameContains(value: String, ignoreCase: Boolean = false): FieldSearch =
        add { nameContains(value, ignoreCase) }

    /** 限定字段名以指定文本开头。 */
    @JvmOverloads
    fun filterByNameStartsWith(value: String, ignoreCase: Boolean = false): FieldSearch =
        add { nameStartsWith(value, ignoreCase) }

    /** 限定字段名以指定文本结尾。 */
    @JvmOverloads
    fun filterByNameEndsWith(value: String, ignoreCase: Boolean = false): FieldSearch =
        add { nameEndsWith(value, ignoreCase) }

    /** 限定字段类型。 */
    fun filterByType(value: Class<*>): FieldSearch = add { type(value) }

    /** [filterByType] 的短名称。 */
    fun type(value: Class<*>): FieldSearch = filterByType(value)

    /** 限定字段类型是 [value] 本身或子类。 */
    fun filterByTypeExtendsFrom(value: Class<*>): FieldSearch = add { typeExtendsFrom(value) }

    /** 限定为 static 字段。 */
    fun filterStatic(): FieldSearch = add { isStatic() }

    /** 限定为非 static 字段。 */
    fun filterNonStatic(): FieldSearch = add { notStatic() }

    /** 限定是否为 static 字段。 */
    fun isStatic(value: Boolean): FieldSearch = add { isStatic(value) }

    /** 限定为 static 字段。 */
    fun isStatic(): FieldSearch = filterStatic()

    /** 限定为非 static 字段。 */
    fun notStatic(): FieldSearch = filterNonStatic()

    /** 限定为 public 字段。 */
    fun filterPublic(): FieldSearch = add { isPublic() }

    /** 限定为非 public 字段。 */
    fun filterNonPublic(): FieldSearch = add { notPublic() }

    /** 限定为 private 字段。 */
    fun filterPrivate(): FieldSearch = add { isPrivate() }

    /** 限定为非 private 字段。 */
    fun filterNonPrivate(): FieldSearch = add { notPrivate() }

    /** 限定为 protected 字段。 */
    fun filterProtected(): FieldSearch = add { isProtected() }

    /** 限定为非 protected 字段。 */
    fun filterNonProtected(): FieldSearch = add { notProtected() }

    /** 限定为 final 字段。 */
    fun filterFinal(): FieldSearch = add { isFinal() }

    /** 限定为非 final 字段。 */
    fun filterNonFinal(): FieldSearch = add { notFinal() }

    /** 限定为 volatile 字段。 */
    fun filterVolatile(): FieldSearch = add { isVolatile() }

    /** 限定为非 volatile 字段。 */
    fun filterNonVolatile(): FieldSearch = add { notVolatile() }

    /** 限定为 transient 字段。 */
    fun filterTransient(): FieldSearch = add { isTransient() }

    /** 限定为非 transient 字段。 */
    fun filterNonTransient(): FieldSearch = add { notTransient() }

    /** 限定为 enum 常量字段。 */
    fun filterEnumConstant(): FieldSearch = add { isEnumConstant() }

    /** 限定为非 enum 常量字段。 */
    fun filterNonEnumConstant(): FieldSearch = add { notEnumConstant() }

    /** 限定为 synthetic 字段。 */
    fun filterSynthetic(): FieldSearch = add { isSynthetic() }

    /** 限定为非 synthetic 字段。 */
    fun filterNonSynthetic(): FieldSearch = add { notSynthetic() }

    /** 只在当前类中查找。 */
    fun currentClassOnly(): FieldSearch = apply {
        findSuper = false
    }

    /** 查找当前类和全部父类。 */
    fun includeSuper(): FieldSearch = apply {
        findSuper = true
    }

    /** 添加自定义条件。 */
    fun filter(predicate: Predicate<Field>): FieldSearch = add { filter(predicate) }

    /** 返回第一个匹配的字段，找不到时抛出异常。 */
    fun first(): Field = firstOrNull()
        ?: throw MemberNotFoundException(MemberType.FIELD, clazz.name, findSuper != false)

    /** 返回第一个匹配的字段，找不到时返回 `null`。 */
    fun firstOrNull(): Field? {
        val query = conditions.toList()
        return findFieldOrNull(clazz, findSuper) {
            query.forEach { it() }
        }
    }

    /** 返回全部匹配的字段。 */
    fun toList(): List<Field> {
        val query = conditions.toList()
        return findAllFields(clazz, findSuper) {
            query.forEach { it() }
        }
    }
}
