@file:Suppress("UNCHECKED_CAST")
@file:JvmName("FieldHelper")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.lingqiqi5211.ezhooktool.core.FieldCondition
import io.github.lingqiqi5211.ezhooktool.core.field
import io.github.lingqiqi5211.ezhooktool.core.fieldOrNull
import io.github.lingqiqi5211.ezhooktool.core.findAllFields
import io.github.lingqiqi5211.ezhooktool.core.findAllFieldsBy
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findFieldOrNull
import io.github.lingqiqi5211.ezhooktool.core.getField
import io.github.lingqiqi5211.ezhooktool.core.getFieldAs
import io.github.lingqiqi5211.ezhooktool.core.getFieldByType
import io.github.lingqiqi5211.ezhooktool.core.getFieldByTypeOrNull
import io.github.lingqiqi5211.ezhooktool.core.getFieldOrNull
import io.github.lingqiqi5211.ezhooktool.core.getFieldOrNullAs
import io.github.lingqiqi5211.ezhooktool.core.getStaticField
import io.github.lingqiqi5211.ezhooktool.core.getStaticFieldAs
import io.github.lingqiqi5211.ezhooktool.core.getStaticFieldOrNull
import io.github.lingqiqi5211.ezhooktool.core.getStaticFieldOrNullAs
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.core.putField
import io.github.lingqiqi5211.ezhooktool.core.putStaticField
import io.github.lingqiqi5211.ezhooktool.xposed.internal.AdditionalFields
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookClassLoader
import java.lang.reflect.Field

fun getValueByField(target: Any, fieldName: String, clazz: Class<*>? = null): Any? {
    val targetClass = clazz ?: target.javaClass
    return try {
        val field = targetClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(target)
    } catch (_: Throwable) {
        val superClass = targetClass.superclass ?: return null
        getValueByField(target, fieldName, superClass)
    }
}

fun String.findField(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    findSuper: Boolean? = null,
    condition: FieldCondition,
): Field = findField(this, classLoader, findSuper, condition)

fun String.findFieldOrNull(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    findSuper: Boolean? = null,
    condition: FieldCondition,
): Field? = findFieldOrNull(this, classLoader, findSuper, condition)

fun String.findAllFieldsBy(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    findSuper: Boolean? = null,
    condition: FieldCondition,
): List<Field> = findAllFieldsBy(this, classLoader, findSuper, condition)

fun String.findAllFields(
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    findSuper: Boolean? = null,
): List<Field> = findAllFields(this, classLoader, findSuper)

fun Class<*>.findFieldByType(type: Class<*>, findSuper: Boolean? = null): Field =
    findField(findSuper) { this.type == type }

fun Class<*>.findFieldByTypeOrNull(type: Class<*>, findSuper: Boolean? = null): Field? =
    findFieldOrNull(findSuper) { this.type == type }

fun Class<*>.findField(name: String, type: Class<*>? = null): Field =
    field(name, fieldType = type)

fun Class<*>.findFieldOrNull(name: String, type: Class<*>? = null): Field? =
    fieldOrNull(name, fieldType = type)

fun String.findField(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
): Field = loadClass(this, classLoader).findField(fieldName, type)

fun String.findFieldOrNull(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
): Field? = loadClass(this, classLoader).findFieldOrNull(fieldName, type)

fun Class<*>.findStaticField(name: String, type: Class<*>? = null): Field =
    staticFieldRef(name, type)

fun Class<*>.findStaticFieldOrNull(name: String, type: Class<*>? = null): Field? =
    staticFieldRefOrNull(name, type)

fun String.findStaticField(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
): Field = loadClass(this, classLoader).findStaticField(fieldName, type)

fun String.findStaticFieldOrNull(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
): Field? = loadClass(this, classLoader).findStaticFieldOrNull(fieldName, type)

fun Class<*>.findFieldByExactType(type: Class<*>, findSuper: Boolean? = null): Field =
    findFieldByType(type, findSuper)

fun Class<*>.findFirstFieldByExactType(type: Class<*>, findSuper: Boolean? = null): Field =
    findFieldByType(type, findSuper)

fun Class<*>.findFirstFieldByExactTypeOrNull(type: Class<*>, findSuper: Boolean? = null): Field? =
    findFieldByTypeOrNull(type, findSuper)

fun Any.getObjectField(fieldName: String): Any? =
    getField(fieldName)

fun <T> Any.getObjectFieldAs(fieldName: String): T =
    getObjectField(fieldName) as T

fun Any.setObjectField(fieldName: String, value: Any?) =
    putField(fieldName, value)

fun Any.getObjectFieldOrNull(fieldName: String): Any? =
    getFieldOrNull(fieldName)

fun <T> Any.getObjectFieldOrNullAs(fieldName: String): T? =
    getFieldOrNullAs(fieldName)

fun Any.getBooleanField(fieldName: String): Boolean = getObjectField(fieldName) as Boolean

fun Any.setBooleanField(fieldName: String, value: Boolean) = setObjectField(fieldName, value)

fun Any.getIntField(fieldName: String): Int = getObjectField(fieldName) as Int

fun Any.setIntField(fieldName: String, value: Int) = setObjectField(fieldName, value)

fun Any.getLongField(fieldName: String): Long = getObjectField(fieldName) as Long

fun Any.setLongField(fieldName: String, value: Long) = setObjectField(fieldName, value)

fun Any.getFloatField(fieldName: String): Float = getObjectField(fieldName) as Float

fun Any.setFloatField(fieldName: String, value: Float) = setObjectField(fieldName, value)

fun Any.getDoubleField(fieldName: String): Double = getObjectField(fieldName) as Double

fun Any.setDoubleField(fieldName: String, value: Double) = setObjectField(fieldName, value)

fun Any.getByteField(fieldName: String): Byte = getObjectField(fieldName) as Byte

fun Any.setByteField(fieldName: String, value: Byte) = setObjectField(fieldName, value)

fun Any.getShortField(fieldName: String): Short = getObjectField(fieldName) as Short

fun Any.setShortField(fieldName: String, value: Short) = setObjectField(fieldName, value)

fun Any.getCharField(fieldName: String): Char = getObjectField(fieldName) as Char

fun Any.setCharField(fieldName: String, value: Char) = setObjectField(fieldName, value)

fun Any.getBooleanFieldOrNull(fieldName: String): Boolean? = runCatching { getBooleanField(fieldName) }.getOrNull()

fun Any.getIntFieldOrNull(fieldName: String): Int? = runCatching { getIntField(fieldName) }.getOrNull()

fun Any.getLongFieldOrNull(fieldName: String): Long? = runCatching { getLongField(fieldName) }.getOrNull()

fun Any.getFloatFieldOrNull(fieldName: String): Float? = runCatching { getFloatField(fieldName) }.getOrNull()

fun Any.getDoubleFieldOrNull(fieldName: String): Double? = runCatching { getDoubleField(fieldName) }.getOrNull()

fun Any.getByteFieldOrNull(fieldName: String): Byte? = runCatching { getByteField(fieldName) }.getOrNull()

fun Any.getShortFieldOrNull(fieldName: String): Short? = runCatching { getShortField(fieldName) }.getOrNull()

fun Any.getCharFieldOrNull(fieldName: String): Char? = runCatching { getCharField(fieldName) }.getOrNull()

fun Any.getFirstFieldByExactType(type: Class<*>): Any? =
    javaClass.findFirstFieldByExactType(type).get(this)

fun <T> Any.getFirstFieldByExactTypeAs(type: Class<*>): T? =
    javaClass.findFirstFieldByExactType(type).get(this) as? T

fun Any.fieldRef(fieldName: String, type: Class<*>? = null): Field =
    field(fieldName, fieldType = type)

fun Any.fieldRefOrNull(fieldName: String, type: Class<*>? = null): Field? =
    fieldOrNull(fieldName, fieldType = type)

fun Any.fieldValue(fieldName: String, type: Class<*>? = null): Any? =
    getField(fieldName, type)

fun <T> Any.fieldValueAs(fieldName: String, type: Class<*>? = null): T? =
    getFieldAs(fieldName, type)

fun Any.fieldValueOrNull(fieldName: String, type: Class<*>? = null): Any? =
    getFieldOrNull(fieldName, type)

fun <T> Any.fieldValueOrNullAs(fieldName: String, type: Class<*>? = null): T? =
    getFieldOrNullAs(fieldName, type)

fun Any.fieldValueByType(type: Class<*>, isStatic: Boolean = false): Any? =
    getFieldByType<Any?>(type, isStatic)

fun <T> Any.fieldValueByTypeAs(type: Class<*>, isStatic: Boolean = false): T? =
    getFieldByType(type, isStatic)

fun Any.fieldValueByTypeOrNull(type: Class<*>, isStatic: Boolean = false): Any? =
    getFieldByTypeOrNull<Any?>(type, isStatic)

fun <T> Any.fieldValueByTypeOrNullAs(type: Class<*>, isStatic: Boolean = false): T? =
    getFieldByTypeOrNull(type, isStatic)

fun Any.setFieldValue(fieldName: String, value: Any?, type: Class<*>? = null) =
    putField(fieldName, value, type)

fun Any.setFieldValue(field: Field, value: Any?) =
    putField(field, value)

fun Class<*>.getStaticObjectField(fieldName: String): Any? =
    getStaticField(fieldName)

fun <T> Class<*>.getStaticObjectFieldAs(fieldName: String): T =
    getStaticObjectField(fieldName) as T

fun Class<*>.setStaticObjectField(fieldName: String, value: Any?) =
    putStaticField(fieldName, value)

fun Class<*>.getStaticObjectFieldOrNull(fieldName: String): Any? =
    getStaticFieldOrNull(fieldName)

fun <T> Class<*>.getStaticObjectFieldAsOrNull(fieldName: String): T? =
    getStaticFieldOrNullAs(fieldName)

fun Class<*>.getStaticBooleanField(fieldName: String): Boolean = getStaticObjectField(fieldName) as Boolean

fun Class<*>.setStaticBooleanField(fieldName: String, value: Boolean) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticIntField(fieldName: String): Int = getStaticObjectField(fieldName) as Int

fun Class<*>.setStaticIntField(fieldName: String, value: Int) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticLongField(fieldName: String): Long = getStaticObjectField(fieldName) as Long

fun Class<*>.setStaticLongField(fieldName: String, value: Long) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticFloatField(fieldName: String): Float = getStaticObjectField(fieldName) as Float

fun Class<*>.setStaticFloatField(fieldName: String, value: Float) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticDoubleField(fieldName: String): Double = getStaticObjectField(fieldName) as Double

fun Class<*>.setStaticDoubleField(fieldName: String, value: Double) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticByteField(fieldName: String): Byte = getStaticObjectField(fieldName) as Byte

fun Class<*>.setStaticByteField(fieldName: String, value: Byte) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticShortField(fieldName: String): Short = getStaticObjectField(fieldName) as Short

fun Class<*>.setStaticShortField(fieldName: String, value: Short) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticCharField(fieldName: String): Char = getStaticObjectField(fieldName) as Char

fun Class<*>.setStaticCharField(fieldName: String, value: Char) = setStaticObjectField(fieldName, value)

fun Class<*>.getStaticBooleanFieldOrNull(fieldName: String): Boolean? = runCatching { getStaticBooleanField(fieldName) }.getOrNull()

fun Class<*>.getStaticIntFieldOrNull(fieldName: String): Int? = runCatching { getStaticIntField(fieldName) }.getOrNull()

fun Class<*>.getStaticLongFieldOrNull(fieldName: String): Long? = runCatching { getStaticLongField(fieldName) }.getOrNull()

fun Class<*>.getStaticFloatFieldOrNull(fieldName: String): Float? = runCatching { getStaticFloatField(fieldName) }.getOrNull()

fun Class<*>.getStaticDoubleFieldOrNull(fieldName: String): Double? = runCatching { getStaticDoubleField(fieldName) }.getOrNull()

fun Class<*>.getStaticByteFieldOrNull(fieldName: String): Byte? = runCatching { getStaticByteField(fieldName) }.getOrNull()

fun Class<*>.getStaticShortFieldOrNull(fieldName: String): Short? = runCatching { getStaticShortField(fieldName) }.getOrNull()

fun Class<*>.getStaticCharFieldOrNull(fieldName: String): Char? = runCatching { getStaticCharField(fieldName) }.getOrNull()

fun Class<*>.staticFieldRef(fieldName: String, type: Class<*>? = null): Field =
    field(fieldName, isStatic = true, fieldType = type)

fun Class<*>.staticFieldRefOrNull(fieldName: String, type: Class<*>? = null): Field? =
    fieldOrNull(fieldName, isStatic = true, fieldType = type)

fun Class<*>.staticFieldValue(fieldName: String, type: Class<*>? = null): Any? =
    getStaticField(fieldName, type)

fun <T> Class<*>.staticFieldValueAs(fieldName: String, type: Class<*>? = null): T? =
    getStaticFieldAs(fieldName, type)

fun Class<*>.staticFieldValueOrNull(fieldName: String, type: Class<*>? = null): Any? =
    getStaticFieldOrNull(fieldName, type)

fun <T> Class<*>.staticFieldValueOrNullAs(fieldName: String, type: Class<*>? = null): T? =
    getStaticFieldOrNullAs(fieldName, type)

fun Class<*>.setStaticFieldValue(fieldName: String, value: Any?, type: Class<*>? = null) =
    putStaticField(fieldName, value, type)

fun Class<*>.setStaticFieldValue(field: Field, value: Any?) =
    putStaticField(field, value)

fun String.staticFieldValue(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
): Any? = loadClass(this, classLoader).staticFieldValue(fieldName, type)

fun <T> String.staticFieldValueAs(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
): T? = loadClass(this, classLoader).staticFieldValueAs(fieldName, type)

fun String.setStaticFieldValue(
    fieldName: String,
    value: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
    type: Class<*>? = null,
) = loadClass(this, classLoader).setStaticFieldValue(fieldName, value, type)

fun String.getStaticObjectField(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): Any? = loadClass(this, classLoader).getStaticObjectField(fieldName)

fun <T> String.getStaticObjectFieldAs(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): T = loadClass(this, classLoader).getStaticObjectFieldAs(fieldName)

fun String.getStaticObjectFieldOrNull(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): Any? = loadClass(this, classLoader).getStaticObjectFieldOrNull(fieldName)

fun <T> String.getStaticObjectFieldAsOrNull(
    fieldName: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): T? = loadClass(this, classLoader).getStaticObjectFieldAsOrNull(fieldName)

fun String.setStaticObjectField(
    fieldName: String,
    value: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
) = loadClass(this, classLoader).setStaticObjectField(fieldName, value)

fun Any.getAdditionalInstanceField(field: String): Any? =
    AdditionalFields.getInstance(this, field)

fun <T> Any.getAdditionalInstanceFieldAs(field: String): T? =
    getAdditionalInstanceField(field) as? T

fun Any.setAdditionalInstanceField(field: String, value: Any?): Any? =
    AdditionalFields.setInstance(this, field, value)

fun Any.removeAdditionalInstanceField(field: String): Any? =
    AdditionalFields.removeInstance(this, field)

fun Class<*>.getAdditionalStaticField(field: String): Any? =
    AdditionalFields.getStatic(this, field)

fun <T> Class<*>.getAdditionalStaticFieldAs(field: String): T? =
    getAdditionalStaticField(field) as? T

fun String.getAdditionalStaticField(
    field: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): Any? = loadClass(this, classLoader).getAdditionalStaticField(field)

fun <T> String.getAdditionalStaticFieldAs(
    field: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): T? = loadClass(this, classLoader).getAdditionalStaticFieldAs(field)

fun String.setAdditionalStaticField(
    field: String,
    value: Any?,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): Any? = loadClass(this, classLoader).setAdditionalStaticField(field, value)

fun String.removeAdditionalStaticField(
    field: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): Any? = loadClass(this, classLoader).removeAdditionalStaticField(field)

fun Any.additionalField(key: String): Any? =
    getAdditionalInstanceField(key)

fun <T> Any.additionalFieldAs(key: String): T? =
    getAdditionalInstanceFieldAs(key)

fun Any.setAdditionalField(key: String, value: Any?): Any? =
    setAdditionalInstanceField(key, value)

fun Any.removeAdditionalField(key: String): Any? =
    removeAdditionalInstanceField(key)

fun Class<*>.additionalStaticField(key: String): Any? =
    getAdditionalStaticField(key)

fun <T> Class<*>.additionalStaticFieldAs(key: String): T? =
    getAdditionalStaticFieldAs(key)

fun Class<*>.setAdditionalStaticField(key: String, value: Any?): Any? =
    AdditionalFields.setStatic(this, key, value)

fun Class<*>.removeAdditionalStaticField(key: String): Any? =
    AdditionalFields.removeStatic(this, key)

fun String.additionalStaticField(
    key: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): Any? = loadClass(this, classLoader).getAdditionalStaticField(key)

fun <T> String.additionalStaticFieldAs(
    key: String,
    classLoader: ClassLoader = HookClassLoader.currentOrDefault(),
): T? = loadClass(this, classLoader).getAdditionalStaticFieldAs(key)
