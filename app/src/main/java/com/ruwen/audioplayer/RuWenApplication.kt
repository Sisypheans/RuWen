package com.ruwen.audioplayer

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.ruwen.audioplayer.BuildConfig
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.ruwen.audioplayer.data.db.AppDatabase
import java.io.File
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import com.ruwen.audioplayer.data.db.RoomTransactionRunner
import com.ruwen.audioplayer.data.repository.CoverBackfill
import com.ruwen.audioplayer.data.repository.PlaylistRepository
import com.ruwen.audioplayer.data.repository.PodcastRepository
import com.ruwen.audioplayer.data.repository.PodcastSearchRepository
import com.ruwen.audioplayer.whisper.WhisperManager

class RuWenApplication : Application(), ComponentCallbacks2, SingletonImageLoader.Factory {

    val database by lazy { AppDatabase.getDatabase(this) }

    private val transactionRunner by lazy { RoomTransactionRunner(database) }

    val playlistRepository by lazy {
        PlaylistRepository(database.playlistDao(), database.audioItemDao(), transactionRunner)
    }
    val podcastRepository by lazy {
        PodcastRepository(database.podcastDao(), transactionRunner, this)
    }
    val podcastSearchRepository by lazy { PodcastSearchRepository(this) }

    /**
     * 缺失封面的事后补下载（播客详情页刷新时触发）。
     * 复用图片专用的带 UA 客户端：图床不像 RSS 那么宽容，缺 UA 更容易被拒。
     */
    val coverBackfill by lazy {
        CoverBackfill(database.audioItemDao(), database.podcastDao(), imageHttpClient)
    }

    companion object {
        private const val TAG = "RuWenApplication"

        /** 图片内存缓存上限：可用内存的 25%（Coil 官方示例的取值） */
        private const val MEMORY_CACHE_PERCENT = 0.25

        /**
         * 图片磁盘缓存上限：1GB。
         *
         * 为什么给这么大：部分源（如 omnycontent）**每集一张独立的 1MB 大图**，
         * 一个 73 集的播客就要 75MB。上限太小会进入 LRU 淘汰抖动——
         * 来回切页面时后面的图把前面的挤掉，每次都得重新下载。
         * 这是**上限不是预分配**：实际占用只跟随真实缓存量，不会一上来就吃掉 1GB。
         */
        private const val DISK_CACHE_BYTES = 1024L * 1024 * 1024

        /** 图片请求带的 UA：不带 UA 会被部分图床/CDN 拒绝（OkHttp 默认不发 UA） */
        private const val IMAGE_USER_AGENT = "RuWen/${BuildConfig.VERSION_NAME} (Android)"

        private var instance: RuWenApplication? = null

        fun getInstance(): RuWenApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 清理早期版本留在 cacheDir 的图片缓存（1.1.5 及更早用的目录）。
        // 现在缓存在 filesDir，旧目录已无人引用，留着只是白占空间。
        runCatching { File(cacheDir, "image_cache").deleteRecursively() }
    }

    /**
     * 图片专用的 OkHttp 客户端。
     *
     * 唯一目的是补一个 `User-Agent`：OkHttp 默认不带 UA，个别图床 / CDN（含部分防盗链
     * 与 Cloudflare 的默认规则）会直接拒绝，表现为「明明有网，封面就是不出来」。
     * 只用于图片请求，不影响 RSS / 下载（它们各自建客户端）。
     */
    private val imageHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", IMAGE_USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * 全局图片加载器（Coil 3 官方推荐接入方式：Application 实现 SingletonImageLoader.Factory）。
     *
     * 必须显式配置三件事，缺一个远程封面就显示不出来或留不住：
     * 1. **网络取图器**：Coil 3 把网络加载改成 opt-in，默认的 ImageLoader **不带**任何
     *    NetworkFetcher，http(s) 的图根本取不到（跟有没有网、有没有代理无关）；
     *    必须 `components { add(OkHttpNetworkFetcherFactory(...)) }`。
     * 2. **磁盘缓存**：默认 ImageLoader 只有内存缓存，图片每次冷启动都要重新下载。
     * 3. **缓存目录放 filesDir 而非 cacheDir**：放在 cacheDir 时，系统「设置 → 清除缓存」
     *    或存储紧张时的自动回收会把封面整批清掉（这正是「封面莫名其妙消失」的来源之一）。
     *    filesDir 只在卸载 / 清除数据时才消失。
     *    （App 已声明 `allowBackup="false"`，所以这些缓存不会被云备份带上。）
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient })) }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(filesDir, "image_cache").absolutePath.toPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()

    /**
     * 内存回收钩子：把常驻的 whisper 模型上下文放掉。
     *
     * 模型加载很贵（几百 MB + 数秒），所以平时常驻；但切到后台 / 系统报内存偏低时
     * 继续占着，容易被 LMK 杀进程。这里只在**没有生成任务**时释放
     * （[WhisperManager.releaseModelIfIdle] 内部判断），下次生成会自动重新加载。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.i(TAG, "onTrimMemory(level=$level)：尝试释放 whisper 模型")
            WhisperManager.getInstance(this).releaseModelIfIdle()
        }
    }
}
