package com.ruwen.audioplayer.ui.podcast

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.databinding.ItemPodcastHeaderBinding
import com.ruwen.audioplayer.util.loadRemoteCover

/**
 * 播客详情页的头部（单条目适配器）。
 *
 * 用 ConcatAdapter 拼在单集列表前面，于是头部就是列表的第一项——会随列表滚动，
 * 不占据固定区域（AntennaPod 的头部同样是可滚走的）。
 * 单独做成适配器（而不是塞进 EpisodeAdapter）是为了不动后者的多选/排序逻辑。
 */
class PodcastHeaderAdapter(
    private val onDetailClick: () -> Unit,
    private val onSortClick: () -> Unit,
    private val onRefreshClick: () -> Unit,
    private val onUnsubscribeClick: () -> Unit
) : RecyclerView.Adapter<PodcastHeaderAdapter.HeaderViewHolder>() {

    private var podcast: Podcast? = null

    fun setPodcast(value: Podcast?) {
        podcast = value
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = ItemPodcastHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HeaderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(podcast)
    }

    inner class HeaderViewHolder(
        private val binding: ItemPodcastHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(podcast: Podcast?) {
            binding.tvTitle.text = podcast?.title.orEmpty()
            binding.tvAuthor.text = podcast?.author.orEmpty()
            binding.tvAuthor.visibility = if (podcast?.author.isNullOrBlank()) View.GONE else View.VISIBLE
            // 空 url 时会主动 dispose + 占位图：不这么干会残留上一张封面
            binding.ivCover.loadRemoteCover(podcast?.imageUrl, R.drawable.ic_playlist_empty)

            binding.btnDetail.setOnClickListener { onDetailClick() }
            binding.btnSort.setOnClickListener { onSortClick() }
            binding.btnRefresh.setOnClickListener { onRefreshClick() }
            binding.btnUnsubscribe.setOnClickListener { onUnsubscribeClick() }
        }
    }
}
