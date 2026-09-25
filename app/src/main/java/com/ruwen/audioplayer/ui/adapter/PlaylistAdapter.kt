package com.ruwen.audioplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.entity.Playlist
import java.io.File

/**
 * 播放列表适配器（排版参照 Salt Player 歌单页）：
 * 封面 + 名称 + 「N 首」 + 右侧三点菜单。
 *
 * 长按拖动排序由 [onItemMove] 承担：拖动过程中在 currentList 快照上移动并提交，
 * 松手后由宿主 Fragment 取 [orderedIds] 落库（写 sortPosition）。
 * 拖动期间外部 LiveData 的新列表**不提交**（[isDragging]），避免拖到一半被 diff 重置。
 */
class PlaylistAdapter(
    private val onItemClick: (Playlist) -> Unit,
    private val onMenuClick: (Playlist, View) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    /**
     * 各播放列表的音频条数，key = playlistId；宿主从独立的 LiveData 观察后塞进来。
     *
     * ⚠️ 这里**绝不能**无脑 `notifyDataSetChanged()`：字幕生成期间 Worker 会高频写
     * `audio_items`（解码阶段每 1% 就写一次），Room 每次都让计数查询失效并重新下发。
     * 若每次都全量刷新，用户手指按下（ACTION_DOWN）到抬起（ACTION_UP）之间条目被重绑，
     * RecyclerView 就会把这次点击丢掉——表现为「生成字幕时点播放列表没反应，
     * 进度过了 50%（写入变稀疏）就又能点了」。
     * 正确做法：条数没变直接返回；变了也只刷新真的变了的那些行。
     */
    var audioCounts: Map<Long, Int> = emptyMap()
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            currentList.forEachIndexed { index, playlist ->
                if (previous[playlist.id] != value[playlist.id]) notifyItemChanged(index)
            }
        }

    /** 拖动进行中：挂起外部 submitList */
    var isDragging: Boolean = false

    /** 拖动一次（onMove 回调）。返回是否处理了 */
    fun onItemMove(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from < 0 || to < 0 || from >= itemCount || to >= itemCount) return false
        val list = currentList.toMutableList()
        list.add(to, list.removeAt(from))
        submitList(list)
        return true
    }

    /** 当前展示顺序的 id 列表（拖动结束落库用） */
    fun orderedIds(): List<Long> = currentList.map { it.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverView: ImageView = itemView.findViewById(R.id.ivCover)
        private val nameTextView: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val countTextView: TextView = itemView.findViewById(R.id.tvCount)
        private val menuButton: ImageButton = itemView.findViewById(R.id.btnMenu)

        fun bind(playlist: Playlist) {
            nameTextView.text = playlist.name
            countTextView.text = itemView.context.getString(
                R.string.count_audios, audioCounts[playlist.id] ?: 0
            )
            bindCover(playlist)

            itemView.setOnClickListener { onItemClick(playlist) }
            menuButton.setOnClickListener { onMenuClick(playlist, it) }
        }

        /**
         * 列表封面：有封面文件就显示，否则回到默认光盘占位图（不套 tint，占位图自带配色）。
         *
         * ⚠️ 无封面时必须**先 dispose 掉可能仍在飞的上一次请求**：
         * 封面是异步加载的，拖动排序会让 ViewHolder 被复用；若只 `setImageResource(占位图)`，
         * 旧请求仍持有这个 ImageView，它完成后会把上一张封面**写回来**，
         * 表现为「没封面的列表拖动时显示成相邻条目的封面」。
         */
        private fun bindCover(playlist: Playlist) {
            val file = playlist.coverUri?.takeIf { it.isNotBlank() }?.let(::File)
            if (file != null && file.exists()) {
                coverView.load(file)
            } else {
                coverView.dispose()
                coverView.setImageResource(R.drawable.ic_disc_default)
            }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem
        }
    }
}
