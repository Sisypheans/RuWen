package com.ruwen.audioplayer.ui.playlist

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.SubtitleProgressStore
import com.ruwen.audioplayer.service.PlaybackService
import com.ruwen.audioplayer.data.entity.AudioImport
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.Episode
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.data.entity.SubtitleStatus
import com.ruwen.audioplayer.data.entity.displayName
import com.ruwen.audioplayer.data.repository.PodcastRepository
import com.ruwen.audioplayer.databinding.ActivityPlaylistDetailBinding
import com.ruwen.audioplayer.ui.adapter.AudioItemAdapter
import com.ruwen.audioplayer.ui.MiniPlayerController
import com.ruwen.audioplayer.ui.player.PlayerActivity
import com.ruwen.audioplayer.ui.podcast.formatBytes
import com.ruwen.audioplayer.ui.podcast.formatDuration
import com.ruwen.audioplayer.ui.podcast.formatEpisodeDate
import com.ruwen.audioplayer.ui.viewmodel.PlaylistDetailViewModel
import com.ruwen.audioplayer.ui.util.LoadingOverlay
import com.ruwen.audioplayer.util.AudioUtils
import com.ruwen.audioplayer.util.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var viewModel: PlaylistDetailViewModel
    private lateinit var adapter: AudioItemAdapter

    private lateinit var miniPlayerController: MiniPlayerController

    private val podcastRepository: PodcastRepository by lazy {
        RuWenApplication.getInstance().podcastRepository
    }

    /** 播客封面兜底下载用（音频里没有内嵌封面时才用得上） */
    private val httpClient = OkHttpClient()

    private var playlistId: Long = -1

    /** 是否处于多选（「选择」）模式。进入即全选，底部功能栏随之出现。 */
    private var selectionMode: Boolean = false

    /** 加载遮罩：导入 / 从播客添加等耗时操作时挡住整页（点屏幕与返回键都不响应） */
    private val loadingOverlay by lazy { LoadingOverlay.attach(this) }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            importAudioFiles(uris)
        }
    }

    /**
     * 「导入整个文件夹」。
     *
     * 系统文件选择器（SAF）的界面由系统绘制，我们无法在里面加「全选」按钮；
     * 用「选一个文件夹、把它里面的音频一次性全部导入」来达到同样效果。
     */
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) importAudioFolder(treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1)
        if (playlistId == -1L) {
            finish()
            return
        }

        viewModel = ViewModelProvider(this)[PlaylistDetailViewModel::class.java]

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupDragSort()
        setupSelectionBar()
        observeData()

        viewModel.loadPlaylist(playlistId)

        // 字幕生成进度是内存态（SubtitleProgressStore），不走数据库：
        // 这里订阅它并在 STARTED 期间持续收集，页面不可见时自动停止。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleProgressStore.progress.collect { adapter.subtitleProgress = it }
            }
        }

        // 迷你栏参与布局，列表会自动避让，无需再传 RecyclerView / FAB 去做补偿
        miniPlayerController = MiniPlayerController(this)
        // 服务绑定是异步的，绑定成功后必须再补一次高亮刷新：
        // 否则 onStart 里查到的服务还是 null，而暂停状态下后续没有广播来补刷，高亮会消失。
        miniPlayerController.onServiceConnected = { refreshCurrentPlayingHighlight() }
    }

    override fun onStart() {
        super.onStart()
        miniPlayerController.onStart()
        // 监听播放状态变化，用于刷新列表里「正在播放」的高亮
        ContextCompat.registerReceiver(
            this,
            playbackUpdateReceiver,
            IntentFilter(PlaybackService.ACTION_PLAYBACK_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 服务绑定是异步的，这里先尝试刷一次；连上后由广播再次刷新
        refreshCurrentPlayingHighlight()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(playbackUpdateReceiver) }
        miniPlayerController.onStop()
        super.onStop()
    }

    /**
     * 播放状态变化时刷新列表高亮。
     * 必须监听真实播放状态：自动切下一首、通知栏 / 耳机切歌、从小播放器播放时，
     * 列表都要能跟着更新——只记录点击位置是做不到的。
     */
    private val playbackUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCurrentPlayingHighlight()
        }
    }

    /**
     * 用「真实播放状态」驱动高亮（而非点击位置），见 [AudioItemAdapter.setCurrentAudioId]。
     *
     * 取值交给 [MiniPlayerController.getCurrentHighlightAudioId]，与迷你栏保持同一套判断：
     * 服务有内容就用当前那首；服务为空（冷启动）则用「上次播放」快照，避免迷你栏显示着、
     * 列表却没高亮的不一致。
     */
    private fun refreshCurrentPlayingHighlight() {
        if (::adapter.isInitialized) {
            adapter.setCurrentAudioId(miniPlayerController.getCurrentHighlightAudioId())
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        viewModel.playlist.observe(this) { playlist ->
            supportActionBar?.title = playlist?.name ?: ""
        }
    }

    private fun setupRecyclerView() {
        adapter = AudioItemAdapter(
            onItemClick = { audioItem, position ->
                openPlayer(audioItem, position)
            },
            onMenuClick = { audioItem, view ->
                showAudioItemMenu(audioItem, view)
            },
            // 生成字幕的入口只有「更多」菜单一处；列表里的按钮只在生成中/排队时出现，专用于取消
            onCancelSubtitleClick = { audioItem ->
                showCancelSubtitleDialog(audioItem)
            },
            onSelectionChanged = { updateSelectionBar() }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /**
     * 长按拖动排序（ItemTouchHelper，RecyclerView 官方推荐做法）。
     *
     * 拖动过程中只做同步的 notifyItemMoved（见 [AudioItemAdapter.moveItem]），
     * 抬手时（clearView）才把最终顺序写回数据库；写库触发的 LiveData 重新下发
     * 与当前顺序一致，DiffUtil 算不出差异，因此列表不会闪动。
     */
    private fun setupDragSort() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0 // 不启用左右滑动删除，避免误触
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            /** 多选模式下禁止拖动，避免与「点整行 = 勾选」冲突 */
            override fun isLongPressDragEnabled(): Boolean = !selectionMode

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.updateAudioOrder(adapter.currentItems().map { it.id })
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    private fun setupFab() {
        // 点加号先让用户选导入方式：单个/多个音频文件，或整个文件夹
        binding.fabAddAudio.setOnClickListener {
            showImportOptions()
        }
    }

    // ------------------------------------------------------------------
    //  多选：工具栏图标三态循环 + 底部操作栏
    // ------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_playlist_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select -> {
                enterSelectionMode()
                true
            }
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 进入多选模式：直接全选，并让底部功能栏出现。
     *
     * 这里**不再**是早先的「OFF → MULTI → ALL → OFF」三态循环：
     * 功能栏里已经有独立的「全选 / 取消」按钮，再让工具栏图标承担状态推进只会互相打架。
     * 工具栏「选择」的语义因此固定为「进入多选并全选」。
     */
    private fun enterSelectionMode() {
        selectionMode = true
        adapter.setSelectionMode(true)
        adapter.selectAll()
        updateSelectionBar()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        adapter.setSelectionMode(false)
        updateSelectionBar()
    }

    /**
     * 刷新底部操作栏。
     *
     * 显示条件 = **处于多选模式**（不再要求至少勾选一条）：
     * 功能栏里放了「全选」按钮，若按旧条件（有选中才显示），用户把勾选全部取消后
     * 功能栏就会消失，「全选」按钮自然也点不到，等于把自己锁死。
     */
    private fun updateSelectionBar() {
        binding.selectionActionBar.visibility =
            if (selectionMode) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text =
            getString(R.string.selected_count, adapter.selectedCount())
        // 按钮文案跟着「是否已全选」走：这里统一刷新，手动取消勾选导致不再是全选时也能回退成「全选」
        binding.btnSelectAll.setText(
            if (adapter.isAllSelected()) R.string.deselect_all else R.string.select_all
        )
        // 出现功能栏的同时抑制迷你播放栏，保证底部只有一条栏
        miniPlayerController.suppressed = selectionMode
    }

    private fun setupSelectionBar() {
        // 全选 / 取消全选是一个开关：已全选时点它清空，未全选时点它全选
        binding.btnSelectAll.setOnClickListener { adapter.toggleSelectAll() }
        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
        binding.btnBatchGenerate.setOnClickListener { batchGenerateSubtitles() }
        binding.btnBatchDelete.setOnClickListener { batchDeleteSelected() }
    }

    // ------------------------------------------------------------------
    //  排序：按音频标题正序 / 倒序
    // ------------------------------------------------------------------

    /**
     * 排序弹框：只提供方向（正序 / 倒序）——排序字段按需求固定为音频标题。
     * 标题写明「按标题排序」，让用户清楚排序基准是什么；只写「排序」则语义不明。
     * 列表为空时不弹（没有可排的内容，弹了也只会得到一个空结果）。
     */
    private fun showSortDialog() {
        if (adapter.currentItems().isEmpty()) return
        var ascending = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_playlist_by_title)
            .setSingleChoiceItems(
                arrayOf(getString(R.string.sort_asc), getString(R.string.sort_desc)),
                0
            ) { _, which -> ascending = which == 0 }
            .setPositiveButton(R.string.confirm) { _, _ -> sortByTitle(ascending) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 按标题排序，并**把结果写回数据库**——等价于「用户手动把所有条目拖了一遍」。
     *
     * 写库走的是与长按拖动抬手时完全相同的 [PlaylistDetailViewModel.updateAudioOrder]，
     * 因此：
     *  - 播放顺序（audio_items.order）随之改变，与拖动排序的结果不可区分；
     *  - 排序后仍可继续长按拖动，两者不会互相覆盖（都是同一条写库路径）；
     *  - 不需要给 playlists 表加 sortByTitle / sortAscending 字段（不落「排序偏好」，只落结果）。
     *
     * 写库触发的 LiveData 重新下发与 [adapter] 当前顺序一致，DiffUtil 算不出差异，列表不会闪动。
     */
    private fun sortByTitle(ascending: Boolean) {
        val sorted = adapter.currentItems()
            .sortedBy { it.displayName.lowercase() }
            .let { if (ascending) it else it.asReversed() }
        adapter.submitList(sorted)
        viewModel.updateAudioOrder(sorted.map { it.id })
    }

    /**
     * 批量生成字幕：跳过「已生成」与「正在生成」的条目
     * （前者已有字幕、后者已在队列里，重复发起只会白白占用内存）。
     * 逐个提交即可，真正的排队由 SubtitleGenerationWorker 的 FIFO 队列保证。
     */
    private fun batchGenerateSubtitles() {
        val selectedIds = adapter.selectedAudioIds().toSet()
        val targets = adapter.currentItems().filter {
            it.id in selectedIds &&
                it.subtitleStatus != SubtitleStatus.GENERATING &&
                it.subtitleStatus != SubtitleStatus.GENERATED &&
                it.subtitleStatus != SubtitleStatus.QUEUED
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.batch_nothing_to_generate, Toast.LENGTH_SHORT).show()
            return
        }
        targets.forEach { viewModel.generateSubtitle(it.id) }
        exitSelectionMode()
    }

    private fun batchDeleteSelected() {
        val ids = adapter.selectedAudioIds()
        if (ids.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_delete)
            .setMessage(getString(R.string.batch_delete_confirm, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteAudioItems(playlistId, ids)
                exitSelectionMode()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeData() {
        viewModel.getAudioItems(playlistId).observe(this) { audioItems ->
            adapter.submitList(audioItems)
            if (audioItems.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
            }
        }
    }

    /** 点加号后的导入方式：选文件（可多选）/ 导入整个文件夹 / 从播客添加 */
    private fun showImportOptions() {
        val options = arrayOf(
            getString(R.string.import_audio_file),
            getString(R.string.import_audio_folder),
            getString(R.string.add_from_podcast)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_audio)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFilePicker()
                    1 -> openDocumentTreeLauncher.launch(null)
                    2 -> showAddFromPodcast()
                }
            }
            .show()
    }

    private fun openFilePicker() {
        // 放开到 audio/*：m4a / flac / ogg / wav 等都由应用层按 MIME + 扩展名自行判断
        openDocumentLauncher.launch(arrayOf("audio/*"))
    }

    // ------------------------------------------------------------------
    //  从播客添加：选播客 → 勾选其已下载单集 → 加入当前播放列表
    // ------------------------------------------------------------------

    /** 第一步：列出已订阅的播客（弹框高度自适应，不强制最小高度） */
    private fun showAddFromPodcast() {
        lifecycleScope.launch {
            val podcasts = podcastRepository.getPodcasts()
            if (podcasts.isEmpty()) {
                Toast.makeText(
                    this@PlaylistDetailActivity, R.string.no_podcast, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
                .setTitle(R.string.add_from_podcast)
                .setItems(podcasts.map { it.title }.toTypedArray()) { _, which ->
                    showDownloadedEpisodes(podcasts[which])
                }
                .show()
        }
    }

    /**
     * 第二步：勾选该播客已下载的单集（未下载的不列出——播客单集不接入播放）。
     *
     * 用 `setAdapter` + 自定义行（[R.layout.item_select_episode]）而不是 `setMultiChoiceItems`：
     * 系统默认行会被长标题撑成多行，两条就能把弹框占满、还要在小框里滚。
     * 自定义行把标题限 2 行、元信息 1 行，弹框高度就跟着内容走（超屏才滚）。
     * 勾选状态由我们自己的数组维护，点击整行切换（`setAdapter` 的列表不会自动关闭弹框）。
     */
    private fun showDownloadedEpisodes(podcast: Podcast) {
        lifecycleScope.launch {
            val episodes = podcastRepository.getDownloadedEpisodes(podcast.id)
            if (episodes.isEmpty()) {
                Toast.makeText(
                    this@PlaylistDetailActivity, R.string.no_downloaded_episodes, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val checked = BooleanArray(episodes.size)
            // 先声明后赋值：toggle() 里要引用它，而 adapter 内部又要回调 toggle()（互相引用）
            lateinit var rowAdapter: BaseAdapter

            fun toggle(position: Int) {
                checked[position] = !checked[position]
                rowAdapter.notifyDataSetChanged()
            }

            rowAdapter = object : BaseAdapter() {
                override fun getCount(): Int = episodes.size
                override fun getItem(position: Int): Episode = episodes[position]
                override fun getItemId(position: Int): Long = episodes[position].id

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_select_episode, parent, false)
                    val episode = episodes[position]
                    view.findViewById<TextView>(R.id.tvTitle).text = episode.title
                    view.findViewById<TextView>(R.id.tvMeta).text = listOf(
                        formatEpisodeDate(episode.pubDate),
                        formatBytes(episode.sizeBytes),
                        formatDuration(episode.durationSec)
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    view.findViewById<MaterialCheckBox>(R.id.cbSelect).isChecked = checked[position]
                    // 让**行自己**消费点击：AlertDialog 的列表条目点击默认会关闭对话框
                    // （「点一下就关」的 bug 就来自这里——全选走的是按钮所以不受影响）。
                    // 行内有可点击的 View 时，ListView 的 onItemClick 不再触发。
                    view.setOnClickListener { toggle(position) }
                    return view
                }
            }

            val dialog = MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
                .setTitle(podcast.title)
                .setAdapter(rowAdapter) { _, which -> toggle(which) }
                .setPositiveButton(R.string.confirm) { _, _ ->
                    val chosen = episodes.filterIndexed { index, _ -> checked[index] }
                    if (chosen.isNotEmpty()) addDownloadedEpisodes(podcast, chosen)
                }
                .setNegativeButton(R.string.cancel, null)
                // 全选放进 neutral 按钮（官方 AlertDialog 的第三个动作位）：
                // 多选列表里"全选"属于批量动作，塞进列表第一行会污染真实数据。
                .setNeutralButton(R.string.select_all, null)
                .create()

            dialog.setOnShowListener {
                // 双保险：把 AlertDialog 内部 ListView 的条目点击也换成我们的切换逻辑，
                // 即使某次点击落到 ListView 层（而不是行 View），也不会关闭对话框。
                dialog.listView?.onItemClickListener =
                    android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                        toggle(position)
                    }

                val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                // 覆盖默认点击：neutral 默认点击即关闭对话框，这里要「全选后继续留在弹窗里」
                neutral.setOnClickListener {
                    val selectAll = checked.count { it } < checked.size
                    checked.indices.forEach { checked[it] = selectAll }
                    rowAdapter.notifyDataSetChanged()
                    neutral.text = getString(
                        if (selectAll) R.string.deselect_all else R.string.select_all
                    )
                }
            }
            dialog.show()
        }
    }

    /**
     * 第三步：把勾选的单集作为普通音频加入播放列表。
     *
     * 走的是与文件导入完全相同的 [AudioImport] 通道（带 duration / fileSize），
     * 这样字幕共享判定「同一个音频」在播客音频上同样生效。
     * 封面兜底用播客侧的图片（单集图 → 播客封面）：RSS 一定带封面，
     * 音频文件里没内嵌图时它是最可靠的来源。
     */
    private fun addDownloadedEpisodes(podcast: Podcast, episodes: List<Episode>) {
        lifecycleScope.launch {
            // 每条都要读元数据 + 可能下载封面，几十条要等好几秒，必须给遮罩
            loadingOverlay.show()
            val imports = try {
                withContext(Dispatchers.IO) {
                    episodes.mapNotNull { episode ->
                        val path = episode.localPath ?: return@mapNotNull null
                        val file = File(path)
                        // 文件被外部清掉时跳过，避免加入一条永远播不了的死条目
                        if (!file.exists()) return@mapNotNull null
                        buildImport(
                            uri = Uri.fromFile(file),
                            filePath = path,
                            fallbackTitle = episode.title,
                            fileSize = file.length(),
                            fallbackCoverUrl = episode.imageUrl ?: podcast.imageUrl,
                            // RSS 单集标题是权威来源：不能让文件内嵌标签（常是同一个节目名）盖掉它
                            preferProvidedTitle = true
                        )
                    }
                }
            } finally {
                loadingOverlay.hide()
            }
            if (imports.isEmpty()) {
                Toast.makeText(
                    this@PlaylistDetailActivity, R.string.podcast_file_missing, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            viewModel.addAudioItems(playlistId, imports)
        }
    }

    /**
     * 导入一个文件夹下的全部音频（含子目录）—— 等效于「全选」。
     *
     * 分两个阶段，因为**逐个读音频元数据很慢**（每个文件都要开一次 MediaMetadataRetriever，
     * 还要解码内嵌封面并落盘）：几百个文件串行执行要等几十秒，界面还毫无反馈，
     * 看起来就像卡死。所以：
     *  1. **枚举**：只遍历目录、收集文件名/URI/大小（快），拿到总数用来显示进度；
     *  2. **导入**：按固定并发度并行读元数据，边做边刷新「已处理 x / 共 y」。
     *
     * 进度走 [loadingOverlay] 遮罩，而**不再是** AlertDialog：
     * AlertDialog 默认 `cancelable=true`，点一下弹窗外它就 dismiss、顺带把任务 cancel 掉，
     * 表现为「加载特效点一下就没了」（这正是要修的现象）。遮罩是页面内的一层 View，
     * 吃掉触摸 + 拦截返回键，点屏幕不会消失、返回键也不响应，任务不可中断。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun importAudioFolder(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            Log.w(TAG, "takePersistableUriPermission failed: ${it.message}")
        }

        // 枚举阶段还不知道总数 → 先只转圈，拿到总数后再把进度文案打开
        loadingOverlay.show()

        lifecycleScope.launch {
            try {
                // ---- 阶段 1：枚举候选文件 ----
                val candidates = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(this@PlaylistDetailActivity, treeUri)
                        ?: return@withContext emptyList()
                    val out = mutableListOf<ImportCandidate>()
                    collectAudioCandidates(root, out)
                    out
                }

                if (candidates.isEmpty()) {
                    toast(R.string.import_folder_empty)
                    return@launch
                }

                updateImportProgress(0, candidates.size)

                // ---- 阶段 2：并行读元数据（保持原顺序，进度按完成数刷新）----
                val finished = AtomicInteger(0)
                val items = candidates.map { candidate ->
                    async(Dispatchers.IO.limitedParallelism(IMPORT_PARALLELISM)) {
                        val item = runCatching {
                            buildImport(
                                uri = candidate.uri,
                                filePath = candidate.uri.toString(),
                                fallbackTitle = candidate.name,
                                fileSize = candidate.size
                            )
                        }.onFailure {
                            Log.w(TAG, "导入失败：${candidate.name}", it)
                        }.getOrNull()

                        val done = finished.incrementAndGet()
                        // 每 N 个刷一次进度即可：逐个都切主线程反而拖慢导入
                        if (done == candidates.size || done % 4 == 0) {
                            withContext(Dispatchers.Main) {
                                updateImportProgress(done, candidates.size)
                            }
                        }
                        item
                    }
                }.awaitAll().filterNotNull()

                if (items.isEmpty()) {
                    toast(R.string.import_folder_empty)
                } else {
                    viewModel.addAudioItems(playlistId, items)
                    Toast.makeText(
                        this@PlaylistDetailActivity,
                        getString(R.string.import_done_count, items.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                // 无论成功 / 失败 / 提前 return，遮罩都必须收掉，否则整页会被永久挡住
                loadingOverlay.hide()
            }
        }
    }

    /** 刷新导入进度文案（主线程调用） */
    private fun updateImportProgress(done: Int, total: Int) {
        loadingOverlay.updateMessage(getString(R.string.import_progress_count, done, total))
    }

    private fun toast(resId: Int) {
        Toast.makeText(this@PlaylistDetailActivity, resId, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    //  导入：读音频自带元数据 + 封面
    // ------------------------------------------------------------------

    /**
     * 构造一条导入记录：读音频自带元数据、落盘封面、按兜底链解析展示标题。
     * **必须在 IO 线程调用**（MediaMetadataRetriever 与网络下载都是重操作）。
     *
     * 兜底链：
     *  - 标题：内嵌标签 → [fallbackTitle]（播客 = RSS 单集标题，本地 = 文件名）
     *    → 从路径再剥一次文件名 → 兜底字面值「未命名音频」
     *  - 封面：内嵌封面 → [fallbackCoverUrl]（播客 = 单集图/播客封面）→ 无（界面显示占位图）
     *
     * @param filePath 入库的 uri 字段，同时是封面文件的命名依据，两者必须是同一个串
     */
    private suspend fun buildImport(
        uri: Uri,
        filePath: String,
        fallbackTitle: String,
        fileSize: Long,
        fallbackCoverUrl: String? = null,
        /**
         * [fallbackTitle] 是否来自**权威来源**（播客的 RSS 单集标题就是权威来源）。
         *
         * 为 true 时它优先于文件内嵌标签：播客音频常被统一打上同一个节目名的 tag，
         * 若让内嵌标签优先，一批单集导入后名字会全变成同一个（实测就是这个问题）。
         * 本地文件导入保持 false（用户自己的音乐库，内嵌标签通常更准确）。
         */
        preferProvidedTitle: Boolean = false
    ): AudioImport = withContext(Dispatchers.IO) {
        val meta = AudioUtils.readAudioMeta(this@PlaylistDetailActivity, uri)

        val coverPath = meta.embeddedPicture
            ?.let { CoverStore.save(this@PlaylistDetailActivity, filePath, it) }
            ?: fallbackCoverUrl?.let { downloadCover(filePath, it) }

        // 展示标题的兜底链：权威来源（或内嵌标签）→ 另一个 → 文件名 → 兜底字面值
        val candidates = if (preferProvidedTitle) {
            listOf(fallbackTitle, meta.title, AudioUtils.getAudioTitle(filePath))
        } else {
            listOf(meta.title, fallbackTitle, AudioUtils.getAudioTitle(filePath))
        }
        val displayTitle = candidates.firstOrNull { !it.isNullOrBlank() }
            ?: getString(R.string.unnamed_audio)

        AudioImport(
            title = fallbackTitle,
            uri = filePath,
            duration = meta.duration,
            fileSize = fileSize,
            displayTitle = displayTitle,
            coverPath = coverPath,
            // 不管这次有没有下载成功都记下来源：失败的那次恰恰是最需要记住的
            // （下次刷新播客时能按这个 URL 把封面补回来）
            coverSourceUrl = fallbackCoverUrl
        )
    }

    /** 下载封面兜底图并落盘；失败返回 null（调用方据此降级到占位图） */
    private suspend fun downloadCover(filePath: String, url: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                val bytes = httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
                bytes?.let { CoverStore.save(this@PlaylistDetailActivity, filePath, it) }
            }.getOrNull()
        }

    /**
     * 枚举阶段的候选文件：只带轻量信息，真正的元数据读取留到导入阶段（并配合进度）。
     */
    private data class ImportCandidate(val uri: Uri, val name: String, val size: Long)

    /** 递归枚举音频文件（只遍历目录 + 判断文件名/MIME，不做任何慢操作） */
    private fun collectAudioCandidates(dir: DocumentFile, out: MutableList<ImportCandidate>) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                collectAudioCandidates(file, out)
                continue
            }
            // DocumentFile.name 在个别 provider 上会是 null（尤其云盘/部分厂商的文件管理器），
            // 早先这里直接 continue，等于把文件静默丢掉；退回从 document URI 末段取文件名。
            val name = file.name?.takeIf { it.isNotBlank() } ?: nameFromUri(file.uri)
            if (name == null) {
                Log.w(TAG, "跳过无名条目（name 与 URI 都取不到文件名）：${file.uri}")
                continue
            }
            if (!isAudioFile(name, file.type)) {
                // 明示跳过原因：排查「某个格式没导进来」时，logcat 里一眼能看出是 MIME 还是扩展名的问题
                Log.i(TAG, "跳过非音频：name=$name, type=${file.type}")
                continue
            }
            out.add(
                ImportCandidate(
                    uri = file.uri,
                    // 标题去掉扩展名：内嵌标签读不到时用它兜底
                    name = if (name.contains(".")) name.substringBeforeLast(".") else name,
                    size = file.length()
                )
            )
        }
    }

    /** 从 document URI 末段取文件名（`.../document/1234%3Asong.m4a` → `song.m4a`） */
    private fun nameFromUri(uri: Uri): String? {
        val segment = uri.lastPathSegment ?: return null
        return segment.substringAfterLast('/').substringAfterLast(':').takeIf { it.isNotBlank() }
    }

    /** 是否是我们支持的音频（当前只处理 MP3，与单文件导入的判定保持一致） */
    /**
     * 是否是可导入的音频。
     *
     * 判断顺序：先看 MIME（SAF 提供方的权威信息），再退回扩展名
     * （不少第三方文件管理器给的 type 是 null 或 `application/octet-stream`）。
     * 早先只认 `.mp3` / `audio/mpeg`，m4a、flac 等都被静默跳过；
     * 播放（Media3）与字幕识别（MediaExtractor + MediaCodec）本来就支持这些容器，
     * 所以这里不该卡这么死。
     */
    private fun isAudioFile(name: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    private fun importAudioFiles(uris: List<Uri>) {
        // 获取持久化 URI 权限，确保后续可以访问
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w("PlaylistDetail", "Could not take persistable permission: ${e.message}")
            }
        }

        // 读时长/大小较慢（MediaMetadataRetriever），必须在 IO 线程做，不能堵在调用处的主线程
        lifecycleScope.launch {
            loadingOverlay.show()
            val items = try {
                withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val documentFile =
                            DocumentFile.fromSingleUri(this@PlaylistDetailActivity, uri)
                        // 选择器给回的 URI 也常取不到 name，同样退回 URI 末段
                        val name = documentFile?.name?.takeIf { it.isNotBlank() } ?: nameFromUri(uri)
                        if (name == null) {
                            Log.w(TAG, "跳过无名条目：$uri")
                            return@mapNotNull null
                        }
                        if (!isAudioFile(name, documentFile?.type)) {
                            Log.i(TAG, "跳过非音频：name=$name, type=${documentFile?.type}")
                            return@mapNotNull null
                        }

                        val title = if (name.contains(".")) name.substringBeforeLast(".") else name
                        buildImport(
                            uri = uri,
                            filePath = uri.toString(),
                            fallbackTitle = title,
                            fileSize = documentFile?.length() ?: 0L
                        )
                    }
                }
            } finally {
                loadingOverlay.hide()
            }

            if (items.isNotEmpty()) {
                viewModel.addAudioItems(playlistId, items)
            }
        }
    }

    private fun openPlayer(audioItem: AudioItem, position: Int) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, playlistId)
            putExtra(PlayerActivity.EXTRA_START_INDEX, position)
            // 同时给 audioId：队列因导入新音频而与数据库不一致时，靠 id 才不会定位错
            putExtra(PlayerActivity.EXTRA_START_AUDIO_ID, audioItem.id)
        }
        startActivity(intent)
    }

    private fun showAudioItemMenu(audioItem: AudioItem, view: View) {
        val popup = PopupMenu(this, view, Gravity.END)
        popup.setForceShowIcon(true)
        popup.menuInflater.inflate(R.menu.audio_item_menu, popup.menu)
        // 生成中禁用“生成字幕”，避免重复触发导致重复占用内存（乃至进程被系统杀掉）
        popup.menu.findItem(R.id.action_generate_subtitle)?.isEnabled =
            audioItem.subtitleStatus != SubtitleStatus.GENERATING
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> {
                    showDeleteAudioDialog(audioItem)
                    true
                }
                R.id.action_generate_subtitle -> {
                    if (audioItem.subtitleStatus != SubtitleStatus.GENERATING) {
                        viewModel.generateSubtitle(audioItem.id)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDeleteAudioDialog(audioItem: AudioItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_audio_title)
            .setMessage(getString(R.string.dialog_delete_audio_msg, audioItem.displayName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteAudioItem(audioItem.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 取消正在生成的字幕。
     *
     * 端侧识别在手机上是分钟~小时级，没有停止入口时用户只能杀进程。
     * 链路（已改为 FIFO 串行队列）：SubtitleGenerationWorker.cancelGeneration →
     * 若该音频正在被处理 → WhisperManager.cancel() 置位 native abort →
     * whisper.cpp 的 abort_callback 返回 true → whisper_full 中止 → worker 捕获
     * CancellationException 后把这条出队并**继续处理下一条**；
     * 若它还在排队（尚未开始）→ 直接从 SubtitleQueue 移除。
     * （旧实现用的是 WorkManager cancelUniqueWork，会把整条队列连坐取消，已废弃。）
     *
     * 注意：abort 标志**不是立刻生效**的。whisper.cpp 只在编码器窗口边界轮询它
     * （whisper_encode_internal 每个约 30s 音频窗口检查一次），所以最多要等当前
     * 窗口算完 native 才会返回、数据库状态才会离开 GENERATING。
     * 因此这里先调 [AudioItemAdapter.markCancelling] 让 UI 立即显示「正在取消…」，
     * 否则用户会以为按钮点了没反应。
     */
    private fun showCancelSubtitleDialog(audioItem: AudioItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cancel_subtitle)
            .setMessage(R.string.cancel_subtitle_confirm)
            .setPositiveButton(R.string.cancel_subtitle) { _, _ ->
                // 先给即时反馈，再真正发起取消
                adapter.markCancelling(audioItem.id)
                viewModel.cancelSubtitle(audioItem.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        private const val TAG = "PlaylistDetail"

        /** 批量导入的并发度：MediaMetadataRetriever 是 IO+CPU 混合的慢操作，4 路并发收益最高 */
        private const val IMPORT_PARALLELISM = 4

        /**
         * 扩展名兜底白名单：只列 Media3（播放）与 MediaCodec（字幕解码）都支持的容器，
         * 免得把 wma / ape 这类放进来后「能导入但播不了」。
         */
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus",
            "wav", "amr", "3gp", "3gpp", "mka", "m4p", "aif", "aiff", "caf"
        )
    }
}
