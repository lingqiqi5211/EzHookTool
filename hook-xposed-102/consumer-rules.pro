# EzHookTool hook-xposed-102 consumer rules
# 下游开启 R8/ProGuard 时保留对 libxposed 102 框架与库本身公开的入口面。

# libxposed 框架要求 entry class 暴露公开构造，避免 entry 子类被 R8 改名
-keep class * extends io.github.libxposed.api.XposedModule { *; }

# Xposed 回调签名 / common 包内反射进入的类型
-keep class io.github.lingqiqi5211.ezhooktool.xposed.common.** { *; }
-keep class io.github.lingqiqi5211.ezhooktool.xposed.java.** { *; }
-keepclassmembers class io.github.lingqiqi5211.ezhooktool.xposed.EzXposed { public static *; }
-keepclassmembers class io.github.lingqiqi5211.ezhooktool.xposed.EzXposedKt { public static *; }

# core 公开入口
-keepclassmembers class io.github.lingqiqi5211.ezhooktool.core.EzReflect { public static *; }

# Kotlin Metadata 用于 reified inline 与扩展 fun 解析
-keep class kotlin.Metadata
