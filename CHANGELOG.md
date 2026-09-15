# Changelog

## Unreleased

### 修复

- BestMatch 不再按成员枚举顺序处理同分候选；无法唯一选择时报告歧义，自动调用先过滤静态/实例模式。
- 默认成员枚举与备用访问均不可用时保留原异常，不再返回空数组。
- 资源 getter 统一按完整签名安装，待提交与已安装分开记录；TypedArray 不再依赖私有数据布局。
- 修复整数、颜色和尺寸转换及模块资源参数转发；补齐 array、mipmap、plurals、字符串格式化和常用 TypedArray 值读取，数组登记和返回均使用副本。
- 资源读取使用同一份规则快照；注入或回退后失效旧定位缓存，成功换代后释放旧代自有 provider。
- 实例附加字段按对象身份匹配，不再让相等对象共享字段或受可变 hashCode 影响。
- 82 普通 DSL 的 before/after 回调失败时不再留下未完成的参数或输出修改；用户 logger 抛异常不再打断安全回退。
- 102 聚合 Hook 替换和卸载成功后才更新逻辑 handle；底层结果不明时明确要求重启。
- 查询匹配与缓存使用同一份完整计划，保留重复条件、唯一性和嵌套搜索范围；移除跨查询预热，批量结果修改不再影响缓存。
- 配置切换和清缓存后，旧查询不再回写新缓存；同步嵌套查询沿用同一配置。
- 102 的 `deoptimize` 不再将框架返回的 `false` 误报为成功；新增 `deoptimizeOrThrow` 保留调用异常。
- 102 首次就绪初始化可查询状态与失败原因；重入注册按队尾执行，回调执行后释放，失败不自动重试。
- 热重载用户回调异常不再绕过资源失败处理；部分 Hook 替换、资源撤销或旧 Hook 清理失败明确要求重启，并阻止当前 entry 继续热重载。
- 同一 generation 重复初始化不再重建模块资源；显式 `initModuleResources` 仍可刷新。
- API 101 上资源替换不再因内部 hook ID 要求而安装失败；资源迁移失败不再静默继续热重载。
- 多个清理回调抛出同一个异常对象时，仍会执行剩余清理。
- 热重载业务容器独立复制，旧代后续修改不再污染交接数据；scope 准备期间拒收新状态，失败后恢复接收。
- 并发热重载收尾不再重复卸载同一个旧 Hook。

### 行为变化

- 反射缓存改为作用域内的有界强引用缓存，合计最多 4096 条，超过 256 项的集合结果不缓存；作用域重置或配置切换时清空。
- 82 与 102 的 `initAppContext` 默认缓存 Application Context，不再保留传入的 Activity；Application 未就绪时明确报错。`force=true` 保留原样缓存指定 Context 的用法。
- 热重载恢复期间资源注入必须同步执行，`EzResources.inject` 的 `onMainLooper=true` 会被拒绝。
- TypedArray 的模块资源转发不再伪造 Theme/默认参数；当前只支持直接值与尺寸替换。`setDensityReplacement` 限用于 dimen。
- 跨代业务容器限制为 32 层、4096 个节点/槽位，拒绝循环容器和无法保持数据语义的复制；其它宿主对象仍按引用传递。

### 性能

- HookChain 在创建时划分执行阶段，不再每次调用重新分类。

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
