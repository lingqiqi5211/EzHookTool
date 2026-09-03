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
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.core.java.Fields
import io.github.lingqiqi5211.ezhooktool.core.java.Methods
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.io.File
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 宿主资源替换。libxposed 102 没有 `XResources`，这里把模块 apk 挂进宿主 `Resources`，再 hook
 * `Resources` / `TypedArray` 的 getter 按「包名 + 类型 + 名称」拦截取值。
 *
 * ```kotlin
 * EzResources.setResReplacement("com.miui.home", "drawable", "ic_launcher", R.drawable.my_icon)
 * EzResources.setObjectReplacement("com.miui.home", "color", "bg", Color.RED)
 * EzResources.setDensityReplacement("com.miui.home", "dimen", "bar_height", 8f)
 * ```
 *
 * 包名传 `"*"` 表示不限宿主，精确匹配优先。hook 按需装、进程级、带稳定 reloadKey，模块不需要持有或摘除。
 * 注册过替换的进程不能热重载，[EzXposed.handleHotReloading] 会拒绝并要求重启目标进程。
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

    private val lock = Any()

    /** 已经挂上模块资源的宿主 Resources。热重载前要逐个摘干净。 */
    private val injected = CopyOnWriteArrayList<Resources>()
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val replacements = ConcurrentHashMap<ResKey, Replacement>()

    /**
     * resId 到 ResKey 的缓存，按 Resources 弱引用分区，每区 [ResIdCacheLimit] 封顶，查找不分配。
     * 未命中也缓存，否则每次都要查三次资源表。
     */
    private val resIdCache = WeakHashMap<Resources, SparseArray<ResKey>>()
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

    /** 注入失败过就不在 getter 热路径上重试；热重载换代时重置。 */
    @Volatile
    private var injectFailed = false

    /** 走过 `addAssetPath` 就再也摘不掉，热重载必须拒绝。 */
    @Volatile
    private var legacyInjected = false

    private data class ResKey(val pkg: String, val type: String, val name: String)

    private enum class Kind { MODULE_RES_ID, DENSITY, VALUE }

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
        val modulePath = EzXposed.modulePathOrNull ?: run {
            EzReflect.logger.warn(TAG, "inject before EzXposed.initOnModuleLoaded, skipped")
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
        if (ok) {
            injected.addIfAbsent(resources)
            if (!viaLoader) legacyInjected = true
        } else {
            EzReflect.logger.warn(TAG, "Failed to inject module resources into $resources")
        }
        return ok
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

        /** 从所有注入过的 Resources 上摘掉 loader；摘到一半失败时把已摘的加回去再抛出。 */
        fun detachAll(targets: List<Resources>) {
            val current = loader ?: return
            val detached = ArrayList<Resources>(targets.size)
            var failure: Throwable? = null
            for (resources in targets) {
                try {
                    resources.removeLoaders(current)
                    detached += resources
                } catch (t: Throwable) {
                    if (failure == null) failure = t
                    EzReflect.logger.warn(TAG, "Failed to remove module ResourcesLoader: ${t.message}")
                }
            }
            if (failure != null) {
                for (resources in detached) {
                    runCatching { resources.addLoaders(current) }.onFailure {
                        EzReflect.logger.warn(TAG, "Failed to restore ResourcesLoader after detach failure")
                    }
                }
                throw IllegalStateException(
                    "Unable to detach the old module ResourcesLoader before hot reload",
                    failure,
                )
            }
            loader = null
            loaderFailed = false
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
        register(pkg, type, name, Replacement(Kind.MODULE_RES_ID, moduleResId))
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
        synchronized(resIdCacheLock) { resIdCache.clear() }
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
            checkNotNull(EzXposed.baseOrNull) {
                "EzResources requires EzXposed.initOnModuleLoaded to be called first."
            }
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
                handles += method.createHook {
                    reloadKey("ezhooktool.internal.resources.${method.name}/${method.parameterCount}")
                    before(::onResourcesGet)
                }
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
                handles += method.createHook {
                    reloadKey("ezhooktool.internal.typedarray.${method.name}/${method.parameterCount}")
                    before(::onTypedArrayGet)
                }
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

        if (injected.isEmpty()) {
            // 还没注入就补一次；只试一次，不在热路径上反复开文件。
            if (injectFailed) return
            val context = EzXposed.appContextOrNull ?: return
            if (!inject(context)) injectFailed = true
            if (injected.isEmpty()) return
        }

        val requestedId = param.args.getOrNull(0) as? Int ?: return
        if (requestedId == 0) return
        val hostResources = param.thisObjectOrNull as? Resources ?: return
        val methodName = param.executable.name

        for (moduleResources in injected) {
            val value = try {
                resolve(moduleResources, hostResources, methodName, param.args)
            } catch (_: Resources.NotFoundException) {
                continue
            } ?: continue

            val converted = convert(methodName, value)
            if (converted != null) {
                param.result = converted
            } else {
                if (mismatchWarned.add("$methodName ${value.javaClass.name}")) {
                    EzReflect.logger.warn(
                        TAG,
                        "Mismatched replacement type for $methodName: got ${value.javaClass.name}",
                    )
                }
            }
            return
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
                Kind.MODULE_RES_ID -> when (methodName) {
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

    private fun resolve(
        moduleResources: Resources,
        hostResources: Resources,
        method: String,
        args: Array<Any?>,
    ): Any? {
        val resId = args.getOrNull(0) as? Int ?: return null
        if (resId == 0) return null
        val key = resolveKey(hostResources, resId) ?: return null
        val replacement = findReplacement(key) ?: return null

        return when (replacement.kind) {
            Kind.VALUE -> asValue(replacement.value, method)
            Kind.DENSITY -> asDensity(replacement.value, hostResources, method)
            Kind.MODULE_RES_ID -> asModuleRes(replacement.value, moduleResources, method, args)
        }
    }

    private fun resolveKey(resources: Resources, resId: Int): ResKey? {
        synchronized(resIdCacheLock) {
            val table = resIdCache.getOrPut(resources) { SparseArray() }
            table.get(resId)?.let { return it.takeIf { k -> k != emptyKey } }
            if (table.size() >= ResIdCacheLimit) table.clear()
            val key = runCatching {
                ResKey(
                    resources.getResourcePackageName(resId),
                    resources.getResourceTypeName(resId),
                    resources.getResourceEntryName(resId),
                )
            }.getOrDefault(emptyKey)
            table.put(resId, key)
            return key.takeIf { it != emptyKey }
        }
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
        moduleResources: Resources,
        method: String,
        args: Array<Any?>,
    ): Any? {
        val moduleResId = (value as? Number)?.toInt() ?: return null
        if (moduleResId == 0) return null

        // 先确认模块里真有这个资源；没有会抛 NotFoundException，由调用点当成「不替换」处理。
        moduleResources.getResourceName(moduleResId)

        inReplacement.set(true)
        return try {
            when {
                (method == "getDrawable" || method == "getColorStateList") && args.size >= 2 ->
                    Methods.callMethod(moduleResources, method, moduleResId, args[1])

                (method == "getDrawableForDensity" || method == "getFraction") && args.size >= 3 ->
                    Methods.callMethod(moduleResources, method, moduleResId, args[1], args[2])

                else -> Methods.callMethod(moduleResources, method, moduleResId)
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

    // region 生命周期

    /**
     * 阻止热重载的原因；没有就是 `null`。注册过替换、或走过 `addAssetPath` 注入，都必须整进程重启：
     * 已 inflate 的 View 和缓存的资源不会跟着换代，`addAssetPath` 也没有摘除手段。[EzXposed.handleHotReloading] 会读这个值。
     */
    @JvmStatic
    val hotReloadBlockReason: String?
        get() = when {
            replacements.isNotEmpty() || appliedMask != 0 ->
                "EzResources has active resource replacements; already-inflated views and cached " +
                    "resources cannot be migrated. Restart the target process instead."

            legacyInjected ->
                "EzResources injected the module apk via AssetManager.addAssetPath, which cannot be " +
                    "detached. Restart the target process instead."

            else -> null
        }

    /**
     * 热重载换代前摘掉旧 apk 的 `ResourcesLoader`。不 unhook getter，它们带稳定 `reloadKey` 由新代原子替换。
     * 摘失败时抛出，调用方应拒绝本次热重载。
     */
    @JvmStatic
    fun prepareHotReload() {
        synchronized(lock) {
            mainHandler?.removeCallbacksAndMessages(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LoaderInjector.detachAll(injected.toList())
            }
            injected.clear()
            replacements.clear()
            synchronized(resIdCacheLock) { resIdCache.clear() }
            mainHandler = null
            appliedMask = 0
            injectFailed = false
            legacyInjected = false
            mismatchWarned.clear()
        }
    }

    // endregion
}
