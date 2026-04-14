package io.github.lingqiqi5211.ezhooktool.sample101

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "EzHookTool sample for LibXposed API 101"
        })
    }
}
