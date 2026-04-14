package io.github.lingqiqi5211.ezhooktool.xposed.internal

import android.content.Context

internal object AppContextProvider {
    @Volatile
    private var cachedContext: Context? = null

    fun currentOrNull(): Context? {
        cachedContext?.let { return it }

        val current = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getDeclaredMethod("currentApplication").apply {
                isAccessible = true
            }
            currentApplication.invoke(null) as? Context
        }.getOrNull()?.applicationContext
            ?: return null

        cachedContext = current
        return current
    }

    fun requireCurrent(): Context = currentOrNull()
        ?: throw NullPointerException("Cannot resolve appContext before Application.onCreate.")

    fun init(context: Context?) {
        val appContext = context?.applicationContext
            ?: throw NullPointerException("Cannot initialize appContext with null context.")
        cachedContext = appContext
    }
}
