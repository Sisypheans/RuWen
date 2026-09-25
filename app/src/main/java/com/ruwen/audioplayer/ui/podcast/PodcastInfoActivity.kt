package com.ruwen.audioplayer.ui.podcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.databinding.ActivityPodcastInfoBinding
import com.ruwen.audioplayer.util.loadRemoteCover
import kotlinx.coroutines.launch

/**
 * 播客详情信息页（「详情」按钮打开）。
 *
 * 参照 AntennaPod 的 FeedInfoFragment：独立页面、可滚动，展示节目统计、RSS 地址、
 * 站点与节目简介。
 *
 * **节目简介按 HTML 渲染**：RSS 里的 description 通常是 HTML（`<p>` `<br>` `<a>` 等），
 * 直接 setText 会把标签原样显示。这里用 Android 官方 `Html.fromHtml` 转成富文本，
 * 并开启 LinkMovementMethod 让链接可点（AntennaPod 自己也维护了一个 HtmlToPlainText
 * 把 HTML 降级成纯文本；我们直接用框架 API，避免再引一份实现）。
 */
class PodcastInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPodcastInfoBinding

    private val podcastRepository by lazy { RuWenApplication.getInstance().podcastRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPodcastInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val podcastId = intent.getLongExtra(EXTRA_PODCAST_ID, -1L)
        val episodeCount = intent.getIntExtra(EXTRA_EPISODE_COUNT, 0)
        val downloadedCount = intent.getIntExtra(EXTRA_DOWNLOADED_COUNT, 0)
        val totalSeconds = intent.getLongExtra(EXTRA_TOTAL_SECONDS, 0L)
        if (podcastId <= 0L) {
            finish()
            return
        }

        binding.tvStatistics.text = buildString {
            append(getString(R.string.podcast_stats_episodes, episodeCount))
            append('\n')
            append(
                getString(
                    R.string.podcast_stats_duration,
                    totalSeconds / 3600,
                    (totalSeconds % 3600) / 60
                )
            )
            append('\n')
            append(getString(R.string.podcast_stats_downloaded, downloadedCount))
        }

        lifecycleScope.launch {
            val podcast = podcastRepository.getPodcastById(podcastId)
            if (podcast == null) {
                finish()
                return@launch
            }
            bind(podcast)
        }
    }

    private fun bind(podcast: Podcast) {
        supportActionBar?.title = podcast.title
        binding.tvTitle.text = podcast.title
        binding.tvAuthor.text = podcast.author.orEmpty()
        binding.tvAuthor.visibility = if (podcast.author.isNullOrBlank()) View.GONE else View.VISIBLE
        // 空 url 时会主动 dispose + 占位图：不这么干会残留上一张封面
        binding.ivCover.loadRemoteCover(podcast.imageUrl, R.drawable.ic_playlist_empty)

        binding.tvFeedUrl.text = podcast.feedUrl
        binding.tvWebsite.text = podcast.link?.takeIf { it.isNotBlank() }
            ?: getString(R.string.podcast_info_absent)

        val description = podcast.description.orEmpty()
        binding.tvDescription.movementMethod = LinkMovementMethod.getInstance()
        binding.tvDescription.text = if (description.isBlank()) {
            getString(R.string.podcast_info_no_description)
        } else {
            // 兼容 HTML 与纯文本：fromHtml 对纯文本也安全（标签外的文字原样保留）
            Html.fromHtml(description, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
    }

    companion object {
        private const val EXTRA_PODCAST_ID = "extra_podcast_id"
        private const val EXTRA_EPISODE_COUNT = "extra_episode_count"
        private const val EXTRA_DOWNLOADED_COUNT = "extra_downloaded_count"
        private const val EXTRA_TOTAL_SECONDS = "extra_total_seconds"

        fun start(
            context: Context,
            podcast: Podcast,
            episodeCount: Int,
            downloadedCount: Int,
            totalSeconds: Long
        ) {
            val intent = Intent(context, PodcastInfoActivity::class.java).apply {
                putExtra(EXTRA_PODCAST_ID, podcast.id)
                putExtra(EXTRA_EPISODE_COUNT, episodeCount)
                putExtra(EXTRA_DOWNLOADED_COUNT, downloadedCount)
                putExtra(EXTRA_TOTAL_SECONDS, totalSeconds)
            }
            context.startActivity(intent)
        }
    }
}
