package io.github.lingqiqi5211.ezhooktool.sample102.hooks

import android.graphics.Color
import io.github.lingqiqi5211.ezhooktool.sample102.R
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/**
 * 演示宿主资源替换，包名传 "*" 表示不限宿主。批次中的 getter 在提交成功后生效。
 * 直接值不注入 APK；模块资源沿用宿主资源池转发，不承诺任意主题或命名空间兼容。
 */
object ExampleResourceHook : BaseHook() {
    override val name: String = "ExampleResourceHook"

    override fun init() {
        // 直接给值：宿主的 colorAccent 换成红色。
        EzResources.setObjectReplacement("com.example.target", "color", "colorAccent", Color.RED)
        // dp 语义：宿主的 toolbar 高度改成 64dp（会乘以当前 Resources 的 density）。
        EzResources.setDensityReplacement("com.example.target", "dimen", "toolbar_height", 64f)
        // 数组读取返回副本，不让不同调用共享可修改的结果。
        EzResources.setObjectReplacement("com.example.target", "array", "supported_modes", arrayOf("default"))
        // 先在模块资源池求值再登记，不注入宿主；配置改变后需要重新登记。
        EzResources.setObjectReplacement("com.example.target", "string", "module_label", EzXposed.moduleRes.getString(R.string.app_name))
        // R 属于模块；该兼容入口首次命中时会注入 APK，不适用于 TypedArray 模块资源转发。
        EzResources.setResReplacement("com.example.target", "string", "app_name", R.string.app_name)
    }
}
