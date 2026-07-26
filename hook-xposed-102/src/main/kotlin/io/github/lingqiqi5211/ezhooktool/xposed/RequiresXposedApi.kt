package io.github.lingqiqi5211.ezhooktool.xposed

/**
 * 标注该 API 需要的最低 libxposed API 版本。
 *
 * `hook-xposed-102` 按 API 102 编译，但只把 102 独有的能力当作可选特性使用，因此同一份产物也能跑在
 * 只实现 API 101 的 framework 上。被本注解标注的 API 是那些**无法降级**的入口：framework 版本不足时
 * 调用它们会抛出 [IllegalStateException]，而不是静默变成语义不同的行为。
 *
 * 能优雅降级的 API 不标注（例如 `HookHandle.id` 在 101 上恒为 `null`，普通 hook 安装会自动跳过
 * `setId`）。调用前可用 [XposedFeature.isSupported] 判断当前 framework 是否具备对应能力。
 *
 * @param value 该 API 要求的最低 libxposed API 版本
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
)
annotation class RequiresXposedApi(val value: Int)
