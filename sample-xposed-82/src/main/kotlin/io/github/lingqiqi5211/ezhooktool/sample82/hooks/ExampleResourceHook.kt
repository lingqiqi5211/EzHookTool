package io.github.lingqiqi5211.ezhooktool.sample82.hooks

import android.graphics.Color
import io.github.lingqiqi5211.ezhooktool.sample82.R
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/** 演示按需安装的资源替换；直接值不注入 APK，包名传 "*" 表示不限宿主。 */
object ExampleResourceHook : BaseHook() {
    override val name: String = "ExampleResourceHook"

    override fun init() {
        EzResources.setObjectReplacement("com.example.target", "color", "colorAccent", Color.RED)
        EzResources.setDensityReplacement("com.example.target", "dimen", "toolbar_height", 64f)
        EzResources.setObjectReplacement("com.example.target", "array", "supported_modes", arrayOf("default"))
        // 模块内求值后登记固定文本；配置改变后需要重新登记。
        EzResources.setObjectReplacement("com.example.target", "string", "module_label", EzXposed.moduleRes.getString(R.string.app_name))
        // 该兼容入口首次命中时会注入 APK，不适用于 TypedArray 模块资源转发。
        EzResources.setResReplacement("com.example.target", "string", "app_name", R.string.app_name)
    }
}
