package com.ruwen.audioplayer.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.checkbox.MaterialCheckBox
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.SubtitleProgressStore
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.SubtitleIdentity
import com.ruwen.audioplayer.data.entity.SubtitleStatus
import com.ruwen.audioplayer.data.entity.displayName
import com.ruwen.audioplayer.util.SubtitleUtils
import com.ruwen.audioplayer.util.placeholderImage

/**
 * 音频列表适配器。
 *
 * **为什么不用 ListAdapter**：它内部的列表是不可变快照，而长按拖动排序要求
 * **同步**地交换条目并 `notifyItemMoved`（拖动过程中每帧都要更新，等不了异步 diff）。
 * 所以这里自己维护一个可变列表 [items]；对外仍提供 `submitList`，
 * 内部手动跑 DiffUtil，保留原有的增删/移动动画。
 *
 * 三种形态：
 *  - **普通**：点击整行 = 播放；左侧封面 + 标题，右侧是「更多」菜单；
 *    「取消生成」按钮**只在生成中 / 排队时**出现（生成入口统一收在「更多」菜单里）；
 *  - **多选**：左侧出现勾选框，点击整行 = 切换选中，右侧单行按钮隐藏（由 Activity 控制开关）；
 *  - **正在取消**：生成中且已发起取消的条目显示「正在取消…」并置灰。
 */
class AudioItemAdapter(
    private val onItemClick: (AudioItem, Int) -> Unit,
    private val onMenuClick: (AudioItem, View) -> Unit,
    private val onCancelSubtitleClick: (AudioItem) -> Unit,
    /** 选中集合发生变化时通知 Activity 刷新底部操作栏的「已选 N 项」 */
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<AudioItemAdapter.AudioItemViewHolder>() {

    /** 当前展示的数据；顺序即播放顺序，拖动排序直接改它 */
    private val items = mutableListOf<AudioItem>()

    /** 是否处于多选模式 */
    var selectionMode: Boolean = false
        private set

    /** 多选模式下已勾选的音频 id（LinkedHashSet 让勾选顺序稳定） */
    private val selectedIds = linkedSetOf<Long>()

    private var selectedPosition: Int = -1

    /**
     * 已发出「取消」请求、但 native 侧还没真正停下来的条目。
     *
     * 纯 UI 状态，不落库：whisper.cpp 的 abort_callback 只在编码器窗口边界被轮询
     * （whisper_encode_internal 每个约 30s 音频窗口检查一次），设备负载高时要等
     * 当前窗口算完才返回，这段时间内数据库状态仍是 GENERATING。
     * 不先给出「正在取消…」的即时反馈，用户会误以为按钮点了没效果。
     */
    private val cancellingIds = mutableSetOf<Long>()

    /**
     * 字幕生成进度（内存态，key = 共享字幕文件名）。
     *
     * 由页面订阅 [SubtitleProgressStore] 后塞进来——进度不再落库，因为它是连续变化值，
     * 逐百分比写库会让所有观察 audio_items 的列表被反复刷新（详见该类的注释）。
     * 赋值时只刷新「该条进度真的变了」的行，不做全量刷新，避免打断用户点击。
     */
    var subtitleProgress: Map<String, SubtitleProgressStore.Progress> = emptyMap()
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            items.forEachIndexed { index, item ->
                if (item.subtitleStatus != SubtitleStatus.GENERATING) return@forEachIndexed
                val key = SubtitleIdentity.fileNameOf(item)
                if (previous[key] != value[key]) notifyItemChanged(index)
            }
        }

    // ------------------------------------------------------------------
    //  数据与选择状态
    // ------------------------------------------------------------------

    /** 提交新数据（内部跑 DiffUtil 保留动画） */
    fun submitList(newList: List<AudioItem>) {
        val diff = DiffUtil.calculateDiff(DiffCallback(items.toList(), newList))
        items.clear()
        items.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    /** 当前顺序的快照（拖动排序结束后据此写库） */
    fun currentItems(): List<AudioItem> = items.toList()

    /** 开关多选模式；退出时清空已勾选项 */
    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        // 每行的勾选框显隐都变了 → 本就该整段刷新，但用 range 版而非 notifyDataSetChanged：
        // 后者会连带丢掉条目动画与 View 状态
        notifyItemRangeChanged(0, items.size)
        onSelectionChanged()
    }

    fun selectAll() {
        selectedIds.clear()
        items.forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, items.size)
        onSelectionChanged()
    }

    /** 是否已全部勾选（空列表算「未全选」，避免按钮一上来就显示成反操作） */
    fun isAllSelected(): Boolean = items.isNotEmpty() && selectedIds.size == items.size

    /**
     * 全选 / 取消全选：已全选则清空，否则全选。
     *
     * @return 切换**之后**是否处于全选状态，供调用方同步按钮文案。
     */
    fun toggleSelectAll(): Boolean {
        val wasAllSelected = isAllSelected()
        if (wasAllSelected) {
            selectedIds.clear()
        } else {
            selectAll()   // 内部已含刷新与回调，避免重复写一遍
            return true
        }
        notifyItemRangeChanged(0, items.size)
        onSelectionChanged()
        return false
    }

    /** 切换单条勾选（多选模式下点击整行） */
    fun toggleSelection(audioId: Long) {
        if (!selectedIds.remove(audioId)) selectedIds.add(audioId)
        val position = items.indexOfFirst { it.id == audioId }
        if (position >= 0) notifyItemChanged(position)
        onSelectionChanged()
    }

    fun selectedAudioIds(): List<Long> = selectedIds.toList()

    fun selectedCount(): Int = selectedIds.size

    /**
     * 拖动排序：把 from 位置的条目移到 to 位置。
     * 直接改本地列表 + notifyItemMoved —— 必须同步完成，否则拖动手感会卡。
     */
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (position >= 0) notifyItemChanged(position)
    }

    private var currentAudioId: Long = -1L

    /**
     * 设置「当前正在播放的音频 id」，列表据此高亮对应条目。
     *
     * 关键点：选中态必须由**真实播放状态**驱动，而不是像 [setSelectedPosition] 那样记录点击位置——
     * 否则自动切下一首、通知栏 / 耳机切歌、从小播放器播放时，列表都不会跟着更新。
     * 这里只刷新变化的两个位置，避免 notifyDataSetChanged 造成整列表闪烁重绘。
     */
    fun setCurrentAudioId(audioId: Long) {
        if (currentAudioId == audioId) return
        val oldPos = items.indexOfFirst { it.id == currentAudioId }
        currentAudioId = audioId
        val newPos = items.indexOfFirst { it.id == audioId }
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (newPos >= 0) notifyItemChanged(newPos)
    }

    /** 标记为「取消中」并立刻刷新该行：点击取消后 1 帧内就有可见反馈 */
    fun markCancelling(audioId: Long) {
        if (cancellingIds.add(audioId)) {
            val pos = items.indexOfFirst { it.id == audioId }
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    // ------------------------------------------------------------------
    //  RecyclerView.Adapter
    // ------------------------------------------------------------------

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio, parent, false)
        return AudioItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioItemViewHolder, position: Int) {
        val audioItem = items[position]
        holder.bind(
            audioItem = audioItem,
            // 由真实播放状态驱动（见 setCurrentAudioId），而不是点击位置
            isPlaying = audioItem.id == currentAudioId,
            inSelectionMode = selectionMode,
            isChecked = audioItem.id in selectedIds
        )
    }

    inner class AudioItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.tvTitle)
        private val durationTextView: TextView = itemView.findViewById(R.id.tvDuration)
        private val subtitleStatusView: TextView = itemView.findViewById(R.id.tvSubtitleStatus)
        private val menuButton: ImageButton = itemView.findViewById(R.id.btnMenu)
        private val cancelButton: ImageButton = itemView.findViewById(R.id.btnCancelSubtitle)
        private val coverView: ImageView = itemView.findViewById(R.id.ivCover)
        private val selectCheckBox: MaterialCheckBox = itemView.findViewById(R.id.cbSelect)

        fun bind(
            audioItem: AudioItem,
            isPlaying: Boolean,
            inSelectionMode: Boolean,
            isChecked: Boolean
        ) {
            // 让根布局进入 activated 状态：bg_item_audio 据此铺选中底色，
            // item_title_color 据此把标题染成强调色；点击水波纹由 RippleDrawable 自带。
            itemView.isActivated = isPlaying

            titleTextView.text = audioItem.displayName
            durationTextView.text = SubtitleUtils.formatDurationLong(audioItem.duration)

            bindCover(audioItem.coverPath)

            // ViewHolder 会被复用：先把禁用/半透明状态还原，
            // 避免上一条「取消中」的置灰样式被带过来。
            cancelButton.isEnabled = true
            cancelButton.alpha = 1f

            // 状态已离开 GENERATING（取消完成 / 生成结束）→ 清掉 UI 侧的取消标记
            if (audioItem.subtitleStatus != SubtitleStatus.GENERATING) {
                cancellingIds.remove(audioItem.id)
            }
            val isCancelling = audioItem.id in cancellingIds

            // 字幕状态
            when (audioItem.subtitleStatus) {
                SubtitleStatus.NOT_GENERATED -> {
                    subtitleStatusView.text = itemView.context.getString(R.string.subtitle_not_generated)
                    subtitleStatusView.visibility = View.VISIBLE
                    cancelButton.visibility = View.GONE
                }
                SubtitleStatus.GENERATING -> {
                    // 生成中才给出取消入口：端侧识别是分钟~小时级，
                    // 没有停止入口的话用户除了杀进程别无他法
                    if (isCancelling) {
                        // 已发起取消：不再显示进度/ETA，改为「正在取消…」，
                        // 按钮置灰防止重复点击（重复请求没有意义）
                        subtitleStatusView.text = itemView.context.getString(R.string.subtitle_cancelling)
                        cancelButton.isEnabled = false
                        cancelButton.alpha = 0.4f
                    } else {
                        subtitleStatusView.text = buildGeneratingText(audioItem)
                    }
                    subtitleStatusView.visibility = View.VISIBLE
                    showCancelButton()
                }
                SubtitleStatus.GENERATED -> {
                    subtitleStatusView.text = itemView.context.getString(R.string.subtitle_ready)
                    subtitleStatusView.visibility = View.VISIBLE
                    cancelButton.visibility = View.GONE
                }
                SubtitleStatus.QUEUED -> {
                    // 已入队、等待 Worker 取出：同样给取消入口（移出队列）
                    subtitleStatusView.text = itemView.context.getString(R.string.subtitle_queued)
                    subtitleStatusView.visibility = View.VISIBLE
                    showCancelButton()
                }
                SubtitleStatus.FAILED -> {
                    val err = audioItem.subtitleError
                    subtitleStatusView.text = if (!err.isNullOrEmpty()) {
                        itemView.context.getString(R.string.subtitle_failed_with_reason, err)
                    } else {
                        itemView.context.getString(R.string.subtitle_failed)
                    }
                    subtitleStatusView.visibility = View.VISIBLE
                    cancelButton.visibility = View.GONE
                }
                else -> {
                    subtitleStatusView.text = itemView.context.getString(R.string.subtitle_not_generated)
                    subtitleStatusView.visibility = View.VISIBLE
                    cancelButton.visibility = View.GONE
                }
            }

            // 多选模式：显示勾选框、隐藏单行操作按钮（避免与「点整行 = 勾选」冲突）
            selectCheckBox.visibility = if (inSelectionMode) View.VISIBLE else View.GONE
            selectCheckBox.isChecked = isChecked
            if (inSelectionMode) {
                cancelButton.visibility = View.GONE
                menuButton.visibility = View.GONE
            } else {
                menuButton.visibility = View.VISIBLE
            }

            // 选中状态（正在播放）：只保留高亮，不再额外展示"正在播放"图标
            if (isPlaying) {
                itemView.setBackgroundResource(R.drawable.bg_item_selected)
                titleTextView.setTextColor(itemView.context.getColor(R.color.purple_primary))
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                titleTextView.setTextColor(itemView.context.getColor(R.color.text_primary))
            }

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selectionMode) {
                    toggleSelection(audioItem.id)
                } else {
                    onItemClick(audioItem, position)
                }
            }
            menuButton.setOnClickListener { onMenuClick(audioItem, it) }
            cancelButton.setOnClickListener {
                // 已在取消流程中就忽略重复点击
                if (audioItem.id in cancellingIds) return@setOnClickListener
                onCancelSubtitleClick(audioItem)
            }
        }

        /**
         * 封面：有本地文件就加载，没有就显示占位图。
         * **必须显式设置占位图**——ViewHolder 复用时若不重置，会残留上一条的封面。
         */
        private fun bindCover(coverPath: String?) {
            val file = coverPath?.takeIf { it.isNotEmpty() }?.let(::File)
            if (file != null && file.exists()) {
                val placeholder = coverView.context.placeholderImage(R.drawable.ic_playlist_empty)
                coverView.load(Uri.fromFile(file)) {
                    crossfade(true)
                    placeholder(placeholder)
                    error(placeholder)
                }
            } else {
                coverView.setImageResource(R.drawable.ic_playlist_empty)
            }
        }

        /** 显示为「取消生成」按钮；图标是布局里固定的叉号，这里只管显隐与文案 */
        private fun showCancelButton() {
            cancelButton.contentDescription =
                itemView.context.getString(R.string.cancel_subtitle)
            cancelButton.visibility = View.VISIBLE
        }

        /**
         * 「生成中 42% · 预计还需 1 小时 5 分钟」
         * 剩余时间由 WhisperManager 按当前实时倍率外推，还没法估计时只显示百分比。
         */
        private fun buildGeneratingText(audioItem: AudioItem): String {
            // 进度只来自内存态（DB v9 起不再存进度）。
            // 拿不到进度（例如进程重启后停在"生成中"）就只显示「生成中…」，
            // 不再假装是 0%。
            val live = subtitleProgress[SubtitleIdentity.fileNameOf(audioItem)]
                ?: return itemView.context.getString(R.string.subtitle_generating_plain)
            val eta = live.etaMillis
            return if (eta > 0L) {
                itemView.context.getString(
                    R.string.subtitle_generating_with_eta,
                    live.percent,
                    SubtitleUtils.formatEta(itemView.context, eta)
                )
            } else {
                itemView.context.getString(R.string.subtitle_generating, live.percent)
            }
        }
    }

    /** 手动 diff（替代 ListAdapter 的 ItemCallback） */
    private class DiffCallback(
        private val oldList: List<AudioItem>,
        private val newList: List<AudioItem>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition].id == newList[newItemPosition].id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            // data class 的 == 覆盖全部字段即够：字幕进度已不在实体里
            // （放内存态 SubtitleProgressStore），不受它的更新影响。
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
