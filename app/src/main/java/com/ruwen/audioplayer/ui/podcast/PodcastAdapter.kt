package com.ruwen.audioplayer.ui.podcast

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.util.loadRemoteCover
import com.ruwen.audioplayer.databinding.ItemPodcastBinding

/**
 * 播客封面网格适配器（3×N）。
 *
 * 封面用 Coil 加载：取消/占位/缓存都由 Coil 处理，无需自己做异步与复用错乱防护。
 */
class PodcastAdapter(
    private val onItemClick: (Podcast) -> Unit
) : ListAdapter<Podcast, PodcastAdapter.PodcastViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PodcastViewHolder {
        val binding = ItemPodcastBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PodcastViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PodcastViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PodcastViewHolder(
        private val binding: ItemPodcastBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(podcast: Podcast) {
            binding.tvTitle.text = podcast.title
            binding.ivCover.loadRemoteCover(podcast.imageUrl, R.drawable.ic_playlist_empty)
            binding.root.setOnClickListener { onItemClick(podcast) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Podcast>() {
        override fun areItemsTheSame(oldItem: Podcast, newItem: Podcast): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Podcast, newItem: Podcast): Boolean =
            oldItem == newItem
    }
}
