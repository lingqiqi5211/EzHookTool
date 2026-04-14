package io.github.lingqiqi5211.ezhooktool.sample101.hooks

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.helper.hookIntercept

object ExampleCryptoHook : BaseHook() {
    override val name: String = "ExampleCryptoHook"

    override fun init() {
        "com.example.target.Crypto".findMethod {
            name == "encrypt"
        }.hookIntercept { chain ->
            chain.proceed()
        }
    }
}
