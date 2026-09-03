package io.github.lingqiqi5211.ezhooktool.sample82.hooks

import android.graphics.Color
import io.github.lingqiqi5211.ezhooktool.sample82.R
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources

/** 演示宿主资源替换。注册即生效，一条都没注册时 EzResources 零开销；包名传 "*" 表示不限宿主。 */
object ExampleResourceHook : BaseHook() {
    override val name: String = "ExampleResourceHook"

    override fun init() {
        EzResources.setObjectReplacement("com.example.target", "color", "colorAccent", Color.RED)
        EzResources.setDensityReplacement("com.example.target", "dimen", "toolbar_height", 64f)
        EzResources.setResReplacement("com.example.target", "string", "app_name", R.string.app_name)
    }
}
