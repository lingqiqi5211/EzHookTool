package io.github.lingqiqi5211.ezhooktool.core

/**
 * 反射缓存的只读计数快照，由 [EzReflect.cacheStats] 读取。
 *
 * 只含数字，不引用 Class、ClassLoader、成员对象、配置或异常，因此读一次不会延长任何加载器的存活。
 * 计数属于一代缓存状态：`init`、`reset`、`clearCache` 和配置切换会发布新状态，计数从零开始，
 * 可用 [generation] 判断快照是否来自同一次状态发布。
 *
 * @property generation 发布该状态时递增的代次
 * @property entries 状态当前的缓存条目数，成员与类查询合计不超过 [capacity]
 * @property capacity 该状态的缓存条目上限
 * @property hits 命中次数，含类查询
 * @property misses 未命中次数；缓存关闭或状态退休后的查找不计入
 * @property evictions 因超出 [capacity] 而淘汰的条目数
 * @property skippedOversizeResults 因集合结果超过上限而跳过登记的次数
 */
data class ReflectCacheStats(
    val generation: Long,
    val entries: Int,
    val capacity: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val skippedOversizeResults: Long,
)
