package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ═══════════════════════ Member 修饰符 ═══════════════════════

/** 当前成员是否为 static。 */
val Member.isStatic: Boolean get() = Modifier.isStatic(modifiers)

/** 当前成员是否不是 static。 */
val Member.isNotStatic: Boolean get() = !isStatic

/** 当前成员是否为 public。 */
val Member.isPublic: Boolean get() = Modifier.isPublic(modifiers)

/** 当前成员是否不是 public。 */
val Member.isNotPublic: Boolean get() = !isPublic

/** 当前成员是否为 private。 */
val Member.isPrivate: Boolean get() = Modifier.isPrivate(modifiers)

/** 当前成员是否不是 private。 */
val Member.isNotPrivate: Boolean get() = !isPrivate

/** 当前成员是否为 protected。 */
val Member.isProtected: Boolean get() = Modifier.isProtected(modifiers)

/** 当前成员是否不是 protected。 */
val Member.isNotProtected: Boolean get() = !isProtected

/** 当前成员是否为 final。 */
val Member.isFinal: Boolean get() = Modifier.isFinal(modifiers)

/** 当前成员是否不是 final。 */
val Member.isNotFinal: Boolean get() = !isFinal

/** 当前成员是否为 abstract。 */
val Member.isAbstract: Boolean get() = Modifier.isAbstract(modifiers)

/** 当前成员是否不是 abstract。 */
val Member.isNotAbstract: Boolean get() = !isAbstract

/** 当前成员是否为 native。 */
val Member.isNative: Boolean get() = Modifier.isNative(modifiers)

/** 当前成员是否不是 native。 */
val Member.isNotNative: Boolean get() = !isNative

/** 当前成员是否为 synchronized。 */
val Member.isSynchronized: Boolean get() = Modifier.isSynchronized(modifiers)

/** 当前成员是否不是 synchronized。 */
val Member.isNotSynchronized: Boolean get() = !isSynchronized

/** 当前成员是否为 volatile。 */
val Member.isVolatile: Boolean get() = Modifier.isVolatile(modifiers)

/** 当前成员是否不是 volatile。 */
val Member.isNotVolatile: Boolean get() = !isVolatile

/** 当前成员是否为 transient。 */
val Member.isTransient: Boolean get() = Modifier.isTransient(modifiers)

/** 当前成员是否不是 transient。 */
val Member.isNotTransient: Boolean get() = !isTransient

/** Synthetic flag (0x00001000). */
val Member.isSynthetic: Boolean get() = modifiers and 0x00001000 != 0

/** 当前成员是否不是 synthetic。 */
val Member.isNotSynthetic: Boolean get() = !isSynthetic

/** Bridge flag (0x00000040). */
val Member.isBridge: Boolean get() = modifiers and 0x00000040 != 0

/** 当前成员是否不是 bridge。 */
val Member.isNotBridge: Boolean get() = !isBridge

// ═══════════════════════ Method 扩展 ═══════════════════════

/** 参数个数。 */
val Method.paramCount: Int get() = parameterTypes.size

/** 是否无参数。 */
val Method.hasEmptyParam: Boolean get() = paramCount == 0

/** 是否有参数。 */
val Method.hasNotEmptyParam: Boolean get() = paramCount > 0

// ═══════════════════════ Constructor 扩展 ═══════════════════════

/** 参数个数。 */
val Constructor<*>.paramCount: Int get() = parameterTypes.size

/** 是否无参数。 */
val Constructor<*>.hasEmptyParam: Boolean get() = paramCount == 0

/** 是否有参数。 */
val Constructor<*>.hasNotEmptyParam: Boolean get() = paramCount > 0

// ═══════════════════════ Class 修饰符 ═══════════════════════

/** 当前类是否为 abstract。 */
val Class<*>.isAbstractClass: Boolean get() = Modifier.isAbstract(modifiers)

/** 当前类是否为 final。 */
val Class<*>.isFinalClass: Boolean get() = Modifier.isFinal(modifiers)

/** 当前类是否为 static 嵌套类。 */
val Class<*>.isStaticClass: Boolean get() = Modifier.isStatic(modifiers)

/** 当前类是否为接口。 */
val Class<*>.isInterfaceClass: Boolean get() = Modifier.isInterface(modifiers)

/** 当前类是否为枚举。 */
val Class<*>.isEnumClass: Boolean get() = this.isEnum
