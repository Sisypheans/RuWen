package com.ruwen.audioplayer.ui.podcast

import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.databinding.ItemPodcastPreviewHeaderBinding
import com.ruwen.audioplayer.util.loadRemoteCover

/**
 * 在线预览页的头部（单条目适配器）。与 [PodcastHeaderAdapter] 分开：
 * 预览态没有「排序/刷新/取消订阅」，而是展示简介与「最近单集」标题。
 */
class PodcastPreviewHeaderAdapter : RecyclerView.Adapter<PodcastPreviewHeaderAdapter.VH>() {

    private var title: String = ""
    private var author: String? = null
    private var description: String? = null
    private var imageUrl: String? = null

    fun bind(title: String, author: String?, description: String?, imageUrl: String?) {
        this.title = title
        this.author = author
        this.description = description
        this.imageUrl = imageUrl
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPodcastPreviewHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind()

    inner class VH(private val binding: ItemPodcastPreviewHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.tvTitle.text = title
            binding.tvAuthor.text = author.orEmpty()
            binding.tvAuthor.visibility = if (author.isNullOrBlank()) View.GONE else View.VISIBLE
            // 空 url 时会主动 dispose + 占位图：不这么干会残留上一张封面
            binding.ivCover.loadRemoteCover(imageUrl, R.drawable.ic_playlist_empty)
            // 简介可能是 HTML（RSS 常见），用框架 API 转富文本，避免把标签原样显示
            binding.tvDescription.text = description?.takeIf { it.isNotBlank() }
                ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                .orEmpty()
        }
    }
}
