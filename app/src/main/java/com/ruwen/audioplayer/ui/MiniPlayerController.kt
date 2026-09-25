package com.ruwen.audioplayer.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.displayName
import com.ruwen.audioplayer.service.LastPlayed
import java.io.File
import com.ruwen.audioplayer.service.PlaybackBinder
import com.ruwen.audioplayer.service.PlaybackPrefs
import com.ruwen.audioplayer.service.PlaybackService
import com.ruwen.audioplayer.ui.player.PlayerActivity
import com.ruwen.audioplayer.util.placeholderImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 底部常驻迷你播放栏的共用控制逻辑。
 *
 * MainActivity 与 PlaylistDetailActivity 都通过它来：绑定 [PlaybackService]、监听播放状态、
 * 刷新 UI、处理点击跳转与播放/暂停。
 *
 * 显示分三种状态：
 *  - **实时态**：PlaybackService 里确实装载了播放列表 → 标题/播放态/进度以服务为准；
 *  - **记忆态**：服务是空的（例如冷启动），但 PlaybackPrefs 里有「上次播放」快照
 *    → 显示上次的标题、强制暂停图标、进度归零。点整条打开播放页且**不自动播放**，
 *    点播放按钮则加载上次那首并开始播放；
 *  - 两者都没有 → 隐藏。
 *
 * 布局约定：迷你栏在两个列表页都**参与布局**（垂直 LinearLayout 的最后一项），
 * 而不是覆盖在列表之上。因此列表高度会自动缩短、不会被遮挡，本类**不需要**
 * 去调整 RecyclerView 的 paddingBottom 或 FAB 的 margin —— 那样做既容易累加出错，
 * 也会让底部留白随迷你栏高度一起膨胀。
 *
 * 设计约束（来自需求，禁止擅自扩展）：
 *  - 不引入拖动进度、歌单弹窗、滑动删除等额外功能；
 *  - 只记音频、不记播放进度（既不持久化也不恢复 currentPosition）；
 *  - 绑定/解绑成对，Activity 停止时停掉轮询，避免泄漏。
 */
/**
 * @param progressAsDivider 进度条是否兼作分隔线。
 *   **true**：迷你栏下面紧接底部导航（MainActivity）——进度条换成带灰轨道的样式，
 *   贴在迷你栏底边直接当作它与导航之间的分隔线，两块底部区域因此是同一图层、不需要阴影。
 *   **false**：迷你栏单独存在、下面没有导航（播放列表详情页 / 播客详情页）——
 *   沿用原来的普通进度线（无灰轨道），因为此时没有东西需要它去分隔。
 */
class MiniPlayerController(
    private val activity: AppCompatActivity,
    private val progressAsDivider: Boolean = false
) {
    private companion object {
        const val PROGRESS_MAX = 1000
        const val POLL_INTERVAL_MS = 500L
    }

    private var playbackService: PlaybackService? = null

    /**
     * 当前已绑定的播放服务（尚未绑定时为 null）。
     * 供宿主页面查询真实播放状态，例如音频列表里高亮「正在播放」的那一条。
     * 注意绑定是异步的，onStart 之后立刻取可能仍为 null，故调用方还要配合播放广播刷新。
     */
    fun getPlaybackService(): PlaybackService? = playbackService

    /**
     * 音频列表里应当高亮的音频 id。
     *
     * **必须与迷你栏自身的展示逻辑保持一致**（见 refreshAll 的 live / 记忆态两个分支）：
     *  - 服务已装载内容 → 用服务里当前那首；
     *  - 服务没有内容（**冷启动/关闭 App 重进就是这种情况**：服务是全新实例、playlist 为空）
     *    → 退回「上次播放」快照，也就是迷你栏的「记忆态」。
     *
     * 只取前者的话，重进后 getCurrentAudioItem() 为 null，列表不高亮，
     * 而迷你栏却显示着上次那首，两者对不上——看起来就像高亮丢了。
     */
    fun getCurrentHighlightAudioId(): Long {
        playbackService?.getCurrentAudioItem()?.id?.let { return it }
        return lastPlayedOrNull()?.audioId ?: -1L
    }

    /**
     * 服务绑定成功后的回调。
     *
     * 绑定是**异步**的：页面若在 onStart 里立刻查询播放状态，此时 playbackService 仍是 null，
     * 只能拿到空值；而暂停状态下后续不会再有播放广播来补刷一次，导致依赖播放状态的 UI
     * （例如列表里「正在播放」的高亮）时有时无。宿主应在此回调里再补一次刷新。
     */
    var onServiceConnected: (() -> Unit)? = null
    private var isBound = false
    private var receiverRegistered = false

    private var rootView: View? = null
    private var titleView: TextView? = null

    /** 贴底那根进度条：下方紧接底部导航时显示，兼作分隔线 */
    private var progressBar: ProgressBar? = null

    /** 标题下方那根进度条：迷你栏单独存在时显示（最早期的形态） */
    private var inlineProgressBar: ProgressBar? = null
    private var playPauseBtn: ImageButton? = null
    private var coverView: ImageView? = null

    /** 上一次渲染的播放态，避免每 500ms 重复 setImageResource */
    private var lastRenderedPlaying: Boolean? = null

    /**
     * 外部抑制开关：多选模式下由 Activity 置为 true，迷你栏强制隐藏，
     * 把底部位置让给多选操作栏（否则底部会同时出现两条栏）。
     * 赋值后立即刷新一次，不必等下一个轮询周期。
     */
    var suppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshAll()
        }

    /** 当前是否处于「记忆态」：服务没有装载内容，只靠 PlaybackPrefs 的「上次播放」快照在展示 */
    private var inMemoryMode: Boolean = false

    private var pollJob: Job? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as PlaybackBinder).getService()
            isBound = true
            // 绑定成功后立即刷新一次
            refreshAll()
            // 通知宿主：绑定是异步的，宿主要在这一刻补刷依赖播放状态的 UI
            onServiceConnected?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            playbackService = null
            setVisible(false)
        }
    }

    // 复用 PlaybackService 推送的播放状态广播（与 PlayerActivity 注册的 ACTION 一致）
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackService.ACTION_PLAYBACK_UPDATE) {
                refreshAll()
            }
        }
    }

    fun onStart() {
        rootView = activity.findViewById(R.id.mini_player_root)
        titleView = activity.findViewById(R.id.mini_player_title)
        progressBar = activity.findViewById(R.id.mini_player_progress)
        inlineProgressBar = activity.findViewById(R.id.mini_player_progress_inline)
        playPauseBtn = activity.findViewById(R.id.mini_player_play_pause)
        coverView = activity.findViewById(R.id.mini_player_icon)
        applyProgressStyle()

        // 先隐藏：绑定/轮询拿到真实状态后再决定是否显示
        setVisible(false)

        // 整条点击 → 打开播放页
        rootView?.setOnClickListener { openPlayer() }
        // 播放/暂停按钮：它本身是可点击 View，会消费这次点击，不会冒泡到整条的跳转；
        // 这里显式调用服务方法切换播放/暂停。
        playPauseBtn?.setOnClickListener {
            val service = playbackService
            if (inMemoryMode || service == null || !service.hasPlaylistLoaded()) {
                // 记忆态（或服务还没装载任何内容）：加载「上次播放」的那首并开始播放
                startLastPlayed()
            } else {
                service.togglePlayPause()
            }
        }

        // 绑定 PlaybackService（用 applicationContext，避免短生命周期的 Activity 造成泄露）
        val intent = Intent(activity.applicationContext, PlaybackService::class.java)
        activity.applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 监听播放状态广播
        val filter = IntentFilter(PlaybackService.ACTION_PLAYBACK_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(playbackReceiver, filter)
        }
        receiverRegistered = true

        // 协程统一挂在宿主 Activity 的 lifecycleScope 上：
        // 不再自建 CoroutineScope（自建就得自己记得 cancel，是漏点）；
        // 轮询仍需在 onStop 手动停，见 stopPolling()。
        refreshAll()
        startPolling()
    }

    fun onStop() {
        stopPolling()

        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(playbackReceiver) }
            receiverRegistered = false
        }
        if (isBound) {
            runCatching { activity.applicationContext.unbindService(serviceConnection) }
            isBound = false
        }
        playbackService = null
    }

    // ------------------------------------------------------------------
    //  刷新
    // ------------------------------------------------------------------

    private fun refreshAll() {
        // 多选模式：底部让给操作栏，强制隐藏（不改变内部状态，退出多选后会自动恢复）
        if (suppressed) {
            setVisible(false)
            return
        }

        val service = playbackService
        val live = service != null &&
            service.hasPlaylistLoaded() &&
            service.getCurrentAudioItem() != null

        if (live) {
            inMemoryMode = false
            setVisible(true)
            titleView?.text = service?.getCurrentAudioItem()?.displayName
            bindCover(service?.getCurrentAudioItem()?.coverPath)
            renderPlayPause(service?.isPlaying() == true)
            refreshProgress()
            return
        }

        // 服务里没有内容：退回「记忆态」，用「上次播放」的快照展示（冷启动走的就是这个分支）
        if (lastPlayedOrNull() == null) {
            inMemoryMode = false
            setVisible(false)
            return
        }
        inMemoryMode = true
        setVisible(true)
        titleView?.text = lastPlayedOrNull()?.title
        // 记忆态用快照里的封面路径展示（快照在播放时写入）；旧版本快照 / 无封面音频为 null，回退默认图标
        bindCover(lastPlayedOrNull()?.coverPath)
        renderPlayPause(false)      // 记忆态恒为暂停态
        progressBar?.progress = 0   // 不记进度
    }

    /**
     * 迷你栏封面：优先用音频自带的封面，没有就回退默认音符图标。
     *
     * 两处必须显式处理：
     *  - 加载真实图片前清掉 tint，否则封面会被染成灰色；
     *  - 回退时重新设回 tint，否则默认图标会是矢量图自身的黑色。
     */
    private fun bindCover(coverPath: String?) {
        val view = coverView ?: return
        val file = coverPath?.takeIf { it.isNotEmpty() }?.let(::File)
        if (file != null && file.exists()) {
            view.imageTintList = null
            val placeholder = view.context.placeholderImage(R.drawable.ic_music_note)
            view.load(Uri.fromFile(file)) {
                crossfade(true)
                placeholder(placeholder)
                error(placeholder)
            }
        } else {
            view.setImageResource(R.drawable.ic_music_note)
            view.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.text_secondary)
            )
        }
    }

    /**
     * 按场景切换两根进度条的显隐与标题行数（见构造函数 [progressAsDivider]）。
     *
     * 两根进度条的样式差异直接写在布局里（贴底那根=分隔线样式，行内那根=普通样式），
     * 这里**不换 drawable**——运行时换 drawable 需要重设 level 才可能重绘，
     * 之前就是因为这个在「单独场景」看起来像进度条消失了。只切显隐最稳。
     *
     * 标题：下方有底部导航时只多用 4dp 贴底进度条 → 允许两行；
     * 迷你栏单独存在时进度条在标题下方占一行 → 标题收成一行。
     */
    private fun applyProgressStyle() {
        titleView?.maxLines = if (progressAsDivider) 2 else 1
        if (progressAsDivider) {
            inlineProgressBar?.visibility = View.GONE
            progressBar?.visibility = View.VISIBLE
        } else {
            progressBar?.visibility = View.GONE
            inlineProgressBar?.visibility = View.VISIBLE
        }
    }

    /** 读取「上次播放」快照；读取失败（prefs 异常）时按没有处理，不能让迷你栏崩掉 */
    private fun lastPlayedOrNull(): LastPlayed? = runCatching {
        PlaybackPrefs(activity.applicationContext).getLastPlayed()
    }.getOrNull()

    private fun refreshProgress() {
        val service = playbackService ?: return
        val duration = service.getDuration()
        val position = service.getCurrentPosition()
        val max = progressBar?.max ?: PROGRESS_MAX
        val progress = if (duration > 0) {
            (position.toDouble() / duration * max).toInt().coerceIn(0, max)
        } else {
            0
        }
        // 两根进度条同步同一个值，具体显示哪根由场景决定（见 applyProgressStyle）
        progressBar?.progress = progress
        inlineProgressBar?.progress = progress
    }

    private fun renderPlayPause(isPlaying: Boolean) {
        if (lastRenderedPlaying == isPlaying) return
        lastRenderedPlaying = isPlaying
        playPauseBtn?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    /**
     * 控制迷你栏显隐。
     *
     * 迷你栏参与布局（不是覆盖层），显隐会让列表的可用高度自然增减，
     * 所以这里只需要切 visibility，不必再去补偿 RecyclerView 的 padding 或 FAB 的 margin。
     */
    private fun setVisible(visible: Boolean) {
        val root = rootView ?: return
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun openPlayer() {
        val service = playbackService
        val live = service != null && service.hasPlaylistLoaded()

        val targetPlaylistId: Long
        val targetIndex: Int
        val targetAudioId: Long
        val shouldAutoPlay: Boolean
        if (live) {
            targetPlaylistId = service!!.getCurrentPlaylistId()
            targetIndex = service.getCurrentPlaylistPosition()
            targetAudioId = service.getCurrentAudioItem()?.id ?: -1L
            shouldAutoPlay = true
        } else {
            val last = lastPlayedOrNull() ?: return
            targetPlaylistId = last.playlistId
            targetIndex = last.index
            targetAudioId = last.audioId
            // 记忆态进播放页：只装载不播放，保持与迷你栏一致的暂停态
            shouldAutoPlay = false
        }

        val intent = Intent(activity, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, targetPlaylistId)
            putExtra(PlayerActivity.EXTRA_START_INDEX, targetIndex)
            // 与列表点击一致：带上 audioId，队列与数据库不一致时按 id 定位才准
            putExtra(PlayerActivity.EXTRA_START_AUDIO_ID, targetAudioId)
            putExtra(PlayerActivity.EXTRA_AUTO_PLAY, shouldAutoPlay)
            // 与通知栏点击一致：回到已存在的播放页而非重建
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        activity.startActivity(intent)
    }

    /**
     * 加载「上次播放」的那首并开始播放（记忆态下点播放按钮走这里）。
     * PlaybackService 本身拿不到数据库，所以由这里先从 repository 查出列表再交给服务，
     * 与 PlayerActivity.attachOrStartPlayback 的做法一致。
     */
    private fun startLastPlayed() {
        val last = lastPlayedOrNull() ?: return
        val service = playbackService ?: return

        activity.lifecycleScope.launch {
            val items = runCatching {
                withContext(Dispatchers.IO) {
                    (activity.application as RuWenApplication)
                        .playlistRepository
                        .getAudioItemsByPlaylistSync(last.playlistId)
                }
            }.getOrDefault(emptyList())
            if (items.isEmpty()) return@launch

            // 列表可能被增删/重排：先用 audioId 重新定位，定位不到才退回存的下标
            val found = items.indexOfFirst { it.id == last.audioId }
            val index = if (found >= 0) found else last.index.coerceIn(0, items.lastIndex)

            service.setPlaylist(items, last.playlistId, index)
            service.play()
            inMemoryMode = false
            refreshAll()
        }
    }

    // ------------------------------------------------------------------
    //  轻量轮询：保证进度条与播放态实时（Activity 停止时必须停掉）
    // ------------------------------------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = activity.lifecycleScope.launch {
            while (isActive) {
                refreshProgress()
                playbackService?.let { renderPlayPause(it.isPlaying()) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
