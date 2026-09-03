package io.github.lingqiqi5211.ezhooktool.sample102.hooks

import android.graphics.Color
import io.github.lingqiqi5211.ezhooktool.sample102.R
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources

/**
 * 演示宿主资源替换。注册即生效，一条都没注册时 EzResources 零开销；包名传 "*" 表示不限宿主。
 * 注册过替换之后这个进程不能再热重载，EzXposed.handleHotReloading 会拒绝。
 */
object ExampleResourceHook : BaseHook() {
    override val name: String = "ExampleResourceHook"

    override fun init() {
        // 直接给值：宿主的 colorAccent 换成红色。
        EzResources.setObjectReplacement("com.example.target", "color", "colorAccent", Color.RED)
        // dp 语义：宿主的 toolbar 高度改成 64dp（会乘以当前 Resources 的 density）。
        EzResources.setDensityReplacement("com.example.target", "dimen", "toolbar_height", 64f)
        // 用模块自己的资源顶掉宿主的：R 是模块的 R，宿主 Resources 已被注入模块 apk，所以能解析到。
        EzResources.setResReplacement("com.example.target", "string", "app_name", R.string.app_name)
    }
}
