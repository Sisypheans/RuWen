package com.ruwen.audioplayer.ui.search

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.remote.OpmlParser
import com.ruwen.audioplayer.data.remote.SearchSource
import com.ruwen.audioplayer.data.repository.PodcastRepository
import com.ruwen.audioplayer.databinding.FragmentSearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：在线搜索 + 订阅，不涉及本地内容搜索。
 *
 * 交互参照 AntennaPod：本页只负责「输入关键词 + 选择来源」，
 * 点来源（或回车走默认源 Apple Podcasts）后**跳转到独立的搜索结果页**展示与订阅。
 * 本页保留的两个添加入口：RSS 地址（弹窗输入）、OPML 导入（选文件后全部订阅）。
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val podcastRepository: PodcastRepository by lazy {
        RuWenApplication.getInstance().podcastRepository
    }

    /** 正在订阅的 RSS 地址，用于防重复点击 */
    private val pendingUrls = mutableSetOf<String>()

    private val opmlLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importOpml(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.searchEditText.setOnEditorActionListener { _, _, _ ->
            search(SearchSource.APPLE)
            true
        }

        binding.btnSearchApple.setOnClickListener { search(SearchSource.APPLE) }
        binding.btnSearchPodcastIndex.setOnClickListener { search(SearchSource.PODCAST_INDEX) }
        binding.btnAddByRss.setOnClickListener { showAddByRssDialog() }
        binding.btnImportOpml.setOnClickListener {
            // mime 用 */*：OPML 没有统一注册类型，各应用导出的后缀/mime 不一致
            runCatching { opmlLauncher.launch(arrayOf("*/*")) }
        }
    }

    // ------------------------------------------------------------------
    //  在线搜索：跳转独立结果页
    // ------------------------------------------------------------------

    private fun search(source: SearchSource) {
        val query = binding.searchEditText.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            toast(getString(R.string.enter_keyword))
            return
        }
        hideKeyboard()
        val intent = Intent(requireContext(), PodcastSearchResultActivity::class.java).apply {
            putExtra(PodcastSearchResultActivity.EXTRA_QUERY, query)
            putExtra(PodcastSearchResultActivity.EXTRA_SOURCE, source)
        }
        startActivity(intent)
    }

    private fun subscribeUrl(feedUrl: String) {
        if (feedUrl.isBlank()) {
            toast(getString(R.string.enter_keyword))
            return
        }
        if (feedUrl in pendingUrls) return
        pendingUrls += feedUrl

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 已订阅的直接提示，不重复拉取 RSS
                val existing = podcastRepository.getPodcastByFeedUrl(feedUrl)
                if (existing != null) {
                    if (isAdded) toast(getString(R.string.already_subscribed))
                    return@launch
                }
                // subscribe 内部已用 runCatching 包过，返回的就是 Result，不要再套一层
                // （套了会变成 Result<Result<Podcast>>，isSuccess 恒为 true，失败会被漏掉）
                podcastRepository.subscribe(feedUrl)
                    .onSuccess { podcast ->
                        toast(getString(R.string.subscribe_success, podcast.title))
                    }
                    .onFailure {
                        toast(getString(R.string.subscribe_failed, it.message.orEmpty()))
                    }
            } finally {
                // finally 而非成功/失败各写一遍：异常路径也能恢复防重标记
                pendingUrls -= feedUrl
            }
        }
    }
    private fun showAddByRssDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rss_url_hint)
            setSingleLine(true)
        }
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_by_rss)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                subscribeUrl(input.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    //  OPML 导入
    // ------------------------------------------------------------------

    /**
     * 解析出全部播客后**逐个订阅**（用户确认：不做勾选，全部订阅）。
     * 串行执行：并发订阅几十个源既会把源站打退，也会让失败原因难以定位。
     */
    private fun importOpml(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val feeds = runCatching {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use {
                        OpmlParser.parse(it)
                    }.orEmpty()
                }
            }.getOrElse {
                toast(getString(R.string.opml_read_failed, it.message.orEmpty()))
                return@launch
            }
            if (!isAdded) return@launch

            if (feeds.isEmpty()) {
                toast(getString(R.string.opml_empty))
                return@launch
            }

            // 结果区已移到独立页面，导入进度直接用 toast：开始报总数，结束报成败
            toast(getString(R.string.opml_importing, 1, feeds.size))
            var success = 0
            var failed = 0
            feeds.forEach { feed ->
                if (podcastRepository.subscribe(feed.xmlUrl).isSuccess) {
                    success++
                } else {
                    failed++
                }
            }
            toast(getString(R.string.opml_import_done, success, failed))
        }
    }

    // ------------------------------------------------------------------
    //  工具
    // ------------------------------------------------------------------

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>() ?: return
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
