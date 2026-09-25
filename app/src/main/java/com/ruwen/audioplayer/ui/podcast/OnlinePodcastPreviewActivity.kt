package com.ruwen.audioplayer.ui.podcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.repository.PodcastRepository
import com.ruwen.audioplayer.databinding.ActivityPodcastPreviewBinding
import com.ruwen.audioplayer.ui.util.LoadingOverlay
import kotlinx.coroutines.launch

/**
 * 在线播客预览页（点击搜索结果条目进入，**不会自动订阅**）。
 *
 * 参照 AntennaPod 的 OnlineFeedViewActivity：点搜索结果先进入一个预览界面，
 * 展示封面/标题/作者/简介/单集列表，由用户看过内容后**按底部「订阅」按钮**才真正订阅。
 *
 * 与 AntennaPod 的差异（说明用途）：AntennaPod 会把 feed 落库成「未订阅」态再展示，
 * 我们的库没有这个状态，因此这里只在内存里解析展示，点订阅时才走
 * [PodcastRepository.subscribe] 落库，避免引入一套额外的状态机。
 */
class OnlinePodcastPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPodcastPreviewBinding
    private lateinit var headerAdapter: PodcastPreviewHeaderAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private val podcastRepository by lazy { RuWenApplication.getInstance().podcastRepository }

    private var feedUrl: String = ""
    private var subscribing = false

    /** 加载遮罩：解析 RSS / 订阅期间挡住整页（原先用单个转圈，不挡交互） */
    private val loadingOverlay by lazy { LoadingOverlay.attach(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPodcastPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        feedUrl = intent.getStringExtra(EXTRA_FEED_URL).orEmpty()
        if (feedUrl.isBlank()) {
            finish()
            return
        }

        // 搜索结果里已有的信息先铺上，避免白屏；RSS 解析完成后再用解析结果覆盖
        headerAdapter = PodcastPreviewHeaderAdapter().apply {
            bind(
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                intent.getStringExtra(EXTRA_AUTHOR),
                intent.getStringExtra(EXTRA_DESCRIPTION),
                intent.getStringExtra(EXTRA_IMAGE_URL)
            )
        }
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        episodeAdapter = EpisodeAdapter(
            onItemClick = {},
            onLongClick = { false },
            onDownloadClick = {}
        ).apply { actionsEnabled = false }   // 预览态只展示单集，不提供下载

        binding.episodeRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.episodeRecyclerView.adapter = ConcatAdapter(headerAdapter, episodeAdapter)

        binding.btnSubscribe.setOnClickListener { subscribe() }

        loadPreview()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadPreview() {
        loadingOverlay.show()
        lifecycleScope.launch {
            val result = podcastRepository.previewFeed(feedUrl)
            loadingOverlay.hide()
            result.onSuccess { preview ->
                supportActionBar?.title = preview.title
                headerAdapter.bind(preview.title, preview.author, preview.description, preview.imageUrl)
                episodeAdapter.submitList(preview.episodes)
                if (preview.episodes.isEmpty()) {
                    toast(getString(R.string.preview_no_episodes))
                }
            }.onFailure {
                toast(getString(R.string.preview_failed, it.message.orEmpty()))
            }
        }
    }

    /** 只有这里会真正订阅（点条目只预览，不订阅） */
    private fun subscribe() {
        if (subscribing) return
        subscribing = true
        binding.btnSubscribe.isEnabled = false
        binding.btnSubscribe.text = getString(R.string.subscribing)
        loadingOverlay.show()

        lifecycleScope.launch {
            try {
                val existing = podcastRepository.getPodcastByFeedUrl(feedUrl)
                if (existing != null) {
                    toast(getString(R.string.already_subscribed))
                    return@launch
                }
                podcastRepository.subscribe(feedUrl)
                    .onSuccess { podcast ->
                        toast(getString(R.string.subscribe_success, podcast.title))
                        finish()
                    }
                    .onFailure {
                        toast(getString(R.string.subscribe_failed, it.message.orEmpty()))
                    }
            } finally {
                subscribing = false
                binding.btnSubscribe.isEnabled = true
                binding.btnSubscribe.text = getString(R.string.subscribe)
                // 订阅失败要留在页面重试；成功时已 finish()，hide 只是收尾
                loadingOverlay.hide()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_FEED_URL = "extra_feed_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_AUTHOR = "extra_author"
        private const val EXTRA_DESCRIPTION = "extra_description"
        private const val EXTRA_IMAGE_URL = "extra_image_url"

        fun start(
            context: Context,
            feedUrl: String,
            title: String,
            author: String?,
            description: String?,
            imageUrl: String?
        ) {
            val intent = Intent(context, OnlinePodcastPreviewActivity::class.java).apply {
                putExtra(EXTRA_FEED_URL, feedUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AUTHOR, author)
                putExtra(EXTRA_DESCRIPTION, description)
                putExtra(EXTRA_IMAGE_URL, imageUrl)
            }
            context.startActivity(intent)
        }
    }
}
