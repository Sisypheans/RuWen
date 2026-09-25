package com.ruwen.audioplayer.ui.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.displayName
import com.ruwen.audioplayer.data.entity.SubtitleCue
import com.ruwen.audioplayer.data.entity.SubtitleStatus
import com.ruwen.audioplayer.data.repository.PlaylistRepository
import com.ruwen.audioplayer.databinding.ActivityPlayerBinding
import com.ruwen.audioplayer.databinding.DialogSleepTimerBinding
import com.ruwen.audioplayer.service.PlaybackService
import com.ruwen.audioplayer.service.PlaybackBinder
import com.ruwen.audioplayer.util.SubtitleUtils
import com.ruwen.audioplayer.util.placeholderImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var playbackService: PlaybackService? = null
    private var isBound = false

    private var playlistId: Long = -1
    /** 目标曲目 id：定位队列位置时用它，比只用下标稳（导入新音频后下标会整体偏移） */
    private var startAudioId: Long = -1L
    private var startIndex: Int = 0
    /**
     * 装载播放列表后是否立即播放。默认 true —— 保持「从音频列表点进去就开始播」的原有行为。
     * 迷你播放栏在「记忆态」（服务里没有装载内容、只是记得上次那首）打开播放页时传 false，
     * 表示只装载不播放，保持与迷你栏一致的暂停态。
     */
    private var autoPlay: Boolean = true
    private var audioItems: List<AudioItem> = emptyList()

    /** 字幕（按 startTime 升序，loadSubtitle 时排好并缓存，供显示与滑动跳转共用） */
    private var subtitleCues: List<SubtitleCue> = emptyList()

    /** 主行显示的 cue（正常情况下是当前句；落在句间空隙时用下一句占位，避免字幕闪空） */
    private var currentCue: SubtitleCue? = null

    /** 下一行显示的 cue（做「预告」；仅当主行是真正的当前句时才有值） */
    private var nextCue: SubtitleCue? = null

    /** 上一行显示的 cue（做「回看」；位于当前句上方） */
    private var prevCue: SubtitleCue? = null

    /** 是否正在拖动进度条。拖动期间暂停 500ms 轮询对「进度条/字幕」的覆盖，避免和手指抢控制权 */
    private var isSeeking = false

    /**
     * 最近一次跳转（滑动 / 松手）后“逻辑上应处的位置”及其记录时刻。
     *
     * ExoPlayer 的 seek 是**异步**的：一次跳转 seek 之后，getCurrentPosition() 在若干帧内仍是旧位置。
     * 若此时立刻再划一次，[seekToAdjacentCue] 仍会按旧位置算“当前句”，导致连划多次只前进一句
     * （或左右连划越界、多退一句）—— 这正是“连续左右滑”会出问题的根因。
     * 故每次跳转后把目标 startTime 记在这里，后续跳转以它为基准；
     * 待 800ms 后（seek 必然已生效）再清掉，恢复正常“以真实位置为准”。
     */
    private var navigationPosition: Long? = null
    private var navigationTimeMs: Long = 0

    private val repository: PlaylistRepository by lazy {
        (application as RuWenApplication).playlistRepository
    }

    // 观察当前曲目在数据库中的字幕状态（生成完成后实时刷新，避免读到内存旧数据）
    private var observedAudioId: Long = -1L
    private var observedLiveData: LiveData<AudioItem?>? = null
    private val subtitleObserver = Observer<AudioItem?> { item ->
        if (item != null) loadSubtitle(item)
    }

    private var progressUpdateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    /** 上一次渲染的播放/暂停状态，避免每 500ms 重复 setImageResource */
    private var lastRenderedPlaying: Boolean? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackBinder
            playbackService = binder.getService()
            isBound = true
            attachOrStartPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            playbackService = null
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackService.ACTION_PLAYBACK_UPDATE -> updatePlaybackState()
                PlaybackService.ACTION_SLEEP_TIMER_UPDATE -> {
                    val remaining = intent.getLongExtra(
                        PlaybackService.EXTRA_SLEEP_TIMER_REMAINING, 0
                    )
                    val modeOrdinal = intent.getIntExtra(
                        PlaybackService.EXTRA_SLEEP_TIMER_MODE,
                        PlaybackService.SleepTimerMode.NONE.ordinal
                    )
                    updateSleepTimerDisplay(remaining, modeOrdinal)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1)
        startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        startAudioId = intent.getLongExtra(EXTRA_START_AUDIO_ID, -1L)
        autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)

        setupToolbar()
        setupPlayerControls()
        setupSleepTimer()

        scope.launch(Dispatchers.IO) {
            val app = application as RuWenApplication
            val items = app.playlistRepository.getAudioItemsByPlaylistSync(playlistId)
            withContext(Dispatchers.Main) {
                audioItems = items
                onAudioItemsLoaded()
            }
        }
    }

    /** 从通知栏再次打开时（FLAG_ACTIVITY_SINGLE_TOP）走这里，更新目标曲目 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newPlaylistId = intent?.getLongExtra(EXTRA_PLAYLIST_ID, playlistId) ?: playlistId
        val newStartIndex = intent?.getIntExtra(EXTRA_START_INDEX, startIndex) ?: startIndex
        val newStartAudioId = intent?.getLongExtra(EXTRA_START_AUDIO_ID, startAudioId) ?: startAudioId
        autoPlay = intent?.getBooleanExtra(EXTRA_AUTO_PLAY, autoPlay) ?: autoPlay
        if (newPlaylistId != playlistId || newStartIndex != startIndex || newStartAudioId != startAudioId) {
            playlistId = newPlaylistId
            startIndex = newStartIndex
            startAudioId = newStartAudioId
            playbackService?.let { attachOrStartPlayback() }
        }
    }

    private fun onAudioItemsLoaded() {
        if (audioItems.isEmpty()) {
            finish()
            return
        }
        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_sleep_timer) {
                showSleepTimerDialog()
                true
            } else {
                false
            }
        }
    }

    /**
     * 工具栏菜单只在这里注入一份。
     * 以前 activity_player.xml 的 MaterialToolbar 上还写了 app:menu="@menu/menu_player"，
     * 布局膨胀时会先注入一次、这里又注入一次，导致睡眠定时图标出现两个；XML 里那份已移除。
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_player, menu)
        return true
    }

    private fun setupPlayerControls() {
        binding.btnPlayPause.setOnClickListener {
            val service = playbackService ?: return@setOnClickListener
            // 乐观更新：点击立即切换图标并播放微动效，不必等待播放器回调
            val willPlay = !service.isPlaying()
            renderPlayPauseIcon(willPlay, animate = true)
            service.togglePlayPause()
        }

        binding.btnPrevious.setOnClickListener {
            playbackService?.previous()
            // 切歌（或本曲重头）后清掉字幕导航基准，避免紧接着的滑动还按旧基准定位
            navigationPosition = null
        }
        binding.btnNext.setOnClickListener {
            playbackService?.next()
            navigationPosition = null
        }

        // 循环：三态轮转（与随机彼此独立）。
        // 轮转顺序（主流播放器顺序，改这里即可调整）：
        //   不循环(NONE) → 列表循环(LIST_LOOP) → 单曲循环(SINGLE) → 不循环(NONE)
        binding.btnRepeat.setOnClickListener {
            val service = playbackService ?: return@setOnClickListener
            val next = when (service.getRepeatMode()) {
                PlaybackService.RepeatMode.NONE -> PlaybackService.RepeatMode.LIST_LOOP
                PlaybackService.RepeatMode.LIST_LOOP -> PlaybackService.RepeatMode.SINGLE
                PlaybackService.RepeatMode.SINGLE -> PlaybackService.RepeatMode.NONE
            }
            service.setRepeatMode(next)
            updateRepeatModeIcon()
        }

        // 随机：独立开关
        binding.btnShuffle.setOnClickListener {
            val service = playbackService ?: return@setOnClickListener
            service.setShuffleEnabled(!service.isShuffleEnabled())
            updateShuffleIcon()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 拖动时只做“字幕预览 + 时间文本”，真正的 seek 放到松手时（onStopTrackingTouch）。
                    // 否则每帧都发 seekTo 会反复打断 ExoPlayer，且 500ms 轮询还会把 thumb 抢回旧位置。
                    val pos = progress.toLong()
                    binding.tvCurrentTime.text = SubtitleUtils.formatDurationLong(pos)
                    updateSubtitle(pos)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 标记“正在拖动”，让 500ms 轮询暂停覆盖进度条与字幕，避免和手指抢控制权。
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val service = playbackService ?: return
                val progress = seekBar?.progress ?: return
                service.seekTo(progress.toLong())
                // 松手立即按最终位置刷新字幕；同时记录逻辑位置，保证紧接着的滑动跳转也连续。
                val pos = progress.toLong()
                updateSubtitle(pos)
                navigationPosition = pos
                navigationTimeMs = System.currentTimeMillis()
                isSeeking = false
            }
        })

        // 字幕区滑动手势：
        //  - 左滑 / 右滑：跳上一段 / 下一段字幕（相对当前播放位置计算）
        //  - 上滑：回到当前字幕起点（重听当前段）
        //  - 下滑：播放 / 暂停
        // 内层 LockableScrollView 仅保留「代码自动滚回顶部」能力，禁用其触摸滚动：
        // 否则它会把纵向手势当成滚动抢走，导致外层容器的上下滑几乎失效
        //（横向不受影响，因为 ScrollView 不横向滚动）。
        // 注：三行字幕改造后 tvSubtitle 的直接父级是 ConstraintLayout，
        // 故直接引用布局里带 id 的 ScrollView，不要再拿 tvSubtitle.parent 当 ScrollView。
        binding.subtitleScrollView.isScrollable = false
        binding.swipeSubtitleContainer.onSwipeListener = { direction ->
            seekToAdjacentCue(direction)
        }
        binding.swipeSubtitleContainer.onVerticalSwipeListener = { direction ->
            if (direction < 0) {
                // 上滑：回到当前字幕段起点，重听当前这一段
                replayCurrentCue()
            } else {
                // 下滑：播放 / 暂停（与播放按钮同逻辑，含乐观图标更新）
                // 注意：属性 setter 上的 lambda 没有以属性名命名的隐式标签，
                // 故不能用 return@onVerticalSwipeListener；改用 ?.let 处理空安全。
                playbackService?.let { service ->
                    val willPlay = !service.isPlaying()
                    renderPlayPauseIcon(willPlay, animate = true)
                    service.togglePlayPause()
                }
            }
        }
        // 横向拖动反馈：字幕容器跟手位移（带阻尼），松手/取消时回弹。
        // 缺少这段时手势没有任何视觉反馈，用户会以为「滑动根本没生效」。
        binding.swipeSubtitleContainer.onSwipeDrag = { dx ->
            val container = binding.swipeSubtitleContainer
            if (dx == 0f) {
                container.animate().translationX(0f).setDuration(160).start()
            } else {
                val maxPx = 64 * resources.displayMetrics.density
                container.animate().cancel()
                container.translationX = (dx * 0.35f).coerceIn(-maxPx, maxPx)
            }
        }
        // 纵向跟手：上滑 / 下滑时字幕容器随手指轻微上下位移（带阻尼），松手/取消时回弹。
        // 与横向对称，避免「上滑/下滑没有视觉反馈」让用户以为手势没生效。
        binding.swipeSubtitleContainer.onVerticalSwipeDrag = { dy ->
            val container = binding.swipeSubtitleContainer
            if (dy == 0f) {
                container.animate().translationY(0f).setDuration(160).start()
            } else {
                val maxPx = 64 * resources.displayMetrics.density
                container.animate().cancel()
                container.translationY = (dy * 0.35f).coerceIn(-maxPx, maxPx)
            }
        }
    }

    /**
     * 字幕区左/右滑时调用：整体前进/后退「一句」。
     *
     * @param direction -1 = 左滑 → 跳上一句；+1 = 右滑 → 跳下一句。到头时给提示而非静默不动。
     *
     * 关键：跳哪一句由「当前播放位置对应的句」决定，不依赖 [updateSubtitle] 在两句
     * **空隙**里对「下一句」的预览占位（main = playing ?: following）。
     * 若按「屏幕上正预览的那句」去算目标，右滑会越过它多跳一句（字幕随之跑在音频前面），
     * 这正是「划动后字幕和音频对不上」的根因。
     *
     *  - 句中：右滑→下一句、左滑→上一句；
     *  - 空隙（屏幕正预览下一句）：右滑→跳到「预览句」本身（不跳过）、左滑→回到「刚播完」的这句；
     *  - 首句之前：右滑→首句、左滑→已到顶。
     * [subtitleCues] 已在 loadSubtitle 中按 startTime 排好序。
     */
    private fun seekToAdjacentCue(direction: Int) {
        val service = playbackService ?: return
        if (subtitleCues.isEmpty()) return

        // 以“逻辑位置”为基准：若有尚未生效的跳转目标（navigationPosition），用它代替可能滞后的
        // getCurrentPosition()，否则连续快速滑动时每次都按旧位置算“当前句”，连划只前进一句。
        val basePosition = navigationPosition ?: service.getCurrentPosition()

        // 当前「正在播放 / 刚播完」的那一句下标（空隙里仍算上一句，不预判下一句）。
        val baseIndex = subtitleCues.indexOfLast { it.startTime <= basePosition }
        val targetIndex = if (baseIndex < 0) {
            // 在首句之前：右滑→跳到首句；左滑→已到顶，交给下方 null 分支提示。
            if (direction > 0) 0 else -1
        } else {
            val inGap = basePosition > subtitleCues[baseIndex].endTime
            if (inGap) {
                // 空隙里屏幕正「预览下一句」(baseIndex+1)：
                // 右滑→跳到这「预览句」本身（避免越过它多跳一句）；
                // 左滑→回到「刚播完」的这句本身（方便重听刚说完的那句）。
                if (direction > 0) baseIndex + 1 else baseIndex
            } else {
                baseIndex + direction
            }
        }

        val target = subtitleCues.getOrNull(targetIndex)
        if (target == null) {
            // 已在第一段/最后一段：明确提示，而不是静默不动
            Toast.makeText(
                this,
                if (direction < 0) R.string.subtitle_no_previous else R.string.subtitle_no_next,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        service.seekTo(target.startTime)
        // 立刻刷新字幕，否则要等下一个 500ms 轮询才更新，
        // 划动后字幕会短暂停在旧句，像「没跟上」。
        // 直接用刚 seek 到的 target.startTime（而不是 getCurrentPosition()），
        // 因为 ExoPlayer 的 seek 是异步的，此时 getCurrentPosition() 仍是旧位置，会渲染错句。
        updateSubtitle(target.startTime)
        // 记录逻辑位置：后续跳转以它为基准，直到 800ms 后 seek 必然生效再清掉（见 updateProgress）。
        navigationPosition = target.startTime
        navigationTimeMs = System.currentTimeMillis()
    }

    /**
     * 上滑手势：回到「当前字幕段」的起点重听。
     *
     * 「当前段」= 最后一个 startTime <= 当前播放位置的字幕（[updateSubtitle] 里就是这个值，
     * 落在句间空隙时也算上一句），所以空隙里上滑会回到「刚播完那句」的起点，
     * 与左滑「回到刚播完那句」的逻辑一致。
     */
    private fun replayCurrentCue() {
        val service = playbackService ?: return
        if (subtitleCues.isEmpty()) return

        // 以“逻辑位置”为基准：若有尚未生效的跳转目标（navigationPosition），用它代替可能滞后的
        // getCurrentPosition()，否则连续快速滑动时每次都按旧位置算“当前句”。
        val basePosition = navigationPosition ?: service.getCurrentPosition()
        // 当前「正在播放 / 刚播完」的那一句下标（空隙里仍算上一句，不预判下一句）。
        val baseIndex = subtitleCues.indexOfLast { it.startTime <= basePosition }
        if (baseIndex < 0) return

        val target = subtitleCues[baseIndex]
        service.seekTo(target.startTime)
        // 立刻刷新字幕，否则要等下一个 500ms 轮询才更新（划动后字幕会短暂停在旧句）。
        updateSubtitle(target.startTime)
        // 记录逻辑位置：后续跳转以它为基准，直到 800ms 后 seek 必然生效再清掉（见 updateProgress）。
        navigationPosition = target.startTime
        navigationTimeMs = System.currentTimeMillis()
    }

    private fun setupSleepTimer() {
        binding.chipSleepTimer.setOnClickListener { showSleepTimerDialog() }
    }

    /**
     * 服务绑定完成后的关键决策：
     *  - 服务里没有列表 / 是另一个播放列表 → 装载并播放；
     *  - 同一个播放列表但点了另一首 → 只切歌；
     *  - 同一个播放列表的同一首 → **什么都不做**，直接附着到正在播放的状态。
     *
     * 最后一条修复了「播放途中退出播放页，再点进同一首又会从头开始」的问题。
     */
    private fun attachOrStartPlayback() {
        val service = playbackService ?: return
        if (audioItems.isEmpty()) return

        // 目标位置**按 audioId 定位**：导入新音频后队列下标会整体偏移，
        // 只拿下标会指到别的曲目，甚至越界被 playAt 静默忽略（表现为「点了没反应」）。
        val targetIndex = audioItems.indexOfFirst { it.id == startAudioId }
            .takeIf { it >= 0 }
            ?: startIndex.coerceIn(0, audioItems.lastIndex)

        // 服务里的队列是一次装载的快照，导入新音频后不会自动变新。
        // 只要它和数据库这一版对不上，就重新装载——否则 playAt 会在旧队列上解释新下标。
        val queueStale = service.getPlaylistAudioIds() != audioItems.map { it.id }

        when {
            !service.hasPlaylistLoaded() || service.getCurrentPlaylistId() != playlistId || queueStale -> {
                // 重新装载时若目标仍是「当前正在播的那首」，把进度一起带过去，避免从头重放
                val sameTarget = service.getCurrentAudioItem()?.id == audioItems[targetIndex].id
                val startPosition = if (sameTarget) service.getCurrentPosition() else 0L
                service.setPlaylist(audioItems, playlistId, targetIndex, startPosition)
                // autoPlay=false（迷你播放栏「记忆态」进入）：只装载不播放，保持暂停态
                if (autoPlay) service.play()
            }
            service.getLastRequestedIndex() != targetIndex -> {
                service.playAt(targetIndex)
            }
            else -> {
                // 已经在播这一首：保持原进度，不做任何打断
            }
        }

        updatePlaybackState()
        updateSleepTimerDisplay(
            service.getSleepTimerRemaining(),
            service.getSleepTimerMode().ordinal
        )
        startProgressUpdates()
    }

    private fun startProgressUpdates() {
        if (progressUpdateJob?.isActive == true) return
        progressUpdateJob = scope.launch {
            while (true) {
                updateProgress()
                delay(500)
            }
        }
    }

    private fun updateProgress() {
        val service = playbackService ?: return
        val position = service.getCurrentPosition()
        val duration = service.getDuration()

        if (!isSeeking) {
            // 当前位置随时可显示，不依赖时长是否已就绪（切歌瞬间时长可能暂为 0/未知，
            // 若一并塞进 duration>0 的判断里，会导致“当前位置”也跟着冻结）。
            binding.seekBar.progress = position.toInt()
            binding.tvCurrentTime.text = SubtitleUtils.formatDurationLong(position)
        }
        if (duration > 0) {
            // 拖动进度条期间不更新 max：thumb 由用户手指控制，否则 500ms 轮询会把 thumb 抢回旧位置。
            if (!isSeeking) {
                binding.seekBar.max = duration.toInt()
            }
            binding.tvTotalTime.text = SubtitleUtils.formatDurationLong(duration)
        }

        // 播放/暂停图标以 500ms 轮询兜底同步。
        // 首次进入时 ExoPlayer 可能仍处于缓冲中，仅靠绑定瞬间的一次读取会显示成“未播放”。
        renderPlayPauseIcon(service.isPlaying(), animate = false)

        // 睡眠定时剩余时间跟随进度轮询持续刷新（不再只依赖跨进程广播）。
        // 设置/取消后会立即刷新一次，但倒计时过程中靠这里的每 500ms 兜底更新剩余秒数。
        updateSleepTimerDisplay(service.getSleepTimerRemaining(), service.getSleepTimerMode().ordinal)

        // 导航跳转目标已记录超过 800ms：此时 seek 必然已生效，清掉逻辑位置，
        // 恢复正常“以真实位置为准”（否则切歌/长时间播放后再划会用到过期基准）。
        if (navigationPosition != null && System.currentTimeMillis() - navigationTimeMs > 800) {
            navigationPosition = null
        }

        // 拖动期间字幕由 onProgressChanged 实时预览，这里不再覆盖，避免和手指抢。
        if (!isSeeking) {
            // 关键修复（左滑不同步）：划动后 ExoPlayer 的 seek 是异步的，800ms 窗口内
            // getCurrentPosition() 仍是跳转前的旧位置。若直接用真实位置渲染字幕，会在
            // 「刚跳到的那句」和「旧位置那句」之间来回闪，看起来就像字幕跟不上音频。
            // navigationPosition 记录的是“本应跳到的位置”，窗口期内以它为准锁住字幕，
            // 等 seek 真正生效（窗口结束）后再交回真实位置。这样左滑/右滑行为一致，
            // 不再因向后 seek 延迟更明显而只在左滑时暴露不同步。
            updateSubtitle(navigationPosition ?: position)
        }
    }

    private fun updatePlaybackState() {
        val service = playbackService ?: return

        renderPlayPauseIcon(service.isPlaying(), animate = false)

        val currentItem = service.getCurrentAudioItem()
        if (currentItem != null) {
            binding.tvTitle.text = currentItem.displayName
            supportActionBar?.title = currentItem.displayName
            bindCover(currentItem.coverPath)
            updateTrackInfo()
            observeSubtitlesFor(currentItem.id)
        }

        updateRepeatModeIcon()
        updateShuffleIcon()
        // 切歌/播放状态变化后立刻同步一次进度条与时间（不再等下一个 500ms 轮询），
        // 否则“当前位置/总长度”会在切歌后短暂停留在上一首的数值。
        updateProgress()
    }

    /**
     * 播放页封面：优先用音频自带的封面（内嵌图片或播客封面），没有则回退默认音符图标。
     *
     * 与迷你栏 [com.ruwen.audioplayer.ui.MiniPlayerController] 保持一致的行为差异：
     *  - 真实封面：清 tint、去内边距、centerCrop 铺满卡片，避免图标那圈留白；
     *  - 回退图标：恢复 tint 与内边距、fitCenter，保持原本的居中音符观感。
     */
    private fun bindCover(coverPath: String?) {
        val view = binding.ivCover
        val file = coverPath?.takeIf { it.isNotEmpty() }?.let(::File)
        if (file != null && file.exists()) {
            view.imageTintList = null
            view.setPadding(0, 0, 0, 0)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            val placeholder = view.context.placeholderImage(R.drawable.ic_music_note)
            view.load(Uri.fromFile(file)) {
                crossfade(true)
                placeholder(placeholder)
                error(placeholder)
            }
        } else {
            val padding = (34 * resources.displayMetrics.density).toInt()
            view.setImageResource(R.drawable.ic_music_note)
            view.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.purple_primary)
            )
            view.setPadding(padding, padding, padding, padding)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    /** 同步字幕区上方的曲目计数（如 “3 / 12”）。广播与 500ms 轮询都会调用，确保切歌后及时刷新。 */
    private fun updateTrackInfo() {
        val service = playbackService ?: return
        val total = service.getPlaylistSize()
        val pos = service.getCurrentPlaylistPosition() + 1
        binding.tvTrackInfo.text = if (total > 0) {
            getString(R.string.track_position, pos, total)
        } else {
            ""
        }
    }

    /**
     * 观察指定音频在数据库中的字幕状态变化。
     * 字幕生成在后台 WorkManager 中完成，只有观察 DB 才能在生成完成后实时刷新。
     */
    private fun observeSubtitlesFor(audioId: Long) {
        if (audioId == observedAudioId) return
        observedLiveData?.removeObserver(subtitleObserver)
        observedAudioId = audioId
        // 切歌瞬间先清空旧字幕缓存与导航基准：否则在 loadSubtitle 异步重新装载前，
        // 500ms 轮询的 updateSubtitle 会用「上一首的 cues + 这一首的位置」渲染出旧字幕一闪。
        subtitleCues = emptyList()
        currentCue = null
        nextCue = null
        prevCue = null
        navigationPosition = null
        // 立即清掉上一首的字幕文本，避免「有字幕 → 无字幕」切换时旧歌词在 loadSubtitle
        // 异步回调前滞留显示。loadSubtitle 随后会写入“未生成”或新歌词。
        renderSubtitleMessage("")
        observedLiveData = repository.observeAudioItem(audioId)
        observedLiveData?.observe(this, subtitleObserver)
    }

    private fun loadSubtitle(audioItem: AudioItem) {
        // 先清空三个缓存句：失败/未生成/文件缺失分支也会走到这里，
        // 不清空会让上一首残留的 cue 一直挂在内存里（虽不会误渲染，但不干净）。
        currentCue = null
        nextCue = null
        prevCue = null
        // 切歌装载新字幕时清掉上一首残留的“逻辑跳转位置”，否则新歌的滑动会用过期基准算“当前句”。
        navigationPosition = null

        if (audioItem.subtitleStatus == SubtitleStatus.FAILED) {
            subtitleCues = emptyList()
            val error = audioItem.subtitleError
            renderSubtitleMessage(
                if (!error.isNullOrEmpty()) {
                    getString(R.string.subtitle_failed_with_reason, error)
                } else {
                    getString(R.string.subtitle_failed)
                }
            )
            return
        }

        val subtitlePath = audioItem.subtitlePath
        if (subtitlePath.isNullOrEmpty()) {
            subtitleCues = emptyList()
            renderSubtitleMessage(getString(R.string.subtitle_not_generated))
            return
        }

        val subtitleFile = File(subtitlePath)
        if (!subtitleFile.exists()) {
            subtitleCues = emptyList()
            renderSubtitleMessage(getString(R.string.subtitle_not_generated))
            return
        }

        // 一次性按 startTime 排好序再缓存：显示（定位当前句/下一句）与滑动跳转都依赖它
        subtitleCues = SubtitleUtils.parseSrt(subtitleFile).sortedBy { it.startTime }
        // 立即按当前播放位置渲染一句，不必等下一个 500ms 轮询
        // （否则刚生成完 / 刚切歌装载好字幕时会先闪一下「无字幕」）。
        updateSubtitle(playbackService?.getCurrentPosition() ?: 0L)
    }

    /**
     * 刷新字幕：**三行显示「上一句 + 当前句 + 下一句」**，当前句恒定在卡片正中。
     *
     * 为什么给相邻句：whisper 的时间戳是从文本反推的估计值，句子边界常略微提前，
     * 只显示当前句时会出现「人声还没说完、字幕已换掉」的观感；上面一行用于回看、
     * 下面一行用于预读跟读。
     *
     * 落在两句之间的空隙时，用「下一句」占位作当前句，避免字幕闪空。
     */
    private fun updateSubtitle(positionMs: Long) {
        if (subtitleCues.isEmpty()) return

        // 当前句 = 最后一个 startTime <= positionMs 的句子（position 在首句之前时为 -1）
        val index = subtitleCues.indexOfLast { it.startTime <= positionMs }
        val playing = subtitleCues.getOrNull(index)?.takeIf { positionMs <= it.endTime }

        // 空隙时当前句用「下一句」占位（index + 1），此时上一句正好是刚播完的那句
        val currentIndex = if (playing != null) index else index + 1
        val current = subtitleCues.getOrNull(currentIndex)
        val prev = subtitleCues.getOrNull(currentIndex - 1)
        val next = subtitleCues.getOrNull(currentIndex + 1)

        if (current == currentCue && next == nextCue && prev == prevCue) return
        currentCue = current
        nextCue = next
        prevCue = prev
        renderSubtitle(prev, current, next)
    }

    /** 渲染三行字幕：当前句高亮（主色 + 加粗），上/下句用次要色小一号 */
    private fun renderSubtitle(prev: SubtitleCue?, current: SubtitleCue?, next: SubtitleCue?) {
        binding.tvSubtitlePrev.text = prev?.text.orEmpty()
        binding.tvSubtitleNext.text = next?.text.orEmpty()
        binding.tvSubtitle.text = current?.text.orEmpty()
    }

    /** 无字幕 / 生成中 / 失败等提示文案：写在中间那行，上下两行清空 */
    private fun renderSubtitleMessage(message: String) {
        binding.tvSubtitlePrev.text = ""
        binding.tvSubtitleNext.text = ""
        binding.tvSubtitle.text = message
    }

    // ----------------------------------------------------------------------
    //  图标状态
    // ----------------------------------------------------------------------

    private fun updateRepeatModeIcon() {
        // 三态显示规则：
        //   NONE（不循环）：ic_repeat，不高亮（alpha 0.6、次要色 tint）
        //   SINGLE（单曲循环）：ic_repeat_one，高亮（alpha 1.0、高亮色 tint）
        //   LIST_LOOP（列表循环）：ic_repeat（标准循环图标，中心不带 1），高亮
        val mode = playbackService?.getRepeatMode() ?: PlaybackService.RepeatMode.NONE
        val (iconRes, active, descRes) = when (mode) {
            PlaybackService.RepeatMode.NONE ->
                Triple(R.drawable.ic_repeat, false, R.string.repeat_off)
            PlaybackService.RepeatMode.SINGLE ->
                Triple(R.drawable.ic_repeat_one, true, R.string.single_loop)
            PlaybackService.RepeatMode.LIST_LOOP ->
                Triple(R.drawable.ic_repeat, true, R.string.list_loop)
        }
        binding.btnRepeat.setImageResource(iconRes)
        binding.btnRepeat.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (active) R.color.purple_primary else R.color.text_secondary
            )
        )
        binding.btnRepeat.alpha = if (active) 1.0f else 0.6f
        binding.btnRepeat.contentDescription = getString(descRes)
    }

    private fun updateShuffleIcon() {
        val active = playbackService?.isShuffleEnabled() == true
        binding.btnShuffle.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (active) R.color.purple_primary else R.color.text_secondary
            )
        )
        binding.btnShuffle.alpha = if (active) 1.0f else 0.6f
        binding.btnShuffle.contentDescription = getString(R.string.shuffle)
    }

    /**
     * 渲染播放/暂停按钮。animate=true 时做一个 100ms 缩放到 0.7 再回弹的微动效。
     */
    private fun renderPlayPauseIcon(isPlaying: Boolean, animate: Boolean) {
        if (lastRenderedPlaying == isPlaying) return
        lastRenderedPlaying = isPlaying
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        if (!animate) return
        binding.btnPlayPause.animate()
            .scaleX(0.7f).scaleY(0.7f)
            .setDuration(100)
            .withEndAction {
                binding.btnPlayPause.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(160)
                    .start()
            }
            .start()
    }

    // ----------------------------------------------------------------------
    //  睡眠定时
    // ----------------------------------------------------------------------

    private fun showSleepTimerDialog() {
        val dialogBinding = DialogSleepTimerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)

        val service = playbackService

        // 已生效的定时：显示剩余时间 / “本集结束后停止”
        refreshTimerActiveText(dialogBinding, service)

        // 设置/关闭后立即刷新播放页上的剩余时间胶囊（不等待下一次广播或 onStart）。
        // 用局部变量 svc 持有 service，避免 lambda 捕获时 service 为 null 导致不刷新。
        val svc = service
        val refreshAndDismiss: () -> Unit = {
            refreshSleepTimerFromService()
            dialog.dismiss()
        }

        dialogBinding.chip15.setOnClickListener { svc?.startSleepTimer(15); refreshAndDismiss() }
        dialogBinding.chip30.setOnClickListener { svc?.startSleepTimer(30); refreshAndDismiss() }
        dialogBinding.chip45.setOnClickListener { svc?.startSleepTimer(45); refreshAndDismiss() }
        dialogBinding.chip60.setOnClickListener { svc?.startSleepTimer(60); refreshAndDismiss() }
        dialogBinding.chip90.setOnClickListener { svc?.startSleepTimer(90); refreshAndDismiss() }
        dialogBinding.chip120.setOnClickListener { svc?.startSleepTimer(120); refreshAndDismiss() }

        dialogBinding.btnCustom.setOnClickListener {
            val minutes = dialogBinding.etCustomMinutes.text.toString().toIntOrNull()
            if (minutes != null && minutes > 0) {
                svc?.startSleepTimer(minutes)
                refreshAndDismiss()
            }
        }

        // 未设置定时时隐藏「关闭定时」
        dialogBinding.btnCancelTimer.visibility =
            if (service?.isSleepTimerActive() == true) View.VISIBLE else View.GONE
        dialogBinding.btnCancelTimer.setOnClickListener {
            svc?.cancelSleepTimer()
            refreshAndDismiss()
        }

        dialog.show()
    }

    /** 用 service 当前值立即刷新一次睡眠定时展示（设置/关闭后调用） */
    private fun refreshSleepTimerFromService() {
        val service = playbackService ?: return
        updateSleepTimerDisplay(service.getSleepTimerRemaining(), service.getSleepTimerMode().ordinal)
    }

    private fun refreshTimerActiveText(
        dialogBinding: DialogSleepTimerBinding,
        service: PlaybackService?
    ) {
        if (service?.isSleepTimerActive() != true) {
            dialogBinding.tvTimerActive.visibility = View.GONE
            return
        }
        dialogBinding.tvTimerActive.visibility = View.VISIBLE
        dialogBinding.tvTimerActive.text = getString(
            R.string.sleep_timer_remaining,
            SubtitleUtils.formatDurationLong(service.getSleepTimerRemaining())
        )
    }

    private fun updateSleepTimerDisplay(remainingMs: Long, modeOrdinal: Int) {
        val mode = PlaybackService.SleepTimerMode.values().getOrNull(modeOrdinal)
            ?: PlaybackService.SleepTimerMode.NONE

        // 未设置定时（NONE），或倒计时剩余为 0（已结束尚未被置为 NONE 的瞬间）→ 隐藏胶囊
        if (mode == PlaybackService.SleepTimerMode.NONE ||
            (mode == PlaybackService.SleepTimerMode.COUNTDOWN && remainingMs <= 0)
        ) {
            binding.chipSleepTimer.visibility = View.GONE
            return
        }
        binding.chipSleepTimer.visibility = View.VISIBLE
        binding.chipSleepTimer.text = getString(
            R.string.sleep_timer_remaining,
            SubtitleUtils.formatDurationLong(remainingMs)
        )
    }

    /** 重新进入页面时补齐一次定时状态（此时还没有新的定时广播） */
    private fun updateSleepTimerDisplay() {
        val service = playbackService ?: return
        updateSleepTimerDisplay(service.getSleepTimerRemaining(), service.getSleepTimerMode().ordinal)
    }

    // ----------------------------------------------------------------------
    //  生命周期
    // ----------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PLAYBACK_UPDATE)
            addAction(PlaybackService.ACTION_SLEEP_TIMER_UPDATE)
        }
        // Android 13+ 要求显式指定 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playbackReceiver, filter)
        }
        // 从后台回到前台：立即以服务真实状态刷新一次 UI，并重启进度轮询
        // （onStop 里停掉了轮询，回到前台必须重新拉起，否则进度条与睡眠定时不再走动）
        if (isBound) {
            updatePlaybackState()
            updateSleepTimerDisplay()
            startProgressUpdates()
        }
    }

    override fun onStop() {
        super.onStop()
        // 包一层 runCatching：onStart 未注册成功就走到 onStop 时不抛 IllegalArgumentException
        runCatching { unregisterReceiver(playbackReceiver) }
        // 退到后台立即停掉 500ms 轮询：避免后台无谓刷新 UI 与耗电
        progressUpdateJob?.cancel()
        progressUpdateJob = null
        // 兜底：若退后台时正好卡在拖动/跳转中间（如手势被打断），复位这两个标志，
        // 否则回来后 isSeeking 一直为 true 会导致进度条不再随播放前进、navigationPosition 过期。
        isSeeking = false
        navigationPosition = null
    }

    override fun onDestroy() {
        super.onDestroy()
        progressUpdateJob?.cancel()
        observedLiveData?.removeObserver(subtitleObserver)
        observedLiveData = null
        observedAudioId = -1L
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_START_INDEX = "start_index"
        /**
         * 目标音频 id。点击列表进入时一并提供：队列可能因导入新音频而与数据库不一致，
         * 只用下标定位会指错曲目（甚至越界被忽略），用 id 定位才稳。
         */
        const val EXTRA_START_AUDIO_ID = "start_audio_id"
        /** 装载播放列表后是否立即播放，默认 true。迷你播放栏以「记忆态」进入时传 false。 */
        const val EXTRA_AUTO_PLAY = "auto_play"
    }
}
