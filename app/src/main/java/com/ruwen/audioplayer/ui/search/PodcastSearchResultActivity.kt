package com.ruwen.audioplayer.ui.search

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.remote.PodcastSearchResult
import com.ruwen.audioplayer.data.remote.SearchSource
import com.ruwen.audioplayer.data.repository.PodcastSearchRepository
import com.ruwen.audioplayer.databinding.ActivityPodcastSearchResultBinding
import com.ruwen.audioplayer.ui.podcast.OnlinePodcastPreviewActivity
import com.ruwen.audioplayer.ui.util.LoadingOverlay
import kotlinx.coroutines.launch

/**
 * 搜索结果页（参照 AntennaPod：搜索页只负责输入与选来源，结果在这里展示）。
 *
 * 进入即按传入的「关键词 + 来源」发起一次搜索；点击结果打开预览页，
 * 订阅统一在预览页进行（列表项不放订阅按钮，避免还没看清是什么就订阅）。
 */
class PodcastSearchResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_SOURCE = "extra_source"
    }

    private lateinit var binding: ActivityPodcastSearchResultBinding
    private lateinit var resultAdapter: SearchResultAdapter

    private val searchRepository: PodcastSearchRepository by lazy {
        RuWenApplication.getInstance().podcastSearchRepository
    }

    /** 加载遮罩：搜索期间挡住整页（原先用单个转圈，不挡交互） */
    private val loadingOverlay by lazy { LoadingOverlay.attach(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPodcastSearchResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val source = intent.getSerializableExtra(EXTRA_SOURCE) as? SearchSource ?: SearchSource.APPLE
        title = query

        resultAdapter = SearchResultAdapter(
            onItemClick = { result -> openPreview(result) }
        )
        binding.resultRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultRecyclerView.adapter = resultAdapter

        search(query, source)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun search(query: String, source: SearchSource) {
        // 搜索是网络请求；遮罩期间点屏幕 / 返回键都不响应，避免重复发起
        loadingOverlay.show()
        hideHint()
        lifecycleScope.launch {
            val result = runCatching { searchRepository.search(source, query) }
            loadingOverlay.hide()
            result.onSuccess { list ->
                resultAdapter.submitList(list)
                if (list.isEmpty()) {
                    showHint(getString(R.string.search_empty))
                }
            }.onFailure {
                showHint(getString(R.string.search_failed, it.message.orEmpty()))
            }
        }
    }

    private fun showHint(text: String) {
        binding.searchHint.text = text
        binding.searchHint.visibility = View.VISIBLE
    }

    private fun hideHint() {
        binding.searchHint.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    //  预览（点条目）
    // ------------------------------------------------------------------

    /** 点条目 = 先看内容：打开在线预览页，不订阅 */
    private fun openPreview(result: PodcastSearchResult) {
        if (result.feedUrl.isBlank()) return
        OnlinePodcastPreviewActivity.start(
            this,
            feedUrl = result.feedUrl,
            title = result.title,
            author = result.author,
            description = result.description,
            imageUrl = result.imageUrl
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
