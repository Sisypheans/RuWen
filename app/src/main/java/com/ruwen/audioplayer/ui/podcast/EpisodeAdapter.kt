package com.ruwen.audioplayer.ui.podcast

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.entity.DownloadStatus
import com.ruwen.audioplayer.data.entity.Episode
import com.ruwen.audioplayer.databinding.ItemEpisodeBinding
import com.ruwen.audioplayer.util.loadRemoteCover
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播客单集列表适配器。
 *
 *  - 正常态：右侧按钮按下载状态自动切换（已下载→删除图标，其他→下载图标）；
 *    下载中在图标外套一圈 **进度环**（进度未知时为不定环），批量下载时能逐条看进度。
 *  - 多选态：显示左侧勾选框、隐藏右侧按钮，下载/删除统一由底部操作栏执行。
 *
 * 下载进度只保存在内存（[downloadProgress]，key = 单集 id）：离开页面后进度丢失，
 * 回到页面时下载中的条目显示为不定环（用户确认按内存方案做，不落库）。
 */
class EpisodeAdapter(
    private val onItemClick: (Episode) -> Unit,
    private val onLongClick: (Episode) -> Boolean,
    private val onDownloadClick: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.EpisodeViewHolder>(DiffCallback) {

    /** 是否显示右侧下载/删除按钮。订阅前的在线预览页只需展示单集，置 false 即可 */
    var actionsEnabled: Boolean = true

    /** 是否处于长按后的多选状态 */
    var inSelectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // 每行的勾选框显隐都变了 → 整段刷新（用 range 版本，不用 notifyDataSetChanged）
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /** 多选状态下被选中的单集 id */
    var selectedIds: Set<Long> = emptySet()
        set(value) {
            val previous = field
            field = value
            if (!inSelectionMode) return
            // 只刷新勾选态真的变了的那几行：每次点勾选都全量刷会打断滚动/点击
            currentList.forEachIndexed { index, episode ->
                if ((episode.id in previous) != (episode.id in value)) notifyItemChanged(index)
            }
        }

    /** 正在下载的单集的进度（0..100），key = 单集 id */
    private val downloadProgress = mutableMapOf<Long, Int>()

    /** 更新某条下载进度并只刷新那一条 */
    fun updateDownloadProgress(episodeId: Long, progress: Int) {
        downloadProgress[episodeId] = progress
        val index = currentList.indexOfFirst { it.id == episodeId }
        if (index >= 0) notifyItemChanged(index)
    }

    /** 下载结束（成功/失败/取消）后清掉进度记录 */
    fun clearDownloadProgress(episodeId: Long) {
        if (downloadProgress.remove(episodeId) != null) {
            val index = currentList.indexOfFirst { it.id == episodeId }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EpisodeViewHolder(
        private val binding: ItemEpisodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(episode: Episode) {
            binding.tvTitle.text = episode.title
            binding.ivCover.loadRemoteCover(episode.imageUrl, R.drawable.ic_playlist_empty)

            // 三行排版（参照 AntennaPod）：第一行 发布日期·大小，第二行 标题，第三行 时长·下载状态
            binding.tvMeta.text = listOf(
                formatEpisodeDate(episode.pubDate),
                formatBytes(episode.sizeBytes)
            ).filter { it.isNotBlank() }.joinToString(" · ")

            binding.tvStatus.text = listOf(
                formatDuration(episode.durationSec),
                downloadStatusText(binding.root.context, episode.downloadStatus)
            ).filter { it.isNotBlank() }.joinToString(" · ")

            // 多选态：显示勾选框、隐藏右侧按钮；预览模式（actionsEnabled=false）也不显示按钮
            binding.cbSelect.visibility = if (inSelectionMode) View.VISIBLE else View.GONE
            binding.btnDownload.visibility =
                if (inSelectionMode || !actionsEnabled) View.GONE else View.VISIBLE
            binding.cbSelect.isChecked = episode.id in selectedIds

            // 已下载的显示"删除"图标，其余显示"下载"图标
            val isDownloaded = episode.downloadStatus == DownloadStatus.DOWNLOADED
            binding.ivDownloadIcon.setImageResource(
                if (isDownloaded) R.drawable.ic_delete else R.drawable.ic_download
            )
            binding.ivDownloadIcon.contentDescription = binding.root.context.getString(
                if (isDownloaded) R.string.delete else R.string.download
            )

            bindDownloadProgress(episode)

            binding.root.setOnClickListener { onItemClick(episode) }
            binding.root.setOnLongClickListener { onLongClick(episode) }
            binding.btnDownload.setOnClickListener { onDownloadClick(episode) }
        }

        /** 下载中 → 图标外套进度环（进度未知时用不定环）；其余状态隐藏环 */
        private fun bindDownloadProgress(episode: Episode) {
            val indicator = binding.downloadProgress
            if (episode.downloadStatus != DownloadStatus.DOWNLOADING) {
                indicator.visibility = View.GONE
                return
            }
            indicator.visibility = View.VISIBLE
            val progress = downloadProgress[episode.id]
            if (progress != null) {
                indicator.isIndeterminate = false
                indicator.setProgressCompat(progress, false)
            } else {
                indicator.isIndeterminate = true
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Episode, newItem: Episode): Boolean =
            oldItem == newItem
    }
}

private fun downloadStatusText(context: android.content.Context, status: DownloadStatus): String =
    context.getString(
        when (status) {
            DownloadStatus.NOT_DOWNLOADED -> R.string.status_not_downloaded
            DownloadStatus.DOWNLOADING -> R.string.status_downloading
            DownloadStatus.DOWNLOADED -> R.string.status_downloaded
            DownloadStatus.FAILED -> R.string.status_download_failed
        }
    )

/** 发布日期；源未提供时返回空串，由调用方决定是否拼接 */
fun formatEpisodeDate(pubDate: Long): String {
    if (pubDate <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(pubDate))
}

/** 字节数转 MB / KB；源未声明时返回空串 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%d KB", (bytes / 1024).toInt().coerceAtLeast(1))
    }
}

/** 秒转 HH:MM:SS 或 MM:SS */
fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
