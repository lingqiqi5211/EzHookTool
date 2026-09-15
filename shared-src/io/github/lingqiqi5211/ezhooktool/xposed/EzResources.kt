@file:Suppress("DiscouragedPrivateApi", "PrivateApi", "DEPRECATION")

package io.github.lingqiqi5211.ezhooktool.xposed

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.SparseArray
import androidx.annotation.RequiresApi
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookDiagnostics
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ResourcesPlatform
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal enum class ResourceHookState { PENDING, INSTALLED, FAILED }

/** 出队不代表取得注入锁；捕获换代状态时让尚未执行的旧任务失效。 */
internal class ResourceInjectionQueue(private val lock: Any) {
    private var epoch = 0L

    fun pending(action: () -> Unit): Runnable = synchronized(lock) {
        val ticket = epoch
        Runnable {
            synchronized(lock) {
                if (ticket == epoch) action()
            }
        }
    }

    fun invalidate() = synchronized(lock) { epoch++ }
}

/** 规则发布后不再修改；读取先匹配包名，再匹配通配规则，不创建临时 key。 */
internal class ResourceRules<T> private constructor(
    private val types: Map<String, Map<String, Map<String, T>>>,
) {
    constructor() : this(emptyMap())

    val isEmpty: Boolean get() = types.isEmpty()

    fun find(pkg: String, type: String, name: String): T? {
        val packages = types[type]?.get(name) ?: return null
        return packages[pkg] ?: packages["*"]
    }

    fun withRule(pkg: String, type: String, name: String, value: T): ResourceRules<T> {
        val names = types[type].orEmpty()
        val packages = names[name].orEmpty() + (pkg to value)
        val updatedNames = names + (name to packages)
        return ResourceRules(types + (type to updatedNames))
    }
}

/**
 * 宿主资源替换。hook `Resources` / `TypedArray` 的 getter，按「包名 + 类型 + 名称」拦截取值。
 * 直接值不注入 APK；模块资源兼容入口通过 `ResourcesLoader` 绑定宿主资源池。思路来自 HyperCeiler 的 `ResourcesTool`。
 *
 * ```kotlin
 * EzResources.setResReplacement("com.miui.home", "drawable", "ic_launcher", R.drawable.my_icon)
 * EzResources.setObjectReplacement("com.miui.home", "color", "bg", Color.RED)
 * EzResources.setDensityReplacement("com.miui.home", "dimen", "bar_height", 8f)
 * ```
 *
 * 包名传 `"*"` 表示不限宿主，精确匹配优先。hook 按需装、进程级、带稳定 reloadKey，模块不需要持有或摘除。
 * 替换规则按名字存、取值时才对当前挂着的 apk 解析 id，所以 102 热重载换 apk 不会串资源；loader 由新一代先挂新再摘旧。
 */
object EzResources {
    private const val TAG = "EzResources"

    private const val HOOK_COLOR = 1
    private const val HOOK_DRAWABLE = 1 shl 1
    private const val HOOK_STRING = 1 shl 2
    private const val HOOK_DIMEN = 1 shl 3
    private const val HOOK_MISC = 1 shl 4

    /** 单个 Resources 每种定位表的上限。宿主资源表几万条，这里只留热集。 */
    private const val ResIdCacheLimit = 4096

    private const val ResourcesHookIdPrefix = "ezhooktool.internal.resources."
    private const val TypedArrayHookIdPrefix = "ezhooktool.internal.typedarray."

    private val lock = Any()
    private val injectionLock = Any()
    private val injectionQueue = ResourceInjectionQueue(injectionLock)

    /** 挂上了模块 apk 的宿主 Resources，以及注入失败过的。弱引用，不能钉住 Activity 的 Resources；都在 [lock] 下访问。 */
    private val injected: MutableSet<Resources> = Collections.newSetFromMap(WeakHashMap())
    private val injectFailed: MutableSet<Resources> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var replacements = ResourceRules<Replacement>()

    /**
     * resId 到 ResKey 的缓存，按 Resources 弱引用分区，每区 [ResIdCacheLimit] 封顶，查找不分配。
     * 未命中也缓存，否则每次都要查三次资源表。
     */
    private class ResourceSource(val assets: AssetManager) {
        val ids = SparseArray<ResKey>()
        val moduleIds = LinkedHashMap<ModuleRes, Int>(16, 0.75f, true)
        var nextEviction = 0
    }

    private val sources = WeakHashMap<Resources, ResourceSource>()
    private val resIdCacheLock = Any()

    /** 递归防护：替换值本身要再调一次原方法去取，不挡住就会自己套自己。 */
    private val inReplacement = ThreadLocal.withInitial { false }

    private val emptyKey = ResKey("", "", "")

    /** 已经为「类型不匹配」告警过的 (方法, 值类型)。这条路径每秒几千次，不能每次都打日志。 */
    private val mismatchWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val hookStates = HashMap<Method, () -> ResourceHookState>()

    @Volatile
    private var mainHandler: Handler? = null

    /** 仅在热重载同步恢复期间存在；变更发生前就记录撤销动作。 */
    @Volatile
    private var pendingSwap: ResourceReloadState? = null

    private class ResourceReloadState(
        val oldLoader: Any?,
        val oldResources: Set<Resources>,
        val oldProvider: Any?,
    ) {
        val journal = ResourceReloadJournal()
        val detached = mutableSetOf<Resources>()
    }

    private data class ResKey(
        val pkg: String,
        val type: String,
        val name: String,
    )

    /** 模块资源按名字记。id 是编译期常量，换代后可能变，按名字对当前 apk 解析才不会串。 */
    private data class ModuleRes(
        val pkg: String,
        val type: String,
        val name: String,
    )

    private enum class Kind { MODULE_RES, DENSITY, VALUE }

    private data class Replacement(
        val kind: Kind,
        val value: Any,
    )

    // region 模块资源注入

    /**
     * 把模块 apk 挂进 [resources]，之后宿主能解析模块的 `R.xxx`。R 及以上走 `ResourcesLoader`，否则回退
     * `AssetManager.addAssetPath`。重复调用同一个 [resources] 是安全的。
     *
     * @param onMainLooper 切到主线程执行；此时调用变成异步，返回 `true` 只表示已投递。
     * 热重载恢复期间禁止启用此选项，必须同步注入，否则抛 IllegalStateException。
     * @return 是否挂上
     */
    @JvmStatic
    @JvmOverloads
    fun inject(
        resources: Resources,
        onMainLooper: Boolean = false,
    ): Boolean {
        val modulePath =
            ResourcesPlatform.modulePathOrNull ?: run {
                HookDiagnostics.warn(TAG, "inject before ${ResourcesPlatform.initEntryPoint}, skipped")
                return false
            }

        if (onMainLooper) {
            synchronized(injectionLock) {
                check(pendingSwap == null) { "Resource injection during hot reload must be synchronous; use onMainLooper=false." }
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    val handler =
                        synchronized(lock) {
                            mainHandler ?: Handler(Looper.getMainLooper()).also { mainHandler = it }
                        }
                    return handler.post(injectionQueue.pending { attach(resources, modulePath) })
                }
                return attach(resources, modulePath)
            }
        }
        return attach(resources, modulePath)
    }

    /** [inject] 的 Context 形式。 */
    @JvmStatic
    @JvmOverloads
    fun inject(
        context: Context,
        onMainLooper: Boolean = false,
    ): Boolean = inject(context.resources, onMainLooper)

    private fun attach(
        resources: Resources,
        modulePath: String,
    ): Boolean =
        synchronized(injectionLock) {
            val swap = pendingSwap
            val ok = try {
                val viaLoader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && LoaderInjector.attach(resources, modulePath, swap)
                viaLoader || run {
                    swap?.journal?.markIrreversible()
                    LegacyInjector.attach(resources, modulePath)
                }
            } catch (t: Throwable) {
                synchronized(lock) { injectFailed.add(resources) }
                throw t
            } finally {
                // 成功、部分失败都可能改变资源表；晚到的旧查询不能回填这次失效后的缓存。
                synchronized(resIdCacheLock) { sources.remove(resources) }
            }
            synchronized(lock) {
                if (ok) {
                    injected.add(resources)
                    injectFailed.remove(resources)
                } else {
                    injectFailed.add(resources)
                }
            }
            if (!ok) HookDiagnostics.warn(TAG, "Failed to inject module resources into $resources")
            ok
        }

    /** 规则命中时才把模块 apk 挂到这个 Resources 上；失败过的不再试，热路径上不能反复开文件。 */
    private fun ensureInjected(resources: Resources): Boolean {
        synchronized(lock) {
            if (resources in injected) return true
            if (resources in injectFailed) return false
        }
        return synchronized(injectionLock) {
            synchronized(lock) {
                if (resources in injected) return true
                if (resources in injectFailed) return false
            }
            val modulePath = ResourcesPlatform.modulePathOrNull ?: return false
            attach(resources, modulePath)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private object LoaderInjector {
        private var loader: ResourcesLoader? = null
        private var provider: ResourcesProvider? = null
        private var loaderFailed = false

        private val attached: MutableSet<Resources> = Collections.newSetFromMap(WeakHashMap())

        fun attachedResources(): List<Resources> = synchronized(lock) { ArrayList(attached) }

        private fun requireLoader(modulePath: String): ResourcesLoader? {
            loader?.let { return it }
            if (loaderFailed) return null
            return runCatching {
                ParcelFileDescriptor
                    .open(
                        File(modulePath),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    ).use { pfd ->
                        val opened = ResourcesProvider.loadFromApk(pfd)
                        try {
                            ResourcesLoader().apply { addProvider(opened) }.also { provider = opened }
                        } catch (t: Throwable) {
                            opened.close()
                            throw t
                        }
                    }
            }.onFailure {
                // 调用方持有 injectionLock；失败记一次，getter 不重复开文件。
                loaderFailed = true
                HookDiagnostics.error(TAG, "Cannot create ResourcesLoader for $modulePath", it)
            }.getOrNull()?.also { loader = it }
        }

        val currentLoader: Any? get() = loader
        val currentProvider: Any? get() = provider

        /** 仅在全部自有绑定都迁移成功后释放旧代自有 provider；旧格式没有所有权信息，交由框架回收。 */
        fun releasePrevious(swap: ResourceReloadState) {
            val old = swap.oldLoader as? ResourcesLoader ?: return
            val owned = swap.oldProvider as? ResourcesProvider ?: return
            if (!swap.detached.containsAll(swap.oldResources)) return
            old.removeProvider(owned)
            owned.close()
        }

        fun releaseUnused() {
            if (synchronized(lock) { attached.isNotEmpty() }) return
            val current = loader ?: return
            val owned = provider ?: return
            current.removeProvider(owned)
            owned.close()
            provider = null
            loader = null
        }

        fun attach(
            resources: Resources,
            modulePath: String,
            swap: ResourceReloadState?,
        ): Boolean {
            val current = requireLoader(modulePath) ?: return false
            val old = swap?.oldLoader as? ResourcesLoader
            val hadOld = old != null && old !== current && resources in swap.oldResources
            if (swap != null) {
                val hadCurrent = synchronized(lock) { resources in attached }
                val wasInjected = synchronized(lock) { resources in injected }
                swap.journal.record {
                    // 加回旧 loader 失败时不能继续摘新 loader，保留至少一份可用资源并上报错误。
                    if (hadOld) {
                        resources.addLoaders(old)
                        swap.detached.remove(resources)
                    }
                    if (!hadCurrent) {
                        resources.removeLoaders(current)
                        synchronized(lock) { attached.remove(resources) }
                    }
                    if (!wasInjected) synchronized(lock) { injected.remove(resources) }
                }
            }
            try {
                resources.addLoaders(current)
            } catch (e: IllegalArgumentException) {
                HookDiagnostics.debug(TAG, "addLoaders rejected (${e.message}), falling back")
                return false
            }
            synchronized(lock) { attached.add(resources) }
            if (hadOld) {
                resources.removeLoaders(old)
                swap.detached.add(resources)
            }
            return true
        }
    }

    private object LegacyInjector {
        private val addAssetPath by lazy {
            AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
        }

        fun attach(
            resources: Resources,
            modulePath: String,
        ): Boolean =
            try {
                val cookie = addAssetPath.invoke(resources.assets, modulePath) as? Int ?: 0
                if (cookie == 0) {
                    HookDiagnostics.warn(TAG, "AssetManager.addAssetPath returned 0 for $modulePath")
                }
                cookie != 0
            } catch (t: Throwable) {
                HookDiagnostics.error(TAG, "AssetManager.addAssetPath failed for $modulePath", t)
                false
            }
    }

    // endregion

    // region 替换规则

    /**
     * 用模块里的资源顶掉宿主资源。
     *
     * @param pkg 宿主包名，`"*"` 表示不限
     * 保留宿主资源池转发语义；首次命中会注入。若需模块内隔离求值，先从 `EzXposed.moduleRes` 取值再调用 [setObjectReplacement]。
     * @param moduleResId 模块自己 `R` 类里的资源 ID
     */
    @JvmStatic
    fun setResReplacement(
        pkg: String,
        type: String,
        name: String,
        moduleResId: Int,
    ) {
        val moduleRes =
            ResourcesPlatform.moduleResourcesOrNull ?: run {
                HookDiagnostics.warn(TAG, "setResReplacement before ${ResourcesPlatform.initEntryPoint}, ignored")
                return
            }
        val target =
            runCatching {
                ModuleRes(
                    moduleRes.getResourcePackageName(moduleResId),
                    moduleRes.getResourceTypeName(moduleResId),
                    moduleRes.getResourceEntryName(moduleResId),
                )
            }.getOrElse {
                HookDiagnostics.warn(TAG, "Module resource 0x${Integer.toHexString(moduleResId)} not found, ignored")
                return
            }
        register(pkg, type, name, Replacement(Kind.MODULE_RES, target))
    }

    /**
     * 直接给一个值。类型要和取值方法对得上（`getText` 需要 `CharSequence`，数值类接受任意 `Number`），
     * 对不上会记一条 warn 并放行原值。数组在登记和返回时复制，其它对象的内部可变状态由调用者负责。
     */
    @JvmStatic
    fun setObjectReplacement(
        pkg: String,
        type: String,
        name: String,
        value: Any,
    ) {
        register(pkg, type, name, Replacement(Kind.VALUE, value))
    }

    /** 数值 x 屏幕密度，即 dp 语义。用于 `dimen`。 */
    @JvmStatic
    fun setDensityReplacement(
        pkg: String,
        type: String,
        name: String,
        value: Float,
    ) {
        require(type == "dimen") { "Density replacement requires resource type dimen." }
        register(pkg, type, name, Replacement(Kind.DENSITY, value))
    }

    /** 清掉全部替换规则；不卸载 getter hook，也不撤销 [inject] 创建的资源绑定。 */
    @JvmStatic
    fun clearReplacements() {
        synchronized(lock) {
            replacements = ResourceRules()
            mismatchWarned.clear()
        }
    }

    /** 兼容用的名称 hash 标识；可能冲突，不会创建 Android 资源，也不能保证宿主能解析。 */
    @JvmStatic
    fun fakeResId(name: String): Int = 0x7e00f000 or (name.hashCode() and 0x00ffffff)

    private fun register(
        pkg: String,
        type: String,
        name: String,
        replacement: Replacement,
    ) {
        runCatching {
            synchronized(lock) {
                if (!ensureHooks(type)) return@runCatching
                val stored = when (val value = replacement.value) {
                    is Array<*> -> replacement.copy(value = value.copyOf())
                    is IntArray -> replacement.copy(value = value.copyOf())
                    else -> replacement
                }
                replacements = replacements.withRule(pkg, type, name, stored)
            }
        }.onFailure {
            HookDiagnostics.error(TAG, "Failed to register replacement for $pkg/$type/$name", it)
        }
    }

    // endregion

    // region hook 安装

    private fun maskOf(type: String): Int =
        when (type) {
            "color" -> HOOK_COLOR
            "drawable", "mipmap" -> HOOK_DRAWABLE
            "string", "plurals" -> HOOK_STRING
            "dimen" -> HOOK_DIMEN
            "array" -> HOOK_STRING or HOOK_MISC
            "integer", "bool", "fraction", "layout", "anim" -> HOOK_MISC
            else -> 0
        }

    private fun ensureHooks(type: String): Boolean {
        val needed = maskOf(type)
        if (needed == 0) {
            HookDiagnostics.warn(TAG, "Unsupported resource type \"$type\", replacement ignored")
            return false
        }
        synchronized(lock) {
            ResourcesPlatform.requireInitialized()
            for ((method, mask) in getterMethods) {
                if (mask and needed != 0) installGetter(method)
            }
        }
        return true
    }

    private val getterMethods by lazy {
        listOf(Resources::class.java, TypedArray::class.java)
            .flatMap { it.declaredMethods.toList() }
            .associateWith(::getterMask)
            .filterValues { it != 0 }
    }

    /** 只登记完整签名已知的 getter；Resources 与 TypedArray 的默认值、Theme 参数不能混用。 */
    internal fun getterMask(method: Method): Int {
        val typed = method.declaringClass == TypedArray::class.java
        val int = Int::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        val boolean = Boolean::class.javaPrimitiveType!!
        val theme = Resources.Theme::class.java

        fun parameters(vararg types: Class<*>): Boolean = method.parameterTypes.contentEquals(types)

        val supported =
            when (method.name) {
                "getColor" -> {
                    if (typed) parameters(int, int) else parameters(int) || parameters(int, theme)
                }

                "getColorStateList", "getDrawable" -> {
                    parameters(int) || !typed && parameters(int, theme)
                }

                "getDrawableForDensity" -> {
                    !typed && (parameters(int, int) || parameters(int, int, theme))
                }

                "getText" -> {
                    parameters(int) || !typed && parameters(int, CharSequence::class.java)
                }

                "getString" -> {
                    parameters(int) || !typed && parameters(int, Array<Any>::class.java)
                }

                "getQuantityText", "getQuantityString" -> {
                    !typed && (parameters(int, int) || method.name == "getQuantityString" && parameters(int, int, Array<Any>::class.java))
                }

                "getTextArray", "getStringArray", "getIntArray", "getLayout", "getAnimation" -> {
                    parameters(int)
                }

                "getDimension", "getFloat" -> {
                    if (typed) parameters(int, float) else parameters(int)
                }

                "getDimensionPixelOffset", "getDimensionPixelSize", "getInteger", "getInt" -> {
                    if (typed) parameters(int, int) else parameters(int)
                }

                "getBoolean" -> {
                    if (typed) parameters(int, boolean) else parameters(int)
                }

                "getFraction" -> {
                    if (typed) parameters(int, int, int, float) else parameters(int, int, int)
                }

                else -> {
                    false
                }
            }
        if (!supported) return 0
        return when (method.name) {
            "getColor", "getColorStateList" -> HOOK_COLOR
            "getDrawable", "getDrawableForDensity" -> HOOK_DRAWABLE
            "getText", "getString", "getQuantityText", "getQuantityString", "getTextArray", "getStringArray" -> HOOK_STRING
            "getDimension", "getDimensionPixelOffset", "getDimensionPixelSize" -> HOOK_DIMEN
            else -> HOOK_MISC
        }
    }

    private fun installGetter(method: Method) {
        val state = hookStates[method]?.invoke()
        if (state == ResourceHookState.INSTALLED || state == ResourceHookState.PENDING) return
        val prefix = if (method.declaringClass == TypedArray::class.java) TypedArrayHookIdPrefix else ResourcesHookIdPrefix
        val signature = method.parameterTypes.joinToString(",") { it.name }
        hookStates[method] = ResourcesPlatform.hookBefore(method, "$prefix${method.name}($signature)", ::onResourceGet)
    }

    // endregion

    // region hook 回调

    private fun onResourceGet(param: HookParam) {
        val rules = replacements
        if (inReplacement.get() == true || rules.isEmpty) return
        val source = param.thisObjectOrNull
        val argument = param.args[0] as Int
        val resources: Resources
        val resId: Int
        when (source) {
            is Resources -> {
                resources = source
                resId = argument
            }

            is TypedArray -> {
                if (argument < 0 || argument >= source.length()) return
                resources = source.resources
                resId = source.getResourceId(argument, 0)
            }

            else -> {
                return
            }
        }
        if (resId == 0) return
        val key = resolveKey(resources, resId) ?: return
        val replacement = rules.find(key.pkg, key.type, key.name) ?: return
        val method = param.executable.name
        val value =
            try {
                when (replacement.kind) {
                    Kind.VALUE -> {
                        if ((method == "getString" || method == "getQuantityString") && param.args.last() is Array<*>) {
                            val text = replacement.value as? CharSequence ?: return
                            String.format(resources.configuration.locales[0], text.toString(), *(param.args.last() as Array<*>))
                        } else {
                            replacement.value
                        }
                    }

                    Kind.DENSITY -> {
                        if (method.startsWith("getDimension")) {
                            (replacement.value as Float) * resources.displayMetrics.density
                        } else {
                            null
                        }
                    }

                    // TypedArray 不提供原 Theme；不伪造 null Theme 或默认参数进行模块资源转发。
                    Kind.MODULE_RES -> {
                        if (source is Resources && ensureInjected(resources)) {
                            asModuleRes(replacement.value, resources, method, param.args)
                        } else {
                            null
                        }
                    }
                }
            } catch (_: Resources.NotFoundException) {
                return
            } ?: return
        val converted = convert(method, value)
        if (converted != null) {
            param.result = converted
        } else if (synchronized(mismatchWarned) { mismatchWarned.size < 128 && mismatchWarned.add("$method ${value.javaClass.name}") }) {
            HookDiagnostics.warn(TAG, "Mismatched replacement type for $method: got ${value.javaClass.name}")
        }
    }

    // endregion

    // region 替换求值

    private fun resolveKey(
        resources: Resources,
        resId: Int,
    ): ResKey? {
        val source = sourceOf(resources)
        synchronized(resIdCacheLock) { source.ids.get(resId) }?.let { return it.takeIf { k -> k != emptyKey } }
        // 资源表查询在锁外做，锁内只碰表。
        val key =
            try {
                ResKey(
                    resources.getResourcePackageName(resId),
                    resources.getResourceTypeName(resId),
                    resources.getResourceEntryName(resId),
                )
            } catch (_: Resources.NotFoundException) {
                emptyKey
            }
        synchronized(resIdCacheLock) {
            if (sources[resources] === source) {
                val table = source.ids
                if (table.indexOfKey(resId) < 0 && table.size() >= ResIdCacheLimit) {
                    table.removeAt(source.nextEviction)
                    source.nextEviction = (source.nextEviction + 1) % ResIdCacheLimit
                }
                table.put(resId, key)
            }
        }
        return key.takeIf { it != emptyKey }
    }

    private fun sourceOf(resources: Resources): ResourceSource {
        val assets = resources.assets
        return synchronized(resIdCacheLock) {
            sources[resources]?.takeIf { it.assets === assets }
                ?: ResourceSource(assets).also { sources[resources] = it }
        }
    }

    /** 对当前资源来源解析名字；注入或回退会退休该来源，晚到的查询不能回填新缓存。 */
    private fun moduleIdOf(
        resources: Resources,
        res: ModuleRes,
    ): Int {
        val source = sourceOf(resources)
        synchronized(resIdCacheLock) { source.moduleIds[res] }?.let { return it }
        val id = resources.getIdentifier(res.name, res.type, res.pkg)
        synchronized(resIdCacheLock) {
            if (sources[resources] === source) {
                source.moduleIds[res] = id
                if (source.moduleIds.size > ResIdCacheLimit) {
                    val iterator = source.moduleIds.entries.iterator()
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return id
    }

    private fun asModuleRes(
        value: Any,
        resources: Resources,
        method: String,
        args: Array<Any?>,
    ): Any? {
        val moduleResId = moduleIdOf(resources, value as? ModuleRes ?: return null)
        if (moduleResId == 0) return null

        inReplacement.set(true)
        return try {
            when {
                method == "getColor" -> {
                    resources.getColor(moduleResId, args.getOrNull(1) as? Resources.Theme)
                }

                method == "getColorStateList" -> {
                    resources.getColorStateList(moduleResId, args.getOrNull(1) as? Resources.Theme)
                }

                method == "getDrawable" -> {
                    resources.getDrawable(moduleResId, args.getOrNull(1) as? Resources.Theme)
                }

                method == "getDrawableForDensity" -> {
                    resources.getDrawableForDensity(
                        moduleResId,
                        args[1] as Int,
                        args.getOrNull(2) as? Resources.Theme,
                    )
                }

                method == "getText" -> {
                    if (args.size == 2) {
                        resources.getText(moduleResId, args[1] as? CharSequence)
                    } else {
                        resources.getText(moduleResId)
                    }
                }

                method == "getString" -> {
                    if (args.size == 2) resources.getString(moduleResId, *(args[1] as Array<*>)) else resources.getString(moduleResId)
                }

                method == "getQuantityText" -> {
                    resources.getQuantityText(moduleResId, args[1] as Int)
                }

                method == "getQuantityString" -> {
                    if (args.size == 3) {
                        resources.getQuantityString(moduleResId, args[1] as Int, *(args[2] as Array<*>))
                    } else {
                        resources.getQuantityString(moduleResId, args[1] as Int)
                    }
                }

                method == "getStringArray" -> {
                    resources.getStringArray(moduleResId)
                }

                method == "getTextArray" -> {
                    resources.getTextArray(moduleResId)
                }

                method == "getIntArray" -> {
                    resources.getIntArray(moduleResId)
                }

                method == "getInteger" -> {
                    resources.getInteger(moduleResId)
                }

                method == "getBoolean" -> {
                    resources.getBoolean(moduleResId)
                }

                method == "getDimension" -> {
                    resources.getDimension(moduleResId)
                }

                method == "getDimensionPixelOffset" -> {
                    resources.getDimensionPixelOffset(moduleResId)
                }

                method == "getDimensionPixelSize" -> {
                    resources.getDimensionPixelSize(moduleResId)
                }

                method == "getFraction" -> {
                    resources.getFraction(moduleResId, args[1] as Int, args[2] as Int)
                }

                method == "getFloat" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    resources.getFloat(moduleResId)
                }

                method == "getLayout" -> {
                    resources.getLayout(moduleResId)
                }

                method == "getAnimation" -> {
                    resources.getAnimation(moduleResId)
                }

                else -> {
                    null
                }
            }
        } finally {
            inReplacement.remove()
        }
    }

    /** 把替换值收敛成对应方法的返回类型；对不上返回 null，由调用点记日志并放行原值。 */
    internal fun convert(
        method: String,
        value: Any,
    ): Any? =
        when (method) {
            "getInteger", "getInt", "getColor", "getDimensionPixelOffset" -> {
                (value as? Number)?.toInt()
            }

            "getDimensionPixelSize" -> {
                if (value is Int) {
                    value
                } else {
                    (value as? Number)?.toFloat()?.let { pixels ->
                        val rounded = (pixels + 0.5f).toInt()
                        when {
                            rounded != 0 -> rounded
                            pixels == 0f -> 0
                            pixels > 0f -> 1
                            else -> -1
                        }
                    }
                }
            }

            "getDimension", "getFloat", "getFraction" -> {
                (value as? Number)?.toFloat()
            }

            "getText", "getQuantityText" -> {
                value as? CharSequence
            }

            "getBoolean" -> {
                value as? Boolean
            }

            "getString", "getQuantityString" -> {
                (value as? CharSequence)?.toString()
            }

            "getColorStateList" -> {
                value as? ColorStateList
            }

            "getDrawable", "getDrawableForDensity" -> {
                value as? Drawable
            }

            "getStringArray" -> {
                (value as? Array<*>)?.takeIf { it.isArrayOf<String>() }?.copyOf()
            }

            "getTextArray" -> {
                (value as? Array<*>)?.takeIf { it.isArrayOf<CharSequence>() }?.copyOf()
            }

            "getIntArray" -> {
                (value as? IntArray)?.copyOf()
            }

            "getLayout", "getAnimation" -> {
                value as? XmlResourceParser
            }

            else -> {
                null
            }
        }

    // endregion

    // region 热重载（102）

    /**
     * 上一代在 onHotReloading 里调用。只交出注入过的宿主 Resources 和旧 loader，两者都是框架对象；
     * 不摘 loader，摘了新一代挂上之前宿主解析模块 id 就是 NotFoundException。getter hook 走正常迁移。
     */
    internal fun captureForHotReload(): Any? {
        return synchronized(injectionLock) {
            injectionQueue.invalidate()
            val live =
                synchronized(lock) {
                    mainHandler?.removeCallbacksAndMessages(null)
                    if (injected.isEmpty()) return null
                    ArrayList(injected)
                }
            val oldLoader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoaderInjector.currentLoader else null
            HookDiagnostics.debug(TAG, "hot reload capture: ${live.size} resources, loader=${oldLoader != null}")
            val loaderResources =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoaderInjector.attachedResources() else emptyList<Resources>()
            val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoaderInjector.currentProvider else null
            arrayOf<Any?>(live, oldLoader, loaderResources, provider)
        }
    }

    /**
     * 新一代在 onTargetReady 之前调用。对每个 Resources 先挂新 apk 的 loader 再摘旧的，中间没有空窗；
     * 后挂的 loader 优先级高，两者并存的一瞬也是新 apk 生效。R 以下的 addAssetPath 摘不掉，模块资源保持旧版到重启。
     */
    internal fun restoreFromHotReload(saved: Any?) {
        val arr = saved as? Array<*>
        val resources = (arr?.getOrNull(0) as? List<*>)?.filterIsInstance<Resources>().orEmpty()
        val oldLoader = arr?.getOrNull(1)
        // 旧版本只有两项，按旧约定将已注入资源视为使用旧 loader。
        val oldResources = (arr?.getOrNull(2) as? List<*>)?.filterIsInstance<Resources>() ?: resources
        synchronized(injectionLock) {
            check(pendingSwap == null) { "Resource hot reload is already in progress." }
            pendingSwap = ResourceReloadState(oldLoader, oldResources.toSet(), arr?.getOrNull(3))
        }
        var injectedCount = 0
        for (target in resources) {
            check(inject(target)) { "Failed to restore module resources for $target; hot reload did not complete." }
            injectedCount++
        }
        HookDiagnostics.debug(
            TAG,
            "hot reload restore: ${resources.size} resources, injected=$injectedCount, oldLoader=${oldLoader != null}",
        )
    }

    /** 仅在 Hook 尚可安全回退时调用；不可撤销注入或 loader 恢复失败会抛异常，不能宣称旧状态已恢复。 */
    internal fun rollbackHotReload() =
        synchronized(injectionLock) {
            val swap = pendingSwap ?: return@synchronized
            try {
                swap.journal.rollback()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoaderInjector.releaseUnused()
            } finally {
                swap.journal.clear()
                pendingSwap = null
                synchronized(resIdCacheLock) {
                    sources.clear()
                }
            }
        }

    /** 丢掉回滚记录；资源早已切换，本方法不代表旧 Hook 清理成功。 */
    internal fun commitHotReload() =
        synchronized(injectionLock) {
            val swap = pendingSwap ?: return@synchronized
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoaderInjector.releasePrevious(swap)
            } finally {
                swap.journal.clear()
                pendingSwap = null
            }
        }

    // endregion
}
