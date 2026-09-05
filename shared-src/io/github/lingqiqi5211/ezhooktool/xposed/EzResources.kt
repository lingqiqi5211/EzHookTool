@file:Suppress("DiscouragedPrivateApi", "PrivateApi", "DEPRECATION")

package io.github.lingqiqi5211.ezhooktool.xposed

import androidx.annotation.RequiresApi
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.SparseArray
import android.util.TypedValue
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.core.java.Fields
import io.github.lingqiqi5211.ezhooktool.core.java.Methods
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ResourcesPlatform
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主资源替换。借 `ResourcesLoader` 把模块 apk 挂进宿主 `Resources`，再 hook `Resources` / `TypedArray` 的
 * getter 按「包名 + 类型 + 名称」拦截取值。不依赖 framework 提供资源接口，思路来自 HyperCeiler 的 `ResourcesTool`。
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

    // TypedArray.mData 的条目布局，见 AOSP TypedArray。
    private const val STYLE_NUM_ENTRIES = 7
    private const val STYLE_TYPE = 0
    private const val STYLE_RESOURCE_ID = 3

    private const val HOOK_COLOR = 1
    private const val HOOK_DRAWABLE = 1 shl 1
    private const val HOOK_STRING = 1 shl 2
    private const val HOOK_DIMEN = 1 shl 3
    private const val HOOK_MISC = 1 shl 4

    /** 单个 Resources 的 resId 缓存上限。宿主资源表几万条，这里只留热集。 */
    private const val ResIdCacheLimit = 4096

    private const val ResourcesHookIdPrefix = "ezhooktool.internal.resources."
    private const val TypedArrayHookIdPrefix = "ezhooktool.internal.typedarray."

    private val lock = Any()

    /** 挂上了模块 apk 的宿主 Resources，以及注入失败过的。弱引用，不能钉住 Activity 的 Resources；都在 [lock] 下访问。 */
    private val injected: MutableSet<Resources> = Collections.newSetFromMap(WeakHashMap())
    private val injectFailed: MutableSet<Resources> = Collections.newSetFromMap(WeakHashMap())
    private val replacements = ConcurrentHashMap<ResKey, Replacement>()

    /**
     * resId 到 ResKey 的缓存，按 Resources 弱引用分区，每区 [ResIdCacheLimit] 封顶，查找不分配。
     * 未命中也缓存，否则每次都要查三次资源表。
     */
    private val resIdCache = WeakHashMap<Resources, SparseArray<ResKey>>()
    private val moduleIdCache = WeakHashMap<Resources, HashMap<ModuleRes, Int>>()
    private val resIdCacheLock = Any()

    /** 递归防护：替换值本身要再调一次原方法去取，不挡住就会自己套自己。 */
    private val inReplacement = ThreadLocal.withInitial { false }

    private val emptyKey = ResKey("", "", "")

    /** 已经为「类型不匹配」告警过的 (方法, 值类型)。这条路径每秒几千次，不能每次都打日志。 */
    private val mismatchWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var appliedMask = 0

    @Volatile
    private var mainHandler: Handler? = null

    /** 热重载换过 loader 但新 hook 尚未装好的 Resources 与旧 loader；装失败时据此换回。 */
    @Volatile
    private var pendingSwap: Pair<List<Resources>, Any?>? = null

    private data class ResKey(val pkg: String, val type: String, val name: String)

    /** 模块资源按名字记。id 是编译期常量，换代后可能变，按名字对当前 apk 解析才不会串。 */
    private data class ModuleRes(val pkg: String, val type: String, val name: String)

    private enum class Kind { MODULE_RES, DENSITY, VALUE }

    private data class Replacement(val kind: Kind, val value: Any)

    // region 模块资源注入

    /**
     * 把模块 apk 挂进 [resources]，之后宿主能解析模块的 `R.xxx`。R 及以上走 `ResourcesLoader`，否则回退
     * `AssetManager.addAssetPath`。重复调用同一个 [resources] 是安全的。
     *
     * @param onMainLooper 切到主线程执行；此时调用变成异步，返回 `true` 只表示已投递
     * @return 是否挂上
     */
    @JvmStatic
    @JvmOverloads
    fun inject(resources: Resources, onMainLooper: Boolean = false): Boolean {
        val modulePath = ResourcesPlatform.modulePathOrNull ?: run {
            EzReflect.logger.warn(TAG, "inject before ${ResourcesPlatform.initEntryPoint}, skipped")
            return false
        }

        if (onMainLooper && Looper.myLooper() != Looper.getMainLooper()) {
            val handler = synchronized(lock) {
                mainHandler ?: Handler(Looper.getMainLooper()).also { mainHandler = it }
            }
            handler.post { attach(resources, modulePath) }
            return true
        }
        return attach(resources, modulePath)
    }

    /** [inject] 的 Context 形式。 */
    @JvmStatic
    @JvmOverloads
    fun inject(context: Context, onMainLooper: Boolean = false): Boolean =
        inject(context.resources, onMainLooper)

    private fun attach(resources: Resources, modulePath: String): Boolean {
        val viaLoader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && LoaderInjector.attach(resources, modulePath)
        val ok = viaLoader || LegacyInjector.attach(resources, modulePath)
        synchronized(lock) { (if (ok) injected else injectFailed).add(resources) }
        if (!ok) EzReflect.logger.warn(TAG, "Failed to inject module resources into $resources")
        return ok
    }

    /** 规则命中时才把模块 apk 挂到这个 Resources 上；失败过的不再试，热路径上不能反复开文件。 */
    private fun ensureInjected(resources: Resources): Boolean {
        synchronized(lock) {
            if (resources in injected) return true
            if (resources in injectFailed) return false
        }
        val modulePath = ResourcesPlatform.modulePathOrNull ?: return false
        return attach(resources, modulePath)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private object LoaderInjector {
        @Volatile
        private var loader: ResourcesLoader? = null

        @Volatile
        private var loaderFailed = false

        private fun requireLoader(modulePath: String): ResourcesLoader? {
            loader?.let { return it }
            if (loaderFailed) return null
            return synchronized(this) {
                loader ?: runCatching {
                    ParcelFileDescriptor.open(
                        File(modulePath),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    ).use { pfd ->
                        ResourcesLoader().apply { addProvider(ResourcesProvider.loadFromApk(pfd)) }
                    }
                }.onFailure {
                    // 记住失败：这段可能在 Resources getter 的热路径上被触发，不能每次都重新 open。
                    loaderFailed = true
                    EzReflect.logger.error(TAG, "Cannot create ResourcesLoader for $modulePath", it)
                }.getOrNull()?.also { loader = it }
            }
        }

        val currentLoader: Any? get() = loader

        fun detachOld(resources: Resources, oldLoader: Any?): Boolean {
            val old = oldLoader as? ResourcesLoader ?: return false
            if (old === loader) return false
            return runCatching { resources.removeLoaders(old) }.onFailure {
                EzReflect.logger.warn(TAG, "Failed to remove the previous generation's ResourcesLoader: ${it.message}")
            }.isSuccess
        }

        fun swapBack(resources: Resources, oldLoader: Any?) {
            val old = oldLoader as? ResourcesLoader ?: return
            runCatching { resources.addLoaders(old) }
            loader?.let { runCatching { resources.removeLoaders(it) } }
        }

        fun attach(resources: Resources, modulePath: String): Boolean {
            val current = requireLoader(modulePath) ?: return false
            return try {
                resources.addLoaders(current)
                true
            } catch (e: IllegalArgumentException) {
                // ResourcesImpl 没在 ResourcesManager 注册过时 framework 直接拒绝，只能回退。
                EzReflect.logger.debug(TAG, "addLoaders rejected (${e.message}), falling back")
                false
            }
        }
    }

    private object LegacyInjector {
        private val addAssetPath by lazy {
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
        }

        fun attach(resources: Resources, modulePath: String): Boolean = try {
            val cookie = addAssetPath.invoke(resources.assets, modulePath) as? Int ?: 0
            if (cookie == 0) {
                EzReflect.logger.warn(TAG, "AssetManager.addAssetPath returned 0 for $modulePath")
            }
            cookie != 0
        } catch (t: Throwable) {
            EzReflect.logger.error(TAG, "AssetManager.addAssetPath failed for $modulePath", t)
            false
        }
    }

    // endregion

    // region 替换规则

    /**
     * 用模块里的资源顶掉宿主资源。
     *
     * @param pkg 宿主包名，`"*"` 表示不限
     * @param moduleResId 模块自己 `R` 类里的资源 ID
     */
    @JvmStatic
    fun setResReplacement(pkg: String, type: String, name: String, moduleResId: Int) {
        val moduleRes = ResourcesPlatform.moduleResourcesOrNull ?: run {
            EzReflect.logger.warn(TAG, "setResReplacement before ${ResourcesPlatform.initEntryPoint}, ignored")
            return
        }
        val target = runCatching {
            ModuleRes(
                moduleRes.getResourcePackageName(moduleResId),
                moduleRes.getResourceTypeName(moduleResId),
                moduleRes.getResourceEntryName(moduleResId),
            )
        }.getOrElse {
            EzReflect.logger.warn(TAG, "Module resource 0x${Integer.toHexString(moduleResId)} not found, ignored")
            return
        }
        register(pkg, type, name, Replacement(Kind.MODULE_RES, target))
    }

    /**
     * 直接给一个值。类型要和取值方法对得上（`getText` 需要 `CharSequence`，数值类接受任意 `Number`），
     * 对不上会记一条 warn 并放行原值。
     */
    @JvmStatic
    fun setObjectReplacement(pkg: String, type: String, name: String, value: Any) {
        register(pkg, type, name, Replacement(Kind.VALUE, value))
    }

    /** 数值 x 屏幕密度，即 dp 语义。用于 `dimen`。 */
    @JvmStatic
    fun setDensityReplacement(pkg: String, type: String, name: String, value: Float) {
        register(pkg, type, name, Replacement(Kind.DENSITY, value))
    }

    /** 清掉全部替换规则。已经装上的 hook 不摘 —— 它们此后只是空转。 */
    @JvmStatic
    fun clearReplacements() {
        replacements.clear()
        synchronized(resIdCacheLock) {
            resIdCache.clear()
            moduleIdCache.clear()
        }
    }

    /** 生成一个不与宿主冲突的虚拟资源 ID，用于宿主里本来没有的资源。 */
    @JvmStatic
    fun fakeResId(name: String): Int = 0x7e00f000 or (name.hashCode() and 0x00ffffff)

    private fun register(pkg: String, type: String, name: String, replacement: Replacement) {
        runCatching {
            if (!ensureHooks(type)) return@runCatching
            replacements[ResKey(pkg, type, name)] = replacement
        }.onFailure {
            EzReflect.logger.error(TAG, "Failed to register replacement for $pkg/$type/$name", it)
        }
    }

    // endregion

    // region hook 安装

    private fun maskOf(type: String): Int = when (type) {
        "color" -> HOOK_COLOR
        "drawable" -> HOOK_DRAWABLE
        "string" -> HOOK_STRING
        "dimen" -> HOOK_DIMEN
        "integer", "bool", "fraction", "layout", "anim" -> HOOK_MISC
        else -> 0
    }

    private fun ensureHooks(type: String): Boolean {
        val needed = maskOf(type)
        if (needed == 0) {
            EzReflect.logger.warn(TAG, "Unsupported resource type \"$type\", replacement ignored")
            return false
        }
        if (appliedMask and needed == needed) return true

        synchronized(lock) {
            if (appliedMask and needed == needed) return true
            ResourcesPlatform.requireInitialized()
            // 这些 hook 是进程级的，一条替换规则装一次就服务全部规则。
            installResourcesHooks(needed)
            installTypedArrayHooks(needed)
            appliedMask = appliedMask or needed
        }
        return true
    }

    private fun installResourcesHooks(mask: Int) {
        for (method in Resources::class.java.declaredMethods) {
            if (!needsResourcesHook(method, mask)) continue
            runCatching {
                ResourcesPlatform.hookBefore(
                    method,
                    "$ResourcesHookIdPrefix${method.name}/${method.parameterCount}",
                    ::onResourcesGet,
                )
            }.onFailure {
                EzReflect.logger.error(TAG, "Failed to hook Resources.${method.name}", it)
            }
        }
    }

    private fun needsResourcesHook(method: Method, mask: Int): Boolean {
        val types = method.parameterTypes
        val int = Int::class.javaPrimitiveType
        val oneInt = types.size == 1 && types[0] == int
        val twoArgs = types.size == 2 && types[0] == int
        val threeArgs = types.size == 3 && types[0] == int

        return when (method.name) {
            "getColor", "getColorStateList" -> mask and HOOK_COLOR != 0 && twoArgs
            "getDrawable", "getDrawableForDensity" ->
                mask and HOOK_DRAWABLE != 0 && (twoArgs || threeArgs)

            "getText", "getStringArray", "getTextArray" -> mask and HOOK_STRING != 0 && oneInt
            "getDimension", "getDimensionPixelOffset", "getDimensionPixelSize" ->
                mask and HOOK_DIMEN != 0 && oneInt

            "getInteger", "getBoolean", "getFloat", "getIntArray", "getLayout", "getAnimation" ->
                mask and HOOK_MISC != 0 && oneInt

            "getFraction" -> mask and HOOK_MISC != 0 && threeArgs
            else -> false
        }
    }

    private fun installTypedArrayHooks(mask: Int) {
        for (method in TypedArray::class.java.declaredMethods) {
            if (!needsTypedArrayHook(method, mask)) continue
            runCatching {
                ResourcesPlatform.hookBefore(
                    method,
                    "$TypedArrayHookIdPrefix${method.name}/${method.parameterCount}",
                    ::onTypedArrayGet,
                )
            }.onFailure {
                EzReflect.logger.error(TAG, "Failed to hook TypedArray.${method.name}", it)
            }
        }
    }

    private fun needsTypedArrayHook(method: Method, mask: Int): Boolean {
        val types = method.parameterTypes
        val int = Int::class.javaPrimitiveType
        val isColor =
            (method.name == "getColor" && types.size == 2 && types[0] == int && types[1] == int) ||
                (method.name == "getColorStateList" && types.size == 1 && types[0] == int)
        val isDrawable = method.name == "getDrawable" && types.size == 1 && types[0] == int

        return (mask and HOOK_COLOR != 0 && isColor) || (mask and HOOK_DRAWABLE != 0 && isDrawable)
    }

    // endregion

    // region hook 回调

    private fun onResourcesGet(param: HookParam) {
        if (inReplacement.get() == true) return
        if (replacements.isEmpty()) return

        val requestedId = param.args.getOrNull(0) as? Int ?: return
        if (requestedId == 0) return
        val hostResources = param.thisObjectOrNull as? Resources ?: return
        val methodName = param.executable.name

        val value = try {
            resolve(hostResources, methodName, param.args)
        } catch (_: Resources.NotFoundException) {
            return
        } ?: return

        val converted = convert(methodName, value)
        if (converted != null) {
            param.result = converted
        } else if (mismatchWarned.add("$methodName ${value.javaClass.name}")) {
            EzReflect.logger.warn(TAG, "Mismatched replacement type for $methodName: got ${value.javaClass.name}")
        }
    }

    private fun onTypedArrayGet(param: HookParam) {
        if (inReplacement.get() == true) return
        if (replacements.isEmpty()) return

        val index = param.args.getOrNull(0) as? Int ?: return
        if (index < 0) return
        val typedArray = param.thisObjectOrNull ?: return

        val data = Fields.getObjectField(typedArray, "mData") as? IntArray ?: return
        val base = index * STYLE_NUM_ENTRIES
        if (base + STYLE_RESOURCE_ID >= data.size) return
        if (data[base + STYLE_TYPE] == TypedValue.TYPE_NULL) return
        val resId = data[base + STYLE_RESOURCE_ID]
        if (resId == 0) return

        val resources = Fields.getObjectField(typedArray, "mResources") as? Resources ?: return
        val methodName = param.executable.name

        val key = resolveKey(resources, resId) ?: return
        val replacement = findReplacement(key) ?: return

        val value = runCatching {
            when (replacement.kind) {
                Kind.VALUE -> asValue(replacement.value, methodName)
                Kind.DENSITY -> asDensity(replacement.value, resources, methodName)
                // TypedArray 的取值方法签名和 Resources 的不一样，补齐成后者的形状再转发。
                Kind.MODULE_RES -> if (!ensureInjected(resources)) null else when (methodName) {
                    "getColor" ->
                        asModuleRes(replacement.value, resources, methodName, arrayOf(resId, 0))

                    "getColorStateList", "getDrawable" ->
                        asModuleRes(replacement.value, resources, methodName, arrayOf(resId, null))

                    else -> null
                }
            }
        }.onFailure {
            EzReflect.logger.error(TAG, "TypedArray replacement failed for $methodName", it)
        }.getOrNull() ?: return

        convert(methodName, value)?.let { param.result = it }
    }

    // endregion

    // region 替换求值

    private fun resolve(hostResources: Resources, method: String, args: Array<Any?>): Any? {
        val resId = args.getOrNull(0) as? Int ?: return null
        if (resId == 0) return null
        val key = resolveKey(hostResources, resId) ?: return null
        val replacement = findReplacement(key) ?: return null

        return when (replacement.kind) {
            Kind.VALUE -> asValue(replacement.value, method)
            Kind.DENSITY -> asDensity(replacement.value, hostResources, method)
            // 模块资源就在宿主当前这个 Resources 里解析：配置和主题都是它自己的，不会拿错变体。
            Kind.MODULE_RES ->
                if (ensureInjected(hostResources)) asModuleRes(replacement.value, hostResources, method, args) else null
        }
    }

    private fun resolveKey(resources: Resources, resId: Int): ResKey? {
        synchronized(resIdCacheLock) { resIdCache[resources]?.get(resId) }?.let { return it.takeIf { k -> k != emptyKey } }
        // 资源表查询在锁外做，锁内只碰表。
        val key = runCatching {
            ResKey(
                resources.getResourcePackageName(resId),
                resources.getResourceTypeName(resId),
                resources.getResourceEntryName(resId),
            )
        }.getOrDefault(emptyKey)
        synchronized(resIdCacheLock) {
            val table = resIdCache.getOrPut(resources) { SparseArray() }
            if (table.size() >= ResIdCacheLimit) table.clear()
            table.put(resId, key)
        }
        return key.takeIf { it != emptyKey }
    }

    /** 对 [resources] 当前挂着的模块 apk 解析名字；查不到是 0。结果按 Resources 缓存，本代内 loader 不会变。 */
    private fun moduleIdOf(resources: Resources, res: ModuleRes): Int {
        synchronized(resIdCacheLock) { moduleIdCache[resources]?.get(res) }?.let { return it }
        val id = resources.getIdentifier(res.name, res.type, res.pkg)
        synchronized(resIdCacheLock) { moduleIdCache.getOrPut(resources) { HashMap() }[res] = id }
        return id
    }

    /** 精确匹配优先，其次是包名通配的规则。 */
    private fun findReplacement(key: ResKey): Replacement? =
        replacements[key] ?: if (key.pkg == "*") null else replacements[key.copy(pkg = "*")]

    private fun asValue(value: Any, method: String): Any? {
        if (method == "getText" && value !is CharSequence) {
            EzReflect.logger.warn(TAG, "getText replacement is not a CharSequence, ignored")
            return null
        }
        return value
    }

    private fun asDensity(value: Any, resources: Resources, method: String): Any? {
        if (method == "getText") {
            EzReflect.logger.warn(TAG, "Density replacement cannot serve getText, ignored")
            return null
        }
        val density = resources.displayMetrics.density
        val scaled = when (value) {
            is Number -> value.toFloat() * density
            is String -> value.toFloatOrNull()?.times(density)
            else -> null
        }
        if (scaled == null) {
            EzReflect.logger.warn(TAG, "Invalid density replacement value: $value")
        }
        return scaled
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
                (method == "getDrawable" || method == "getColorStateList") && args.size >= 2 ->
                    Methods.callMethod(resources, method, moduleResId, args[1])

                (method == "getDrawableForDensity" || method == "getFraction") && args.size >= 3 ->
                    Methods.callMethod(resources, method, moduleResId, args[1], args[2])

                else -> Methods.callMethod(resources, method, moduleResId)
            }
        } finally {
            inReplacement.remove()
        }
    }

    /** 把替换值收敛成对应方法的返回类型；对不上返回 null，由调用点记日志并放行原值。 */
    private fun convert(method: String, value: Any): Any? = when (method) {
        "getInteger", "getColor", "getDimensionPixelOffset", "getDimensionPixelSize" ->
            (value as? Number)?.toFloat()?.let(Math::round)

        "getDimension", "getFloat" -> (value as? Number)?.toFloat()
        "getText" -> value as? CharSequence
        "getBoolean" -> value as? Boolean
        else -> value
    }

    // endregion

    // region 热重载（102）

    /**
     * 上一代在 onHotReloading 里调用。只交出注入过的宿主 Resources 和旧 loader，两者都是框架对象；
     * 不摘 loader，摘了新一代挂上之前宿主解析模块 id 就是 NotFoundException。getter hook 走正常迁移。
     */
    internal fun captureForHotReload(): Any? {
        val live = synchronized(lock) {
            mainHandler?.removeCallbacksAndMessages(null)
            if (injected.isEmpty()) return null
            ArrayList(injected)
        }
        val oldLoader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoaderInjector.currentLoader else null
        EzReflect.logger.debug(TAG, "hot reload capture: ${live.size} resources, loader=${oldLoader != null}")
        return arrayOf<Any?>(live, oldLoader)
    }

    /**
     * 新一代在 onTargetReady 之前调用。对每个 Resources 先挂新 apk 的 loader 再摘旧的，中间没有空窗；
     * 后挂的 loader 优先级高，两者并存的一瞬也是新 apk 生效。R 以下的 addAssetPath 摘不掉，模块资源保持旧版到重启。
     */
    internal fun restoreFromHotReload(saved: Any?) {
        val arr = saved as? Array<*> ?: return
        val resources = (arr.getOrNull(0) as? List<*>)?.filterIsInstance<Resources>() ?: return
        val oldLoader = arr.getOrNull(1)
        var injectedCount = 0
        val swapped = ArrayList<Resources>()
        for (target in resources) {
            if (!inject(target)) continue
            injectedCount++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && LoaderInjector.detachOld(target, oldLoader)) swapped += target
        }
        if (swapped.isNotEmpty()) pendingSwap = swapped to oldLoader
        EzReflect.logger.debug(
            TAG,
            "hot reload restore: ${resources.size} resources, injected=$injectedCount, oldLoader=${oldLoader != null}, detached=${swapped.size}",
        )
    }

    /** 新一代 hook 装失败、框架保留上一代继续跑时调用：把旧 loader 换回去，上一代的 by-id 注入组件才对得上。 */
    internal fun rollbackHotReload() {
        val (targets, oldLoader) = pendingSwap ?: return
        pendingSwap = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (target in targets) LoaderInjector.swapBack(target, oldLoader)
        }
        synchronized(lock) { injected.removeAll(targets.toSet()) }
        EzReflect.logger.warn(TAG, "hot reload rolled back: restored the previous ResourcesLoader on ${targets.size} resources")
    }

    /** 新一代 hook 装好后调用，丢掉旧 loader 的引用。 */
    internal fun commitHotReload() {
        pendingSwap = null
    }

    // endregion
}
