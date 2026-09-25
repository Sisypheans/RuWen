package com.ruwen.audioplayer.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.remote.PodcastSearchResult
import com.ruwen.audioplayer.databinding.ItemSearchResultBinding
import com.ruwen.audioplayer.util.loadRemoteCover

/**
 * 在线搜索结果列表。
 *
 * 点击**条目本身**= 打开预览页（先看内容再决定，不订阅）；
 * 订阅统一在预览页进行，列表项不放订阅按钮，避免「还没看清是什么就订阅了」。
 */
class SearchResultAdapter(
    private val onItemClick: (PodcastSearchResult) -> Unit
) : ListAdapter<PodcastSearchResult, SearchResultAdapter.ResultViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(
        private val binding: ItemSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: PodcastSearchResult) {
            binding.tvTitle.text = result.title
            binding.tvAuthor.text = result.author.orEmpty()
            binding.tvAuthor.visibility =
                if (result.author.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.ivCover.loadRemoteCover(result.imageUrl, R.drawable.ic_playlist_empty)

            // 点整条 = 打开预览页（订阅在预览页进行）
            binding.root.setOnClickListener { onItemClick(result) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<PodcastSearchResult>() {
        // 同一 RSS 地址视为同一条（不同源可能搜到同一个播客）
        override fun areItemsTheSame(oldItem: PodcastSearchResult, newItem: PodcastSearchResult): Boolean =
            oldItem.feedUrl == newItem.feedUrl

        override fun areContentsTheSame(oldItem: PodcastSearchResult, newItem: PodcastSearchResult): Boolean =
            oldItem == newItem
    }
}
