# AGENTS.md

## 沟通方式

- 回复使用者时使用与使用者相同的语系，专有名词保留 English。
- 只写结论、实际改动、原因、验证结果；避免工程汇报腔。
- 不夸大、不表演化；没有验证过的内容明确说明未验证。

## 项目定位

EzHookTool 是 Kotlin 反射工具库，同时提供 Android / Xposed / libxposed 场景下的 hook 辅助能力。
`core` 是独立反射层，必须能脱离 Android、Xposed、libxposed 单独使用。

模块：

- `core`：纯 Kotlin/JVM 反射、查找、实例化、成员访问、descriptor 解析、DSL 作用域、Java 友好入口。
- `hook-xposed-82`：经典 Xposed API 82 运行时辅助（`de.robv.android.xposed`、`XResources`、`XModuleResources`）。
- `hook-xposed-102`：libxposed 运行时辅助。**按 API 102 编译，运行基线是 API 101**，见下一节。
- `shared-src`：82 与 102 编译同一份的源码（`FieldHelper`、`ExtraFields`、`IReplaceHook`、`AdditionalFields`、
  `HookClassLoader`、`EzResources`）。framework 相关的部分由两个模块各自的同名 `internal object`
  提供（如 `ResourcesPlatform`），共享代码只依赖这些对象的签名。不是 Gradle 模块，两个 hook 模块各自把它加进 `main` source set，FQCN 在两个 artifact
  里一致。改这里等于同时改两个 artifact；加新文件前先确认它与具体 framework 无关。
- `sample-xposed-82`、`sample-xposed-102`：示例工程，只放示例用法。

## libxposed：101 基线，102 是可选特性

- 102 相对 101 只多了 `HookBuilder.setId`、`HookHandle.getId` / `replaceHook`、`XposedInterfaceWrapper.detach`
  和热重载回调及其参数类型。`Chain`、`intercept`、remote prefs / files 都是 101 就有的，不要当成 102 特性。
- 可选特性用 `XposedFeature` 枚举表达，`EzXposed.initOnModuleLoaded` 一开始就解析成位掩码，之后
  `isSupported` 只是一次位测试。不要在装 hook 的路径上再查 `apiVersion`、抛异常或分配。
- 库内对 102 符号的调用只允许写在 `XposedApiCompat.Api102` 里。别处一出现，
  `:hook-xposed-102:checkApi102Gateway` 直接失败（该任务同时扫 `shared-src`，挂在 `check` 上）。
  加新的 102 调用：先在 `Api102` 加薄转发，调用方先判特性再调网关。网关内不重复判断。
- Kotlin 里不要写 `handle.id`。它会解析成 Java 合成属性直接调 `getId()`，101 上抛 `NoSuchMethodError`；
  一律用 `XposedApiCompat.hookId(handle)`。
- 面向使用者的 102-only 入口标注 `@RequiresXposedApi`，入口处用 `XposedApiCompat.requireFeature` 检查。
- 不拆模块。单 artifact 同时服务 101 与 102 framework，是既定决定。

## 热重载与 hook 收集

- 收集哪些 hook 装了、换代时怎么原子替换、怎么收尾旧 handle，全部是工具在 `handleHotReloading` /
  `handleHotReloaded` 里做的事。**不要给模块提供 hook 收集 / 登记 API**，也不要要求模块自己持有 handle。
  模块唯一要做的是把两个回调原样桥接给 `EzXposed`，并保证 hook 在 `onTargetReady` 的同步窗口里装完。
- 库内部自己装的 Application attach hook 带稳定 `reloadKey`，由热重载原子替换；`EzResources` 的 getter hook
  例外，见资源替换一节。不要在热重载前手工摘它们。

## 运行时初始化

### API 102

- `EzXposed.initOnModuleLoaded(...)`：先 `XposedApiCompat.resolve`，再保存 `base`、`processName`、
  `isSystemServer`、模块路径，自动初始化模块资源。
- `initOnPackageLoaded(...)` 只记录 package 信息，不依赖目标 app classloader；`initOnPackageReady(...)`
  才初始化 `EzReflect.classLoader`。
- `initOnPackageLoadedAsTargetReady(...)` 依赖 `getDefaultClassLoader`，是 Android Q 的 API，已标
  `@RequiresApi(Q)`；低版本用 `initOnPackageReady`。
- `addModuleAssetPath(resources)` 委托 `EzResources.inject`，R 以上走 `ResourcesLoader`，更低回退 `addAssetPath`。

### API 82

- `EzXposed.initZygote(...)` 是唯一可靠的模块路径来源，也自动初始化模块资源。
- 缺 `initZygote(...)` 时 `initModuleResources(...)` / `addModuleAssetPath(...)` 必须给出明确错误。
- `addModuleAssetPath(...)` 与 102 一样委托 `EzResources.inject`。
- 不要把 `XposedInterface` 的假设带到 82。

### 资源替换（`EzResources`，82 与 102 共用）

- hook 是进程级、按需装的：注册过某类替换才 hook 对应 getter；一条没注册时零开销。
- `resIdCache` 按 `Resources` 弱引用分区、每区 4096 封顶、查找不分配；注入失败只记一次，不在 getter
  热路径上重试。改这个类先想清楚它每秒被调几千次。
- 热重载三件事分开：getter hook 走正常迁移；注入过的 `Resources` 与旧 `ResourcesLoader`（都是框架对象）经 saved state
  第 9 位交给新一代，`EzResources.restoreFromHotReload` 在 `onTargetReady` 之前先挂新再摘旧；替换规则按名字存。
  `onHotReloading` 返回 `false` 会取消整个请求，资源状态永远不能拖累其它 hook，也不要为它跳过 hook（会钉住上一代 classloader）。

## Core 约束

- `core` 不得引入 Android、Xposed、libxposed 依赖；API 不假设调用者在 hook 环境中。
- `EzReflect` 默认 `ClassLoader` 语义必须稳定，未初始化时有可预测回退。
- 查找缓存的外层是 `WeakKeyConcurrentMap`（Class / ClassLoader 键弱引用，否则热重载后旧 generation 被钉住），
  命中路径无锁、无原子操作，不要加回去。
- descriptor 解析、重载匹配、可访问性处理要考虑 primitive/boxed、nullable、static/instance、
  inherited/declared、vararg/array/generic erased。
- DSL 与 Java facade 都是正式支持面；错误信息要描述查找条件和目标类型。

## 修改原则

- 保持 public API 的 source / binary compatibility；不轻易改包名、签名、默认参数语义。
- 改 public API 行为时同步更新 README、`doc/overview.md`、KDoc、sample，并在 `CHANGELOG.md` 记一条。
- 已有 `@JvmStatic`、object 入口、Java facade 不随意删改。
- 不写特定 app / ROM / 用户项目的临时逻辑。
- 不吞影响调用者判断的错误；需要兜底时抛带前置条件说明的 `IllegalStateException`。
- hook callback 不能让目标 app 因工具库异常崩溃；`safeMode` 是核心契约，82 与 102 行为要一致或差异有明确原因。

## 注释与风格

- **只写 KDoc 和单行 `//` 注释。不写多行 `//` 注释块。** KDoc 说清生命周期和前置条件即可，不写设计随笔。
- 函数短小，命名直白；避免无意义抽象，只在 82 / 102 真实重复且语义一致时抽 helper。
- 仓库统一 LF（`.gitattributes`），不要提交 CRLF。

## 验证标准

- 任何 Kotlin 改动：`./gradlew build`。它包含编译、lint、`core` 单测和 `checkApi102Gateway`。CI
  （`.github/workflows/build.yml`）跑的也是这一条。
- `hook-xposed-82` / `hook-xposed-102` 不写测试。hook 行为只能在真机上验证，改动结果以对应模块编译加实机为准。
- 涉及 descriptor、重载匹配、finder 条件组合：补充或更新 `core` 测试。
- 涉及初始化流程、资源注入、safe mode：同时检查 82 与 102。

## 发布

- 版本在 `gradle.properties` 的 `VERSION_NAME`；发布前补 `CHANGELOG.md`。
- 三个库模块用 vanniktech maven publish 发到 Maven Central。除非使用者明确要求，不执行发布、推送或联网操作。

## Git 与工作区

- 工作区可能已有使用者改动。不要还原、格式化或移动无关文件。
- 提交前只包含本次任务相关文件；不执行 `git reset --hard`、`git checkout --` 等破坏性操作，除非使用者明确要求。
