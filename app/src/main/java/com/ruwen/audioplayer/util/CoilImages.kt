package com.ruwen.audioplayer.util

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import coil3.Image
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.dispose
import coil3.load
import coil3.request.crossfade

/**
 * Coil 3 的 placeholder / error **只接受 [Image]，不接受 `@DrawableRes Int`**，
 * 这里统一做一次转换，免得每个适配器各写一遍 `AppCompatResources + asImage`。
 */
fun Context.placeholderImage(@DrawableRes resId: Int): Image? =
    AppCompatResources.getDrawable(this, resId)?.asImage()

/**
 * **远程封面的统一加载入口**（播客封面 / 单集封面 / 搜索结果 / 预览页）。
 *
 * 收口到一个函数是因为这类调用有 6 处，散开写必然写漏，已经漏过一次：
 * 其中 3 处写成 `url?.takeIf { it.isNotBlank() }?.let { load(...) }`，
 * url 为空时**压根不发起加载**，于是 ImageView 保留上一张图——
 * ViewHolder 复用时就表现为「封面串位 / 该消失的不消失」。
 *
 * 两条硬性约定：
 *  1. **url 为空必须显式收尾**：先 `dispose()` 再设占位图。
 *     只 `setImageResource(占位图)` 不够——上一次请求仍持有这个 ImageView，
 *     它完成后会把旧封面写回来（与本地封面的串位是同一个坑）。
 *  2. **显式指定 `diskCacheKey = url`**：这样取消订阅时才能按 URL 精确删除缓存条目
 *     （见 [purgeRemoteCovers]）；不指定的话键由 Coil 内部生成，外部无从下手。
 */
fun ImageView.loadRemoteCover(url: String?, @DrawableRes placeholderRes: Int) {
    if (url.isNullOrBlank()) {
        dispose()
        setImageResource(placeholderRes)
        return
    }
    val placeholder = context.placeholderImage(placeholderRes)
    load(url) {
        crossfade(true)
        placeholder(placeholder)
        error(placeholder)
        diskCacheKey(url)
    }
}

/**
 * 删除这些 URL 的**磁盘缓存**条目（取消订阅时调用）。
 *
 * 只清磁盘缓存：内存缓存是进程内的 LRU，随进程退出即消失，且 UI 已不再展示这些封面，
 * 强行清反而会打断正在显示的图片。
 */
fun Context.purgeRemoteCovers(urls: Collection<String>) {
    if (urls.isEmpty()) return
    val diskCache = SingletonImageLoader.get(this).diskCache ?: return
    urls.forEach { url ->
        if (url.isNotBlank()) diskCache.remove(url)
    }
}
