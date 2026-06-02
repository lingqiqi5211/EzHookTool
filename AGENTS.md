# AGENTS.md

## 沟通方式

- 回复使用者时使用与使用者相同的语系，专有名词保留 English。
- 只写结论、实际改动、原因、验证结果；避免工程汇报腔。
- 不夸大、不表演化；对没有验证过的内容明确说明未验证。

## 项目定位

EzHookTool 是 Kotlin 反射工具库，同时提供 Android / Xposed / libxposed 场景下的 hook 辅助能力。

这个项目不只是 hook 工具。`core` 是独立反射层，必须能脱离 Android、Xposed、libxposed 单独使用。

模块职责：

- `core`：纯 Kotlin/JVM 反射、查找、实例化、成员访问、descriptor 解析、DSL 作用域、Java 友好入口。
- `hook-xposed-82`：经典 Xposed API 82 运行时辅助。使用 `de.robv.android.xposed`、`XResources`、`XModuleResources`。
- `hook-xposed-102`：libxposed API 102 运行时辅助。使用 `io.github.libxposed.api` 与 `ModuleResources`。
- `sample-xposed-82`、`sample-xposed-102`：示例工程，只放示例用法，不承载库的核心逻辑。

## Core 约束

- `core` 不得引入 Android、Xposed、libxposed 依赖。
- `core` 的 API 面向普通 JVM 反射场景，不应假设调用者在 hook 环境中。
- `EzReflect` 的默认 `ClassLoader` 语义必须稳定；未显式初始化时应有可预测回退。
- 类、方法、字段、构造器查找逻辑要兼顾 Kotlin 与 Java 调用侧。
- descriptor 解析、重载匹配、可访问性处理属于核心能力，修改时必须考虑边界条件：
  - primitive 与 boxed type
  - nullable 与非 nullable 调用
  - static 与 instance 成员
  - inherited member 与 declared member
  - vararg、array、generic erased type
- DSL 写法和 Java facade 都是正式支持面。不要只优化 Kotlin DSL 而破坏 Java 调用。
- 错误信息要描述查找条件和目标类型，避免只返回模糊的 `NoSuchMethodException` 或 `NoSuchFieldException`。
- 新增 helper 前先确认现有 finder、scope、descriptor 工具是否已覆盖；能组合已有能力时不要重复实现。

## 修改原则

- 优先保持 public API 的 source / binary compatibility。不要轻易改包名、类名、方法签名、默认参数语义。
- 修改 public API 行为时，同步检查 README、`doc/overview.md`、KDoc、sample 是否需要更新。
- Java interop 是正式支持面。已有 `@JvmStatic`、object 入口、Java facade 不要随意删除或改名。
- 工具库不得写入特定 app、特定 ROM、特定用户项目的临时逻辑；消费者项目问题应抽象成通用能力或清晰错误。
- 不要吞掉会影响调用者判断的错误。需要兜底时，优先抛出带初始化阶段或调用入口说明的 `IllegalStateException`。
- 反射与 hook helper 要保持可读性，错误信息要指向缺失的前置条件，而不是暴露 `lateinit`、NPE 等内部细节。

## 运行时初始化约束

### API 102

- `EzXposed.initOnModuleLoaded(...)` 是 libxposed 102 的基础入口，应在这里保存 `base`、`processName`、`isSystemServer`、模块路径，并自动初始化模块资源。
- `EzXposed.initOnPackageLoaded(...)` 只记录 package 相关信息，不应依赖目标 app classloader。
- `EzXposed.initOnPackageReady(...)` 才初始化面向目标进程的 `EzReflect.classLoader`。
- `initAppContext(context, true)` 表示缓存 app context 并注入模块资源路径；它不应要求调用者额外先执行 `initModuleResources()`。
- `initModuleResources()` 只负责重新创建可独立访问的模块资源对象，不应成为资源路径注入的隐性前置条件。

### API 82

- `EzXposed.initZygote(...)` 是唯一可靠的模块路径来源，也应自动初始化模块资源。
- `initModuleResources(...)` 和 `addModuleAssetPath(...)` 在缺少 `initZygote(...)` 时必须给出明确错误。
- 不要把 API 102 的 `XposedInterface` 假设带到 API 82。

### 资源注入

- 读取 `modulePath` 前必须走统一的检查入口，避免直接读取未初始化的 `lateinit`。
- `addModuleAssetPath(...)` 应只处理资源路径注入，不顺手初始化不相关运行时状态。
- 重复调用应尽量保持幂等或可预测；不能保证幂等时，必须在 KDoc 中说明。

## Hook 安全性

- hook callback 默认不应让目标 app 因工具库异常崩溃。
- `safeMode` 相关行为属于核心契约，改动前必须确认 API 82 和 API 102 的行为一致或差异有明确原因。
- callback 异常日志要包含目标方法、阶段、原始异常；不要只打印合成类或匿名 lambda 信息。

## 代码风格

- Kotlin 代码使用项目现有风格：顶层 KDoc 清晰说明生命周期，函数短小，命名直白。
- 公共方法的默认参数要谨慎；新增默认参数必须确认 Java 调用侧不会产生误解。
- 避免无意义抽象。只有当 API 82 和 API 102 存在真实重复且语义一致时才抽公共 helper。
- 不使用 app 项目里的包名、资源名、类名作为库内常量。

## 验证标准

开始修改前先定义完成标准。交付前按改动范围验证：

- 仅文档或注释：检查内容是否准确覆盖改动目的。
- Kotlin 逻辑改动：至少执行 `git diff --check`。
- 涉及 `core`：需要时运行 `./gradlew :core:test`。
- 涉及 API 82 / 102：需要时分别运行对应模块的编译任务。
- 涉及 public API、初始化流程、资源注入或 safe mode：优先同时检查 API 82 和 API 102。
- 涉及 descriptor、重载匹配、finder 条件组合：优先补充或更新 `core` 测试。

除非使用者明确要求，不主动执行耗时构建、发布、生成文档或联网操作。

## Git 与工作区

- 工作区可能已有使用者改动。不要还原、格式化或移动无关文件。
- 提交前只包含本次任务相关文件。
- 不执行 `git reset --hard`、`git checkout --` 等破坏性操作，除非使用者明确要求。
