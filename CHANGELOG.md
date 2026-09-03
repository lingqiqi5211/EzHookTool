# Changelog

## 1.2.0

### 新增

- `EzResources`（hook-xposed-102）：宿主资源替换。`setResReplacement` / `setObjectReplacement` /
  `setDensityReplacement` 三个入口，按「包名 + 类型 + 名称」匹配，包名 `"*"` 表示不限宿主。
  按需 hook，不注册替换时零开销。用法见 sample-xposed-102 的 `ExampleResourceHook`。
- `XposedFeature`：hook-xposed-102 以 libxposed API 101 为运行基线。102 才有的能力（hook ID、
  `replaceHook`、热重载、`detach`）在 101 framework 上按不支持处理，可用 `XposedFeature.HOT_RELOAD.isSupported`
  这类属性判断。

### 行为变化

- `EzXposed.handleHotReloading` 在两种情况下拒绝热重载并记录原因：进程里注册过 `EzResources` 替换；
  或模块 apk 是通过 `AssetManager.addAssetPath` 注入的（Android R 以下，或 framework 拒绝 `ResourcesLoader`）。
  这两种状态都无法跟着换代，需要重启目标进程。
- `EzXposed.addModuleAssetPath` 签名不变，实现改为优先 `ResourcesLoader`，失败时回退 `addAssetPath`。
- `EzXposed.initOnPackageLoadedAsTargetReady` 标注 `@RequiresApi(Q)`。它依赖的 `getDefaultClassLoader`
  在 Android Q 以下不存在，此前会在运行期抛 `NoSuchMethodError`。低版本请改用 `initOnPackageReady`。

### 性能

- `core` 反射缓存的命中路径不再持锁，也没有原子操作。
