# Changelog

## 1.2.0

### 新增

- **`EzResources`（hook-xposed-102）** —— 宿主资源替换。libxposed 102 没有 `XResources`，这里用 hook
  `Resources` / `TypedArray` getter 的方式补上：把模块 apk 作为 `ResourcesLoader` 挂进宿主 `Resources`，
  再按「包名 + 类型 + 名称」拦截取值。按需装 hook，没注册替换时零开销；resId 缓存按 `Resources`
  弱引用分区、每个 `Resources` 4096 条封顶、查找不分配对象。
- **可选特性协商定型** —— 版本在 `initOnModuleLoaded` 解析一次成位掩码，之后 `XposedFeature.isSupported`
  是一次位测试。库内对 102 API 的调用只允许出现在 `XposedApiCompat.Api102` 一个网关里，
  由 `:hook-xposed-102:checkApi102Gateway` 在构建期强制。运行基线是 API 101。
- **CI 构建**：`.github/workflows/build.yml`，每次 push / PR 跑 `./gradlew build`。

### 行为变化

- **`EzXposed.handleHotReloading` 多了一个否决条件**：进程里注册过 `EzResources` 替换时拒绝热重载并
  记录原因（已 inflate 的 View、缓存的 drawable、framework 持有的 `ResourcesLoader` 跟不了代）。
  只通过 `ResourcesLoader` 注入、没注册替换的模块不受影响，旧 apk 的 loader 会在换代前自动摘除。
  走过 `AssetManager.addAssetPath` 回退（Android R 以下，或 framework 拒绝 loader）的进程同样拒绝热重载：
  那条路径没有摘除手段。
- **`EzResources` 不再为不支持的资源类型保存替换规则**，只记一条 warn。
- **`EzXposed.addModuleAssetPath` 改为走 `EzResources.inject`**：签名不变，实现从
  `AssetManager.addAssetPath` 升级为优先 `ResourcesLoader`、失败再回退；注入过的 `Resources` 会被登记，
  热重载前才摘得干净。
- **`initOnPackageLoadedAsTargetReady` 标注 `@RequiresApi(Q)`**：它调用的
  `PackageLoadedParam.getDefaultClassLoader` 是 Android Q 才有的 API，而模块 minSdk 26，此前在
  API 26~28 上会在运行期抛 `NoSuchMethodError`。现在是使用方的编译期 lint 提示。

### 内部

- `FieldHelper` / `ExtraFields` / `IReplaceHook` / `AdditionalFields` / `HookClassLoader` 原本是 82 与 102
  两份手工同步的镜像，改为 `shared-src` 共享目录，包名与 FQCN 不变。
- `core` 反射缓存的外层从 `Collections.synchronizedMap(WeakHashMap)` 换成弱键并发映射 `WeakKeyConcurrentMap`，
  访问时钟从 `AtomicLong` 改为 racy 计数：缓存命中路径上不再有锁和 CAS。
- `checkApi102Gateway` 同时扫描 `shared-src`。
- 依赖：AGP 9.4.0、Gradle 9.7.1、JUnit 6.1.3。
- 新增 `.gitattributes`，仓库统一 LF 入库。
- `Deoptimizers.deoptimizeMethods` 去掉 `!!`。
