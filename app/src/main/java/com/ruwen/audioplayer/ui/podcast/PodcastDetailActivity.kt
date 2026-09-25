package com.ruwen.audioplayer.ui.podcast

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.DownloadStatus
import com.ruwen.audioplayer.data.entity.Episode
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.databinding.ActivityPodcastDetailBinding
import com.ruwen.audioplayer.ui.MiniPlayerController
import com.ruwen.audioplayer.ui.util.LoadingOverlay
import com.ruwen.audioplayer.util.purgeRemoteCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 播客详情页。
 *
 * 职责：展示单集、下载/删除、长按多选、排序、刷新（重新拉取 RSS）、
 * 详情（本地统计/描述/URL）、取消订阅（二次确认）。
 *
 * 删除单集 / 取消订阅会连带清理：
 *  本地音频文件 → 播放列表里的对应条目 → 该条目的字幕文件（确认无其他引用后才删）。
 *
 * 说明：播客单集**不接入播放**，下载完成后由用户在播放列表详情页"从播客添加"引入。
 */
class PodcastDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPodcastDetailBinding
    private lateinit var miniPlayerController: MiniPlayerController
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var headerAdapter: PodcastHeaderAdapter

    private val podcastRepository by lazy { RuWenApplication.getInstance().podcastRepository }
    private val playlistRepository by lazy { RuWenApplication.getInstance().playlistRepository }
    private val coverBackfill by lazy { RuWenApplication.getInstance().coverBackfill }

    /** 下载用的 HTTP 客户端，全页复用（每个请求新建会浪费连接池） */
    private val httpClient = OkHttpClient()

    /** 加载遮罩：刷新 RSS / 取消订阅（删文件 + 清缓存）期间挡住整页 */
    private val loadingOverlay by lazy { LoadingOverlay.attach(this) }

    private var podcastId: Long = -1L
    private var currentPodcast: Podcast? = null

    /**
     * 单集排序：**每个播客各一套**，存在 podcasts 表（Podcast.sortByTitle / sortAscending）。
     * 这里的初值只是占位，真正的取值在 [loadPodcast] 拿到播客行后覆盖（列表随后重排一次）。
     */
    private var sortOrder = SortOrder(byTitle = false, ascending = false)

    /** 单集排序方式 */
    private data class SortOrder(val byTitle: Boolean, val ascending: Boolean)

    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPodcastDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        podcastId = intent.getLongExtra(EXTRA_PODCAST_ID, -1L)
        if (podcastId <= 0L) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        miniPlayerController = MiniPlayerController(this)

        setupEpisodeList()
        observeEpisodes()
        loadPodcast()
    }

    override fun onStart() {
        super.onStart()
        miniPlayerController.onStart()
    }

    override fun onStop() {
        miniPlayerController.onStop()
        super.onStop()
    }

    private fun setupEpisodeList() {
        episodeAdapter = EpisodeAdapter(
            onItemClick = { episode ->
                if (selectionMode) toggleSelection(episode)
            },
            onLongClick = { episode ->
                if (!selectionMode) enterSelectionMode(episode)
                else toggleSelection(episode)
                true
            },
            onDownloadClick = { episode ->
                if (episode.downloadStatus == DownloadStatus.DOWNLOADED) confirmDeleteSingle(episode)
                else downloadEpisode(episode)
            }
        )
        // 头部（封面 + 标题 + 四个操作按钮）作为列表第一项：随列表滚动，不做固定区域
        headerAdapter = PodcastHeaderAdapter(
            onDetailClick = { showPodcastInfo() },
            onSortClick = { showSortDialog() },
            onRefreshClick = { refreshFeed() },
            onUnsubscribeClick = { confirmUnsubscribe() }
        )
        binding.episodeRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.episodeRecyclerView.adapter = ConcatAdapter(headerAdapter, episodeAdapter)

        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnDownloadSelected.setOnClickListener { downloadSelected() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
    }

    private fun loadPodcast() {
        lifecycleScope.launch {
            val podcast = podcastRepository.getPodcastById(podcastId)
            if (podcast == null) {
                finish()
                return@launch
            }
            currentPodcast = podcast
            supportActionBar?.title = podcast.title
            headerAdapter.setPodcast(podcast)
            // 该播客自己的排序：拿到播客行后用它的排序重排一次列表
            //（单集列表可能已经先按默认顺序提交过，所以这里要补一次）
            sortOrder = SortOrder(byTitle = podcast.sortByTitle, ascending = podcast.sortAscending)
            episodeAdapter.submitList(applySort(episodeAdapter.currentList))
        }
    }

    /**
     * 单集列表随数据库变化自动刷新。
     * 与 [loadPodcast] 分开注册：列表不依赖播客元信息，避免被元信息的查询阻塞。
     */
    private fun observeEpisodes() {
        podcastRepository.observeEpisodes(podcastId).observe(this) { list ->
            episodeAdapter.submitList(applySort(list))
        }
    }

    // ------------------------------------------------------------------
    //  排序
    // ------------------------------------------------------------------

    private fun applySort(list: List<Episode>): List<Episode> {
        val sorted = if (sortOrder.byTitle) {
            list.sortedBy { it.title.lowercase() }
        } else {
            list.sortedBy { it.pubDate }
        }
        return if (sortOrder.ascending) sorted else sorted.asReversed()
    }

    private fun showSortDialog() {
        val fields = arrayOf(
            getString(R.string.sort_by_title),
            getString(R.string.sort_by_date)
        )
        val orders = arrayOf(getString(R.string.sort_asc), getString(R.string.sort_desc))

        var pendingByTitle = sortOrder.byTitle
        var pendingAsc = sortOrder.ascending

        // 两段选择：先选排序字段（标题/日期），再选方向（正序/倒序）
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.podcast_sort)
            .setSingleChoiceItems(fields, if (sortOrder.byTitle) 0 else 1) { _, which ->
                pendingByTitle = which == 0
            }
            .setPositiveButton(R.string.confirm) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.podcast_sort)
                    .setSingleChoiceItems(orders, if (sortOrder.ascending) 0 else 1) { _, which ->
                        pendingAsc = which == 0
                    }
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        sortOrder = SortOrder(pendingByTitle, pendingAsc)
                        episodeAdapter.submitList(applySort(episodeAdapter.currentList))
                        // 落库到该播客这一行：下次进入（甚至杀进程后）仍是这一套
                        lifecycleScope.launch {
                            podcastRepository.updateSortOrder(podcastId, pendingByTitle, pendingAsc)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    //  详情 / 刷新 / 取消订阅
    // ------------------------------------------------------------------

    /**
     * 详情：打开独立页面（参照 AntennaPod 的 FeedInfoFragment，那里也是独立页面而非弹窗）。
     * 统计信息在那边按当前单集列表现算，避免这里再复制一份。
     */
    private fun showPodcastInfo() {
        val podcast = currentPodcast ?: return
        val episodes = episodeAdapter.currentList
        PodcastInfoActivity.start(
            this,
            podcast,
            episodes.size,
            episodes.count { it.downloadStatus == DownloadStatus.DOWNLOADED },
            episodes.sumOf { it.durationSec }
        )
    }

    /**
     * 刷新：重新拉 RSS 拿新单集，成功后**顺带补一次缺失的封面**。
     *
     * 补封面放在这里是因为它是唯一「用户明确表达了『我现在有网』」的时刻：
     * 导入时没网导致的空封面，只有在这种联网时机才补得回来。
     * 全程静默——补失败（图床不可达）不弹提示、也不改变刷新结果，
     * 否则用户会被一个跟刷新无关的网络问题干扰。
     */
    private fun refreshFeed() {
        lifecycleScope.launch {
            // 拉 RSS 是网络请求，后面还跟着一次封面补漏，全程不该让用户继续操作
            loadingOverlay.show()
            try {
                val result = podcastRepository.refresh(podcastId)
                val message = if (result.isSuccess) {
                    getString(R.string.refresh_done, result.getOrNull() ?: 0)
                } else {
                    getString(R.string.refresh_failed, result.exceptionOrNull()?.message.orEmpty())
                }
                Toast.makeText(this@PodcastDetailActivity, message, Toast.LENGTH_SHORT).show()

                if (result.isSuccess) {
                    runCatching { coverBackfill.backfill(this@PodcastDetailActivity) }
                }
            } finally {
                loadingOverlay.hide()
            }
        }
    }

    private fun confirmUnsubscribe() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.podcast_unsubscribe)
            .setMessage(R.string.confirm_unsubscribe)
            .setPositiveButton(R.string.delete) { _, _ -> unsubscribe() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unsubscribe() {
        lifecycleScope.launch {
            // 要删音频文件、改播放列表、清封面缓存，中途退出会留下半清理的状态
            loadingOverlay.show()
            try {
                val cleanup = podcastRepository.unsubscribe(podcastId)
                removeDownloadsFromPlaylists(cleanup.audioPaths)
                // 封面缓存已挪到持久目录（filesDir），退订后必须显式清掉，
                // 否则这些图会一直躺在机器里、且再也不会被展示。
                withContext(Dispatchers.IO) {
                    this@PodcastDetailActivity.purgeRemoteCovers(cleanup.coverUrls)
                }
                finish()
            } finally {
                loadingOverlay.hide()
            }
        }
    }

    // ------------------------------------------------------------------
    //  下载 / 删除
    // ------------------------------------------------------------------

    private fun downloadEpisode(episode: Episode) {
        lifecycleScope.launch {
            podcastRepository.markDownloading(episode.id)
            val result = runCatching { downloadTo(episode) }
            episodeAdapter.clearDownloadProgress(episode.id)
            if (result.isSuccess) {
                podcastRepository.markDownloaded(episode.id, result.getOrThrow())
            } else {
                podcastRepository.markDownloadFailed(episode.id)
                toastDownloadFailed(result.exceptionOrNull())
            }
        }
    }

    /**
     * 批量下载：**串行**执行。
     * 并行发起十几个大文件请求既占带宽又容易把源站打退，串行下进度也更好预期。
     */
    private fun downloadSelected() {
        val targets = episodeAdapter.currentList.filter {
            it.id in selectedIds && it.downloadStatus != DownloadStatus.DOWNLOADED
        }
        exitSelectionMode()
        if (targets.isEmpty()) return
        Toast.makeText(
            this, getString(R.string.episodes_downloading, targets.size), Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            targets.forEach { episode ->
                podcastRepository.markDownloading(episode.id)
                val result = runCatching { downloadTo(episode) }
                episodeAdapter.clearDownloadProgress(episode.id)
                if (result.isSuccess) {
                    podcastRepository.markDownloaded(episode.id, result.getOrThrow())
                } else {
                    podcastRepository.markDownloadFailed(episode.id)
                    toastDownloadFailed(result.exceptionOrNull())
                }
            }
        }
    }

    private fun toastDownloadFailed(error: Throwable?) {
        Toast.makeText(
            this@PodcastDetailActivity,
            getString(R.string.download_failed, error?.message.orEmpty()),
            Toast.LENGTH_SHORT
        ).show()
    }

    private suspend fun downloadTo(episode: Episode): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(episode.audioUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(this@PodcastDetailActivity.getString(R.string.err_http, response.code))
            val body = response.body
            // 源站未声明长度时为 -1：此时不报进度，UI 显示不定进度环
            val totalBytes = body.contentLength()
            val dir = File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "podcasts/${episode.podcastId}"
            )
            dir.mkdirs()
            val file = File(dir, "${episode.id}.${extensionOf(episode.audioUrl)}")
            // 先写 .part 再原子改名：中途失败只留下临时文件（会被清掉），
            // 不会出现"文件在位却是半个坏文件"的情况。
            val temp = File(dir, "${file.name}.part")
            try {
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes > 0) {
                                val percent = ((copied * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) {
                                        episodeAdapter.updateDownloadProgress(episode.id, percent)
                                    }
                                }
                            }
                        }
                    }
                }
                if (temp.exists() && !temp.renameTo(file)) error(this@PodcastDetailActivity.getString(R.string.err_file_write_failed))
            } finally {
                temp.delete()
            }
            file.absolutePath
        }
    }

    private fun extensionOf(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('.', "")
        return raw.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"
    }

    private fun confirmDeleteSingle(episode: Episode) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.confirm_delete_single_download)
            .setPositiveButton(R.string.delete) { _, _ -> deleteEpisodes(listOf(episode.id)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.confirm_delete_episodes)
            .setPositiveButton(R.string.delete) { _, _ ->
                val ids = selectedIds.toList()
                exitSelectionMode()
                deleteEpisodes(ids)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 删除单集的下载：**单集记录保留**（列表里变回「未下载」，
     * 之前直接删行会让这一集从列表里消失，那是 bug），
     * 只删除本地文件并连带清理播放列表里的引用与字幕。
     */
    private fun deleteEpisodes(ids: List<Long>) {
        lifecycleScope.launch {
            val paths = podcastRepository.clearEpisodeDownloads(ids)
            removeDownloadsFromPlaylists(paths)
        }
    }

    /**
     * 删除已下载音频时的连带清理：
     * 播放列表里的对应条目（按文件路径匹配，同一集可能在多个列表里）→ 其字幕文件
     * （字幕是共享资产，确认无其他条目引用后才删）→ 最后删音频文件本身。
     */
    private suspend fun removeDownloadsFromPlaylists(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path ->
            val orphanFiles = playlistRepository.removeAudioByFilePath(path)
            orphanFiles.forEach { runCatching { File(it).delete() } }
            runCatching { File(path).delete() }
            // 该播客的下载目录（`.../podcasts/<podcastId>`）空了就删掉，
            // 不留一个再也不会被用到的空文件夹
            runCatching {
                File(path).parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
            }
        }
    }

    // ------------------------------------------------------------------
    //  多选
    // ------------------------------------------------------------------

    private fun enterSelectionMode(first: Episode) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(first.id)
        episodeAdapter.inSelectionMode = true
        episodeAdapter.selectedIds = selectedIds.toSet()
        binding.selectionBar.visibility = View.VISIBLE
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        episodeAdapter.inSelectionMode = false
        episodeAdapter.selectedIds = emptySet()
        binding.selectionBar.visibility = View.GONE
    }

    private fun toggleSelection(episode: Episode) {
        if (!selectedIds.add(episode.id)) selectedIds.remove(episode.id)
        episodeAdapter.selectedIds = selectedIds.toSet()
    }

    private fun toggleSelectAll() {
        val allIds = episodeAdapter.currentList.map { it.id }
        if (selectedIds.containsAll(allIds)) selectedIds.clear()
        else selectedIds.addAll(allIds)
        episodeAdapter.selectedIds = selectedIds.toSet()
    }

    companion object {
        const val EXTRA_PODCAST_ID = "extra_podcast_id"

        fun start(activity: android.app.Activity, podcastId: Long) {
            val intent = Intent(activity, PodcastDetailActivity::class.java).apply {
                putExtra(EXTRA_PODCAST_ID, podcastId)
            }
            activity.startActivity(intent)
        }
    }
}
