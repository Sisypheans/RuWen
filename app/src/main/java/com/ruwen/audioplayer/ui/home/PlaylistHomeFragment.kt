package com.ruwen.audioplayer.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.entity.Playlist
import com.ruwen.audioplayer.databinding.FragmentPlaylistHomeBinding
import com.ruwen.audioplayer.ui.adapter.PlaylistAdapter
import com.ruwen.audioplayer.ui.playlist.PlaylistDetailActivity
import com.ruwen.audioplayer.ui.viewmodel.MainViewModel

/**
 * 首页：播放列表（本地音频 + 字幕的入口）。
 *
 * 原来是 MainActivity 的全部内容，为接入底部导航（首页/播客/搜索）改造成 Fragment，
 * 由 MainActivity 作为宿主承载。工具栏、迷你播放栏、底部导航都在宿主上，不随页面切换重建。
 */
class PlaylistHomeFragment : Fragment() {

    private var _binding: FragmentPlaylistHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupRecyclerView()
        setupFab()
        observeData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = PlaylistAdapter(
            onItemClick = { playlist -> openPlaylistDetail(playlist) },
            onMenuClick = { playlist, anchor -> showPlaylistMenu(playlist, anchor) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        attachDragToSort()
    }

    /**
     * 长按拖动排序：上下拖动交换列表顺序，松手后把新顺序落库（写 sortPosition）。
     * 默认顺序 = 创建顺序（未拖动过的列表 sortPosition 都是 0，按 id 升序展示）。
     */
    private fun attachDragToSort() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = adapter.onItemMove(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition
            )

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter.isDragging = true
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (adapter.isDragging) {
                    adapter.isDragging = false
                    viewModel.updatePlaylistOrder(adapter.orderedIds())
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener { showCreatePlaylistDialog() }
    }

    private fun observeData() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            // 拖动进行中不提交外部新列表，否则会把正在拖的顺序重置掉
            if (!adapter.isDragging) adapter.submitList(playlists)
            if (playlists.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
            }
        }
        viewModel.audioCounts.observe(viewLifecycleOwner) { counts ->
            adapter.audioCounts = counts
        }
    }

    private fun openPlaylistDetail(playlist: Playlist) {
        val intent = Intent(requireContext(), PlaylistDetailActivity::class.java).apply {
            putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
        }
        startActivity(intent)
    }

    private fun showPlaylistMenu(playlist: Playlist, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.setForceShowIcon(true)
        popup.menuInflater.inflate(R.menu.playlist_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    showRenameDialog(playlist)
                    true
                }
                R.id.action_delete -> {
                    showDeleteDialog(playlist)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * 创建 / 重命名播放列表共用的输入对话框。
     *
     * 用 Material 官方推荐的 TextInputLayout + TextInputEditText（而非裸 EditText）：
     * 浮动标签、主题化描边、内置错误提示；间距用 dp 而非像素。
     *
     * 注意：代码创建时**必须**用 TextInputLayout 的 context 构造 TextInputEditText，
     * 否则 TextInputLayout 无法把样式传递给子 EditText。
     */
    private fun showPlaylistNameDialog(
        titleRes: Int,
        initialName: String = "",
        onConfirm: (String) -> Unit
    ) {
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.playlist_name)
            val density = resources.displayMetrics.density
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            setText(initialName)
            if (initialName.isNotEmpty()) setSelection(initialName.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        inputLayout.addView(editText)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(inputLayout)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 接管「确定」：名称为空时留在弹窗内提示，而不是直接关闭后静默丢弃输入
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    inputLayout.error = getString(R.string.playlist_name_empty)
                    return@setOnClickListener
                }
                inputLayout.error = null
                onConfirm(name)
                dialog.dismiss()
            }
        }
        dialog.show()
        editText.requestFocus()
    }

    private fun showCreatePlaylistDialog() {
        showPlaylistNameDialog(R.string.create_playlist) { name ->
            viewModel.createPlaylist(name)
        }
    }

    private fun showRenameDialog(playlist: Playlist) {
        showPlaylistNameDialog(R.string.edit_playlist, playlist.name) { name ->
            viewModel.renamePlaylist(playlist.id, name)
        }
    }

    private fun showDeleteDialog(playlist: Playlist) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_playlist)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deletePlaylist(playlist.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
