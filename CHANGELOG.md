# Changelog

## 1.2.2

### 修复

- `EzResources` 与 libxposed 102 热重载的配合重做。1.2.1 把资源 hook 从热重载里跳过，这会把上一代 classloader
  钉在内存里，新规则也要重启才生效。现在 getter hook 和别的 hook 一样由新一代重新声明、原子替换；注入过的宿主
  `Resources` 与旧 `ResourcesLoader` 经 saved state 交给新一代，新一代先挂新 loader、再摘旧的，宿主解析模块资源
  没有空窗，新 hook 安装失败时把旧 loader 换回；替换规则按资源名字存，取值时才对当前挂着的 apk 解析 id，换了 apk 也不会串。已在 HyperOS 3 真机上
  连续热重载验证，包括模块资源 id 变化的情形。
- 模块资源在命中规则的那个宿主 `Resources` 里解析，规则命中时才把模块 apk 挂上去。此前只在 application 的
  `Resources` 上解析，Activity 的夜间模式、密度等配置不同时会拿到错误变体。注入过的 `Resources` 改为弱引用。

## 1.2.1

### 新增

- `EzResources`（hook-xposed-82 与 hook-xposed-102，API 相同）：宿主资源替换。`setResReplacement` /
  `setObjectReplacement` / `setDensityReplacement` 三个入口，按「包名 + 类型 + 名称」匹配，包名 `"*"` 表示不限宿主。
  按需 hook，不注册替换时零开销。用法见两个 sample 里的 `ExampleResourceHook`。
  libxposed 102 热重载时资源 hook 被跳过，上一代原地继续服务，其它 hook 正常热重载；新注册的替换规则要等目标进程
  重启才生效。
- `XposedFeature`：hook-xposed-102 以 libxposed API 101 为运行基线。102 才有的能力（hook ID、`replaceHook`、
  热重载、`detach`）在 101 framework 上按不支持处理，可用 `XposedFeature.HOT_RELOAD.isSupported` 这类属性判断。

### 行为变化

- `EzXposed.addModuleAssetPath`（82 与 102）签名不变，实现改为优先 `ResourcesLoader`，失败或 Android R 以下回退
  `AssetManager.addAssetPath`。
- `EzXposed.initOnPackageLoadedAsTargetReady`（102）标注 `@RequiresApi(Q)`。它依赖的 `getDefaultClassLoader`
  在 Android Q 以下不存在，此前会在运行期抛 `NoSuchMethodError`。低版本请改用 `initOnPackageReady`。

### 性能

- `core` 反射缓存的命中路径不再持锁，也没有原子操作。
