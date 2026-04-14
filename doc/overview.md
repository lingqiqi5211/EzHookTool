# Module Overview

EzHookTool 的 API 文档按模块聚合输出：

- `core`：反射、查找、实例化、descriptor 与 DSL 作用域
- `hook-xposed-82`：经典 Xposed 82 hook API 与兼容辅助函数
- `hook-xposed-101`：libxposed 101 hook API 与兼容辅助函数

建议阅读顺序：

1. 先看 `core` 中的查找与调用 API
2. 再根据运行时选择 `hook-xposed-82` 或 `hook-xposed-101`
3. 需要兼容旧写法时，再查看对应模块里的 `XposedHelpers`
