package com.ruwen.audioplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.ruwen.audioplayer.R
import java.io.File
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.displayName
import com.ruwen.audioplayer.ui.player.PlayerActivity

/**
 * 播放服务
 *
 * 设计要点：
 *  - 播放器为 [ExoPlayer]，音频焦点完全交给 ExoPlayer 自己管理
 *    （[ExoPlayer.Builder.setAudioAttributes] 的 handleAudioFocus=true）。
 *    不要再手动 requestAudioFocus：那会与 ExoPlayer 内部的焦点管理互相抢焦点，
 *    导致「点了播放但立刻被判为暂停」这类状态错乱。
 *  - 服务以 startService 方式常驻，Activity 退出后仍继续播放；
 *    Activity 重新进入时只「附着」到当前播放状态，不重新开始（见 [hasPlaylistLoaded]）。
 *  - 循环（repeat）与随机（shuffle）是两个彼此独立的开关，与主流播放器一致。
 *
 * TODO(架构演进)：Google 官方推荐形态是 MediaSessionService + MediaController
 * （https://developer.android.com/media/media3/session/background-playback），
 * 由 MediaController 天然处理「重连到已存在的会话」，通知与媒体按键也由框架托管。
 * 当前为保持既有的绑定式服务结构，用「附着而非重启」的方式达到同样的用户可感知行为。
 */
class PlaybackService : Service() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerRemaining: Long = 0

    private var playlist: List<AudioItem> = emptyList()
    private var playlistId: Long = -1L
    private var currentIndex: Int = 0

    /**
     * 最近一次被要求播放的「播放列表顺序」下标。
     * 开启随机播放后 ExoPlayer 的 currentMediaItemIndex 表示的是随机后的顺序，
     * 不能直接和播放列表下标比较，所以单独记录一份用于「重新进入是否要切歌」的判断。
     */
    private var lastRequestedIndex: Int = 0

    // 默认值（冷启动且 prefs 无记录时）为「不循环」(NONE)。
    // 服务创建时会先从 SharedPreferences 恢复用户上次设置的值（见 onCreate）。
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var shuffleEnabled: Boolean = false
    private var sleepTimerMode: SleepTimerMode = SleepTimerMode.NONE

    // NONE 循环模式下的「单曲播完即停」状态：
    //  - endedIndex：刚自然播完的那首在播放列表中的下标，用于把它 seek 回末尾并停住；
    //  - naturalStop：是否已处在「自然播完当前曲、已暂停停在曲尾」状态，
    //    此时用户再点播放应当前进到下一首，而不是从曲尾重启当前曲（否则会立刻又被判停、死循环）。
    private var endedIndex: Int = -1
    private var naturalStop: Boolean = false

    /**
     * 循环模式（三态）：
     *  - NONE      不循环：播完「当前这一首」即暂停停在该曲末尾（不自动续播下一首）。
     *              底层仍用 ExoPlayer REPEAT_MODE_OFF，并在自然切歌时回退到本曲末尾暂停。
     *  - SINGLE     单曲循环（REPEAT_MODE_ONE）
     *  - LIST_LOOP  列表循环（REPEAT_MODE_ALL）
     * 顺序即 enum 声明顺序；EXTRA_REPEAT_MODE 只传 ordinal，全项目共用这一份枚举，收发一致。
     */
    enum class RepeatMode { NONE, SINGLE, LIST_LOOP }

    /**
     * 睡眠定时模式：无 / 倒计时。
     *
     * 原先还有一个 END_OF_TRACK（播完当前这一首后停止），但它与「关闭循环」时的行为重复，
     * 已下线（用户决定），定时一律走倒计时。
     */
    enum class SleepTimerMode { NONE, COUNTDOWN }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            updateNotification()
            sendPlaybackUpdate()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            // 诊断：播放被「非用户操作」停掉时，光看 isPlaying 看不出原因，
            // playbackSuppressionReason 才能区分是音频焦点被抢还是别的原因。
            updateNotification()
            sendPlaybackUpdate()
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            currentIndex = exoPlayer?.currentMediaItemIndex ?: 0
            // 同步"上次请求的索引"：next()/previous() 走 ExoPlayer 直接切歌、不会调用 playAt，
            // 若不在这里同步，lastRequestedIndex 会停留在切歌前的旧值，导致播放页 attachOrStartPlayback
            // 误判“点了另一首”而对正在播的这首从头重播（返回列表再点该音频 / 点迷你栏都会触发）。
            lastRequestedIndex = currentIndex
            // 循环=NONE：自然播完当前曲后立即停在当前曲末尾（不自动续播下一首）。
            // 注意此时 ExoPlayer 已经自动切到了下一首（reason=AUTO），currentIndex 已是下一首下标，
            // 所以要把「刚播完的那首」(endedIndex) seek 回末尾并暂停，画面才能停在刚播完的这首。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                repeatMode == RepeatMode.NONE &&
                endedIndex >= 0
            ) {
                // NONE 播完停在「曲头」（位置 0）：用户确认停在开头比停在末尾更符合预期。
                //
                // 注意两点：
                // 1. 不要试图用 Long.MAX_VALUE 停在曲尾 —— ExoPlayer 内部会做 msToUs（×1000），
                //    Long.MAX_VALUE 溢出成负数后被夹到 0，结果不可预期；
                // 2. 也不要用 AudioItem.duration —— 该字段历史上恒为 0、且新导入才会采集，
                //    一旦被填上真实时长，这里就会变成「停在曲尾」，偏离预期行为。
                //    要停在曲头就显式传 0。
                exoPlayer?.seekTo(endedIndex, 0)
                // 已回退到刚播完的这首，同步服务端下标，避免状态仍指向「下一首」
                //（否则通知/播放页会显示下一首的信息，而实际装载的是刚播完这首）。
                currentIndex = endedIndex
                lastRequestedIndex = endedIndex
                pause()
                naturalStop = true
            }
            // 记住「上次播放」：自动切到下一首/上一首后，快照当前曲目。
            // 注意这里必须用 this@PlaybackService —— 本回调位于 Player.Listener 匿名对象内，
            // 裸 this 指的是监听器而不是 Context。
            // 随机播放只改变「下一首走哪条路」，currentMediaItemIndex 始终返回时间线（即
            // setMediaItems 时传入的播放列表）里的原始下标，故可直接用于定位。
            // 下面仍优先用切到的 mediaItem 的 mediaId（装载时写的是 item.id）反查做双保险，
            // 查不到才退回下标。
            val transitionId = mediaItem?.mediaId?.toLongOrNull()
            val transitionItem = transitionId?.let { id -> playlist.firstOrNull { it.id == id } }
                ?: playlist.getOrNull(currentIndex)
            transitionItem?.let { item ->
                val idx = playlist.indexOf(item).takeIf { it >= 0 } ?: currentIndex
                PlaybackPrefs(this@PlaybackService)
                    .saveLastPlayed(item.playlistId, item.id, idx, item.displayName, item.coverPath)
            }
            // 更新「上一首」下标，供下一次自然切歌回退；所有切歌（含手动 SEEK）都走到这里。
            endedIndex = exoPlayer?.currentMediaItemIndex ?: currentIndex
            // 必须在这里刷通知：自动切下一首时 isPlaying / playbackState 都没变，
            // onIsPlayingChanged 与 onPlaybackStateChanged 都不会触发，
            // 若只靠那两个回调，通知与锁屏会一直显示上一首的标题。
            updateNotification()
            sendPlaybackUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 冷启动恢复：优先用用户上次设置的循环模式，读不到/非法才回退 NONE。
        // 必须早于 initializePlayer，否则 ExoPlayer 的初始 repeatMode 会和服务端不一致。
        repeatMode = PlaybackPrefs(this).getRepeatMode()
        initializePlayer()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                becomingNoisyReceiver,
                IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                becomingNoisyReceiver,
                IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
        }
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            // handleAudioFocus = true：音频焦点（含被其他应用抢占时暂停）由 ExoPlayer 统一管理
            setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            // 持本地唤醒锁：默认 WAKE_MODE_NONE 时，息屏/锁屏后 CPU 休眠，音频会跟着停。
            // Media3 官方对「要在屏幕关闭时继续播放」的做法就是设 WAKE_MODE_LOCAL
            // （Manifest 里的 android.permission.WAKE_LOCK 早先已声明，但代码里从没用过）。
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = toExoRepeatMode(this@PlaybackService.repeatMode)
            addListener(playerListener)
        }

        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            // 锁屏 / 快捷设置上的媒体控件点进来要回到播放页。
            // 官方称之为 session activity：SystemUI、蓝牙设备、语音助手都是通过它唤起 UI 的
            // （https://developer.android.com/media/media3/session/background-playback）。
            .setSessionActivity(createSessionActivityIntent())
            .build()
    }

    /**
     * MediaSession 的 session activity：供 SystemUI / 蓝牙 / 语音助手唤起播放页。
     *
     * 与通知的 contentIntent 分开：通知那一条要带上当前 playlistId / index 做「附着」，
     * 而 session activity 只负责把用户带回播放页（页面自己会附着到正在播放的状态）。
     */
    private fun createSessionActivityIntent(): PendingIntent {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 单条音频的 MediaMetadata。
     *
     * 官方明确的做法是「把元信息填进 MediaItem.MediaMetadata」——MediaSession 会自动把它
     * 同步给 SystemUI，锁屏 / 快捷设置的媒体控件、蓝牙设备、Android Auto 都从这里取标题与封面
     * （https://developer.android.com/media/media3/session/background-playback：
     *  "The metadata about the currently playing item can be customized by modifying the
     *   MediaItem.MediaMetadata"）。
     *
     * 所以**不要在通知里另写一份标题**：通知只是载体，真正的元信息来源是这里。
     */
    private fun buildMediaMetadata(item: AudioItem): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(item.displayName)
            .setArtist(getString(R.string.app_name))
            .setArtworkUri(
                item.coverPath?.takeIf { it.isNotBlank() }?.let { Uri.fromFile(File(it)) }
            )
            .build()

    // ----------------------------------------------------------------------
    //  播放列表 / 播放控制
    // ----------------------------------------------------------------------

    /**
     * 装载播放列表，从 [startIndex] 的 [startPositionMs] 处开始。
     *
     * 仅当确实需要「换一张播放列表 / 队列与数据库不一致」时才调用。
     * [startPositionMs] 用于**刷新队列时不打断当前播放**：例如导入新音频后重新装载，
     * 把正在播的那首与它的进度一起带过去，不会从头重放。
     */
    fun setPlaylist(
        items: List<AudioItem>,
        id: Long,
        startIndex: Int = 0,
        startPositionMs: Long = 0L
    ) {
        playlist = items
        playlistId = id
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        lastRequestedIndex = currentIndex
        endedIndex = currentIndex
        naturalStop = false

        exoPlayer?.run {
            // 用音频 id 作为 mediaId：随机播放打乱顺序后仍能反查回原始 AudioItem
            setMediaItems(items.map { item ->
                MediaItem.Builder()
                    .setUri(item.filePath)
                    .setMediaId(item.id.toString())
                    .setMediaMetadata(buildMediaMetadata(item))
                    .build()
            }, currentIndex, startPositionMs)
            repeatMode = toExoRepeatMode(this@PlaybackService.repeatMode)
            shuffleModeEnabled = shuffleEnabled
            prepare()
        }
        // 记住「上次播放」：装载即记当前曲目（index 已 clamp 到合法范围）
        items.getOrNull(currentIndex)?.let { item ->
            PlaybackPrefs(this).saveLastPlayed(item.playlistId, item.id, currentIndex, item.displayName, item.coverPath)
        }
        sendPlaybackUpdate()
    }

    fun getLastRequestedIndex(): Int = lastRequestedIndex

    /**
     * 已装载队列里的音频 id 序列（顺序即队列顺序）。
     *
     * 播放页用它判断「队列是否还是数据库里那一版」：导入新音频后，
     * 服务里这份快照不会自动变新，若只比 playlistId 就会用旧队列去解释新的下标，
     * 表现为「点了新导入的音频没反应，一直播旧的那首」。
     */
    fun getPlaylistAudioIds(): List<Long> = playlist.map { it.id }

    /** 服务中是否已经装载过播放列表 */
    fun hasPlaylistLoaded(): Boolean = playlist.isNotEmpty()

    /** 已装载的播放列表 id，用于判断「重新进入」时是否需要换列表 */
    fun getCurrentPlaylistId(): Long = playlistId

    fun getPlaylistSize(): Int = playlist.size

    /** 切换到播放列表中的第 [index] 首并开始播放（不重建播放列表） */
    fun playAt(index: Int) {
        if (index < 0 || index >= playlist.size) return
        currentIndex = index
        lastRequestedIndex = index
        naturalStop = false
        // 记住「上次播放」：切歌并播放时记当前曲目
        playlist.getOrNull(currentIndex)?.let { item ->
            PlaybackPrefs(this).saveLastPlayed(item.playlistId, item.id, currentIndex, item.displayName, item.coverPath)
        }
        // seekTo(index, ...) 用的是「当前播放顺序」的下标；开启随机时它与播放列表下标不同，
        // 所以先按 mediaId 找到它在当前播放顺序中的位置，再跳转，保证切到的是用户点的那一首。
        val target = exoPlayer?.let { player ->
            val wantedId = playlist[index].id.toString()
            for (i in 0 until player.mediaItemCount) {
                if (player.getMediaItemAt(i).mediaId == wantedId) return@let i
            }
            index
        } ?: index
        exoPlayer?.seekTo(target, 0)
        play()
    }

    fun play() {
        // 先置为 started，再起播：顺序不能反，否则窗口期里退后台仍会被解绑销毁
        ensureStarted()
        exoPlayer?.play()
        startForegroundCompat()
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            // 这里绝不能再静默吞异常：startForeground 一旦失败，服务就**不是**前台服务，
            // 退后台后会被系统回收（表现为「一退后台播放就停、岛也跟着消失」）。
            Log.e(TAG, "startForeground 失败，服务未进入前台状态！", e)
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * 让服务进入 **started** 状态（`startRequested=true`）。
     *
     * 这是后台播放能不能续下去的关键：只靠 `bindService(BIND_AUTO_CREATE)` 拉起的服务，
     * 连接数归零就会被系统**立刻销毁**（实测 logcat：
     * `unbindService` → `connections after removal is 0` → `destroyService` → `onDestroy`），
     * 表现为「一退后台播放就停、锁屏/岛上的播放器跟着消失」。
     * 而 [PlayerActivity] 里那句 `startService` 只在打开播放页时才走得到——
     * **从迷你播放栏 / 列表页直接起播时根本不经过它**，所以这里必须兜住。
     *
     * 重复调用无害：服务已在运行，`startService` 只是再派发一次空 action 的 onStartCommand。
     */
    private fun ensureStarted() {
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
            .onFailure {
                // 后台调用会被 Android 8+ 拒绝。此时服务本来就已经在跑（正是它收到的指令），
                // 所以失败不需要处理，只记一笔便于排查。
                Log.w(TAG, "startService(self) 被拒绝（多半是后台调用），服务已在运行", it)
            }
    }

    fun togglePlayPause() {
        if (exoPlayer?.isPlaying == true) {
            pause()
        } else {
            // 处在「自然播完当前曲、暂停在曲尾」状态时，再点播放应当把**这首**从头重播，
            // 而不是跳到下一首：用户看到的仍是刚播完的这首（进度条停在它的末尾），
            // 点播放自然期望重听它。
            // 同时必须先回退到开头：若停在曲尾直接 play()，会立刻再次被判定为播完而反复停住。
            if (naturalStop) {
                naturalStop = false
                exoPlayer?.seekTo(endedIndex.coerceAtLeast(0), 0)
            }
            play()
        }
    }

    fun next() {
        naturalStop = false
        exoPlayer?.seekToNextMediaItem()
    }

    fun previous() {
        naturalStop = false
        exoPlayer?.let {
            // 与主流播放器一致：播放超过 3 秒时「上一首」先回到本曲开头
            if (it.currentPosition > 3000) {
                it.seekTo(0)
            } else {
                it.seekToPreviousMediaItem()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        naturalStop = false
        exoPlayer?.seekTo(positionMs)
    }

    // ----------------------------------------------------------------------
    //  循环 / 随机
    // ----------------------------------------------------------------------

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        // 立即持久化，保证「重开 App / 重新进播放页」后仍是这次设置的值
        PlaybackPrefs(this).saveRepeatMode(mode)
        exoPlayer?.repeatMode = toExoRepeatMode(mode)
        sendPlaybackUpdate()
    }

    fun getRepeatMode(): RepeatMode = repeatMode

    /**
     * 随机播放开关。
     * 与循环模式互斥地独立维护：开启随机时不再改动循环模式，
     * 关闭随机也绝不会把「单曲循环」偷偷改掉。
     */
    fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        exoPlayer?.shuffleModeEnabled = enabled
        sendPlaybackUpdate()
    }

    fun isShuffleEnabled(): Boolean = shuffleEnabled

    private fun toExoRepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
        RepeatMode.SINGLE -> Player.REPEAT_MODE_ONE
        RepeatMode.LIST_LOOP -> Player.REPEAT_MODE_ALL
    }

    // ----------------------------------------------------------------------
    //  状态读取
    // ----------------------------------------------------------------------

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0
    fun getDuration(): Long = exoPlayer?.duration ?: 0
    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true
    /**
     * 当前正在播放的音频。优先按 mediaId 反查，保证随机播放下拿到的也是正确的一首
     * （直接用 currentMediaItemIndex 取 playlist 会错位）。
     */
    fun getCurrentAudioItem(): AudioItem? {
        val id = exoPlayer?.currentMediaItem?.mediaId?.toLongOrNull()
        if (id != null) {
            playlist.firstOrNull { it.id == id }?.let { return it }
        }
        return playlist.getOrNull(currentIndex)
    }

    fun getCurrentIndex(): Int = currentIndex

    /** 当前曲目在播放列表中的序号（用于 “3 / 12” 这类展示） */
    fun getCurrentPlaylistPosition(): Int {
        val item = getCurrentAudioItem() ?: return currentIndex
        val idx = playlist.indexOfFirst { it.id == item.id }
        return if (idx >= 0) idx else currentIndex
    }

    // ----------------------------------------------------------------------
    //  睡眠定时
    // ----------------------------------------------------------------------

    /** 倒计时定时：N 分钟后自动暂停 */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimerInternal()
        val total = minutes * 60 * 1000L
        sleepTimerRemaining = total
        sleepTimerMode = SleepTimerMode.COUNTDOWN
        sleepTimer = object : CountDownTimer(total, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerRemaining = millisUntilFinished
                sendSleepTimerUpdate()
            }

            override fun onFinish() {
                pause()
                sleepTimerRemaining = 0
                sleepTimerMode = SleepTimerMode.NONE
                sendSleepTimerUpdate()
            }
        }.start()
        sendSleepTimerUpdate()
    }

    fun cancelSleepTimer() {
        cancelSleepTimerInternal()
        sendSleepTimerUpdate()
    }

    private fun cancelSleepTimerInternal() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerRemaining = 0
        sleepTimerMode = SleepTimerMode.NONE
    }

    fun getSleepTimerRemaining(): Long = sleepTimerRemaining
    fun isSleepTimerActive(): Boolean = sleepTimerMode != SleepTimerMode.NONE
    fun getSleepTimerMode(): SleepTimerMode = sleepTimerMode

    // ----------------------------------------------------------------------
    //  广播：把播放状态推给 UI
    // ----------------------------------------------------------------------

    private fun sendPlaybackUpdate() {
        val intent = Intent(ACTION_PLAYBACK_UPDATE).apply {
            // 必须显式指定包名：targetSdk 34（Android 14）上，RECEIVER_NOT_EXPORTED 的
            // 运行时接收器收不到「隐式」广播——系统静默丢弃、不报错，表现为切歌后
            // 播放页标题/曲目计数不刷新。setPackage 后成为发给自己包的显式广播，投递才生效。
            setPackage(packageName)
            putExtra(EXTRA_IS_PLAYING, isPlaying())
            putExtra(EXTRA_POSITION, getCurrentPosition())
            putExtra(EXTRA_DURATION, getDuration())
            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            putExtra(EXTRA_REPEAT_MODE, repeatMode.ordinal)
            putExtra(EXTRA_SHUFFLE_ENABLED, shuffleEnabled)
        }
        sendBroadcast(intent)
    }

    private fun sendSleepTimerUpdate() {
        val intent = Intent(ACTION_SLEEP_TIMER_UPDATE).apply {
            // 同 sendPlaybackUpdate：Android 14 上隐式广播无法投递给 NOT_EXPORTED 接收器
            setPackage(packageName)
            putExtra(EXTRA_SLEEP_TIMER_REMAINING, sleepTimerRemaining)
            putExtra(EXTRA_SLEEP_TIMER_MODE, sleepTimerMode.ordinal)
        }
        sendBroadcast(intent)
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    // ----------------------------------------------------------------------
    //  通知
    // ----------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_playback_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val currentItem = getCurrentAudioItem()
        val title = currentItem?.displayName ?: getString(R.string.app_name)
        val playing = isPlaying()

        // 点击通知回到播放页时带上当前 playlistId / index，
        // PlayerActivity 会「附着」到正在播放的状态，而不是从头重放
        val contentIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, playlistId)
            putExtra(PlayerActivity.EXTRA_START_INDEX, getCurrentPlaylistPosition())
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (playing) getString(R.string.now_playing) else getString(R.string.notif_paused))
            // 通知由 SystemUI 解析，必须使用不含 ?attr/ 的纯色矢量图
            .setSmallIcon(R.drawable.ic_notification)
            // 专辑封面：MediaStyle 会把 largeIcon 当作封面展示（通知栏大图 / 超级岛的媒体卡）。
            .apply { loadAlbumArt()?.let { setLargeIcon(it) } }
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(playing)
            // 通知内的图标一律用 ic_notif_* ：这些图标由 SystemUI 解析，
            // 不能带 ?attr/ 主题属性，否则解析阶段就会抛 Failed to resolve attribute
            .addAction(
                R.drawable.ic_notif_previous, getString(R.string.previous_track),
                actionPendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (playing) getString(R.string.notif_action_pause) else getString(R.string.notif_action_play),
                actionPendingIntent(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(
                R.drawable.ic_notif_next, getString(R.string.next_track),
                actionPendingIntent(ACTION_NEXT)
            )
            // 套用 Media3 的 MediaStyle：这一步是「锁屏 / 快捷设置出现媒体控件」的关键。
            // 官方（https://developer.android.com/media/implement/surfaces/mobile）的原话是
            // SystemUI 靠它把这条通知识别成「一个活动的媒体会话」，并据此在锁屏上展示专辑图与控件。
            // 少了它，前面的 addAction 只是一排普通通知按钮，锁屏上什么都不会有。
            .apply {
                val session = mediaSession
                if (session != null) {
                    setStyle(
                        MediaStyleNotificationHelper.MediaStyle(session)
                            // 折叠态也保留这三个 transport 按钮（下标按 addAction 的顺序）
                            .setShowActionsInCompactView(0, 1, 2)
                    )
                }
            }
            .build()
    }

    /**
     * 读当前曲目的封面，作为通知的 largeIcon（MediaStyle 会把它当成专辑封面）。
     *
     * 直接用 [CoverStore] 存下的那张 256px JPEG，不再额外降采样：它落盘时就已是这个尺寸，
     * 单次解码在 10ms 量级。这里只解**当前这一首**，且只在通知重建时（播放/暂停/切歌）
     * 发生，不会像「装载播放列表时为每一条都解码」那样拖慢主线程。
     * 解码失败（文件被外部删掉、不是合法图片）返回 null，通知回退成没有封面的样子。
     */
    private fun loadAlbumArt(): Bitmap? {
        val path = getCurrentAudioItem()?.coverPath?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            BitmapFactory.decodeFile(path)?.takeIf { it.width > 0 && it.height > 0 }
        }.getOrNull()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        if (isPlaying()) {
            startForegroundCompat()
            return
        }
        // 暂停时退出前台状态，但保留通知——用户仍需能从通知栏恢复播放。
        // （Media3 的 MediaSessionService 也是这个行为：暂停不撤通知。）
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification())
        }
    }

    // ----------------------------------------------------------------------
    //  生命周期
    // ----------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = PlaybackBinder(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer?.cancel()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        mediaSession?.release()
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
    }

    companion object {
        private const val TAG = "PlaybackService"

        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAYBACK_UPDATE = "com.ruwen.audioplayer.PLAYBACK_UPDATE"
        const val ACTION_SLEEP_TIMER_UPDATE = "com.ruwen.audioplayer.SLEEP_TIMER_UPDATE"

        // 通知/媒体按键指令
        const val ACTION_PLAY = "com.ruwen.audioplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.ruwen.audioplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.ruwen.audioplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.ruwen.audioplayer.ACTION_PREVIOUS"

        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_CURRENT_INDEX = "current_index"
        const val EXTRA_REPEAT_MODE = "repeat_mode"
        const val EXTRA_SHUFFLE_ENABLED = "shuffle_enabled"
        const val EXTRA_SLEEP_TIMER_REMAINING = "sleep_timer_remaining"
        const val EXTRA_SLEEP_TIMER_MODE = "sleep_timer_mode"
    }
}

class PlaybackBinder(private val service: PlaybackService) : android.os.Binder() {
    fun getService(): PlaybackService = service
}
