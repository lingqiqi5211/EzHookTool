package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 成员类型枚举，用于错误信息。
 */
enum class MemberType { METHOD, FIELD, CONSTRUCTOR }

/**
 * 反射成员查找失败时的异常。
 * 包含结构化的搜索上下文，输出格式清晰可读。
 *
 * - `debugMode = false`：简洁输出 + hint
 * - `debugMode = true`：框线格式 + 候选成员列表 + 不匹配原因
 */
class MemberNotFoundException(
    val memberType: MemberType,
    val targetClass: String,
    val searchedSuper: Boolean,
    val conditionDesc: String? = null,
    val candidates: List<String> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(
    formatMessage(memberType, targetClass, searchedSuper, conditionDesc, candidates),
    cause
) {
    companion object {
        private fun formatMessage(
            type: MemberType,
            targetClass: String,
            searchedSuper: Boolean,
            conditionDesc: String?,
            candidates: List<String>,
        ): String {
            val typeName = when (type) {
                MemberType.METHOD -> "Method"
                MemberType.FIELD -> "Field"
                MemberType.CONSTRUCTOR -> "Constructor"
            }
            val searchDesc = if (searchedSuper) "current class + superclasses (smart mode)" else "current class only"

            if (!EzReflect.debugMode) {
                // 简洁模式
                val sb = StringBuilder()
                sb.append("$typeName not found in $targetClass")
                sb.append("\n  Search: $searchDesc")
                if (conditionDesc != null) {
                    sb.append("\n  Condition: $conditionDesc")
                }
                sb.append("\n  Hint: 检查名称、参数和类型是否正确。开启 EzReflect.debugMode = true 查看候选列表。")
                return sb.toString()
            }

            // Debug 模式 — 框线结构
            val line = "─".repeat(58)
            val sb = StringBuilder()
            sb.appendLine("┌$line")
            sb.appendLine("│ EzHookTool: $typeName Not Found")
            sb.appendLine("├$line")
            sb.appendLine("│ Target   : $targetClass")
            sb.appendLine("│ Search   : $searchDesc")
            if (conditionDesc != null) {
                sb.appendLine("│ Condition: $conditionDesc")
            }
            if (candidates.isNotEmpty()) {
                sb.appendLine("│")
                sb.appendLine("│ Candidates in this class:")
                for (c in candidates) {
                    sb.appendLine("│   ✗ $c")
                }
            }
            sb.appendLine("│")
            sb.appendLine("│ Hint: 检查名称、参数个数和返回类型是否正确。")
            sb.append("└$line")
            return sb.toString()
        }
    }
}

/**
 * 类加载失败时的异常。
 */
class ClassNotFoundError(
    val className: String,
    val classLoaderInfo: String,
    val triedNames: List<String> = listOf(className),
    cause: Throwable? = null,
) : RuntimeException(
    formatMessage(className, classLoaderInfo, triedNames),
    cause
) {
    companion object {
        private fun formatMessage(
            className: String,
            classLoaderInfo: String,
            triedNames: List<String>,
        ): String {
            if (!EzReflect.debugMode) {
                val names = if (triedNames.size == 1) className
                else triedNames.joinToString(", ")
                return "Class not found: $names\n  ClassLoader: $classLoaderInfo\n  Hint: 类名可能被混淆或移除。使用 loadClassFirstOrNull() 安全处理。"
            }

            val line = "─".repeat(58)
            val sb = StringBuilder()
            sb.appendLine("┌$line")
            sb.appendLine("│ EzHookTool: Class Not Found")
            sb.appendLine("├$line")
            sb.appendLine("│ Tried:")
            for (name in triedNames) {
                sb.appendLine("│   ✗ $name")
            }
            sb.appendLine("│")
            sb.appendLine("│ ClassLoader: $classLoaderInfo")
            sb.appendLine("│")
            sb.appendLine("│ Hint: 类名可能在目标 App 新版本中被混淆/移除。")
            sb.appendLine("│       使用 loadClassFirstOrNull() 安全处理。")
            sb.append("└$line")
            return sb.toString()
        }
    }
}

// ═══════════════════════ Internal Helpers ═══════════════════════

/**
 * 将 Method 格式化为可读字符串（用于候选列表输出）。
 */
internal fun Method.toReadableString(): String {
    val mods = Modifier.toString(modifiers)
    val params = parameterTypes.joinToString(", ") { it.simpleName }
    return "$mods ${returnType.simpleName} $name($params)".trim()
}

/**
 * 将 Field 格式化为可读字符串。
 */
internal fun Field.toReadableString(): String {
    val mods = Modifier.toString(modifiers)
    return "$mods ${type.simpleName} $name".trim()
}

/**
 * 将 Constructor 格式化为可读字符串。
 */
internal fun Constructor<*>.toReadableString(): String {
    val mods = Modifier.toString(modifiers)
    val params = parameterTypes.joinToString(", ") { it.simpleName }
    return "$mods ${declaringClass.simpleName}($params)".trim()
}
