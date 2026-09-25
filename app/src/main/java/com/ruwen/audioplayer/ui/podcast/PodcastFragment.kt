package com.ruwen.audioplayer.ui.podcast

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.databinding.FragmentPodcastBinding

/**
 * 播客页：展示所有已订阅播客，封面按 3 列网格排列。
 *
 * 本页只负责"订阅内容的浏览入口"，不参与播放：
 * 单集下载后在播放列表详情页通过"从播客添加"引入播放列表。
 */
class PodcastFragment : Fragment() {

    private var _binding: FragmentPodcastBinding? = null
    private val binding get() = _binding!!

    private val podcastRepository by lazy {
        RuWenApplication.getInstance().podcastRepository
    }

    private lateinit var podcastAdapter: PodcastAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPodcastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 3 列网格（播客封面按 3×N 展示）
        binding.podcastRecyclerView.layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT)

        podcastAdapter = PodcastAdapter { podcast -> openPodcastDetail(podcast) }
        binding.podcastRecyclerView.adapter = podcastAdapter

        podcastRepository.observePodcasts().observe(viewLifecycleOwner) { podcasts ->
            val list = podcasts.orEmpty()
            val isEmpty = list.isEmpty()
            binding.podcastEmptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.podcastRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            podcastAdapter.submitList(list)
        }
    }

    private fun openPodcastDetail(podcast: Podcast) {
        PodcastDetailActivity.start(requireActivity(), podcast.id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val SPAN_COUNT = 3
    }
}
