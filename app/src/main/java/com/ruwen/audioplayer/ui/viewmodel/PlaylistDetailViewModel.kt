package com.ruwen.audioplayer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.AudioImport
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.SubtitleStatus
import com.ruwen.audioplayer.data.entity.Playlist
import com.ruwen.audioplayer.data.repository.PlaylistRepository
import com.ruwen.audioplayer.whisper.SubtitleGenerationWorker
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PlaylistRepository = (application as RuWenApplication).playlistRepository

    private val _playlist = MutableLiveData<Playlist?>()
    val playlist: LiveData<Playlist?> = _playlist

    private var audioItemsLiveData: LiveData<List<AudioItem>>? = null

    fun getAudioItems(playlistId: Long): LiveData<List<AudioItem>> {
        if (audioItemsLiveData == null) {
            audioItemsLiveData = repository.getAudioItemsByPlaylist(playlistId)
        }
        return audioItemsLiveData!!
    }

    fun loadPlaylist(playlistId: Long) {
        viewModelScope.launch {
            _playlist.value = repository.getPlaylistById(playlistId)
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            repository.updatePlaylistName(playlistId, name)
            loadPlaylist(playlistId)
        }
    }

    fun deleteAudioItem(audioId: Long) {
        viewModelScope.launch {
            repository.deleteAudioItem(audioId)
        }
    }

    fun deleteAudioItems(playlistId: Long, ids: List<Long>) {
        viewModelScope.launch {
            repository.deleteAudioItems(playlistId, ids)
        }
    }

    fun generateSubtitle(audioId: Long) {
        viewModelScope.launch {
            val audioItem = repository.getAudioItemById(audioId) ?: return@launch
            // 生成中 / 队列中不再重复触发，避免多份 whisper 同时跑、叠加内存压力
            if (audioItem.subtitleStatus == SubtitleStatus.GENERATING ||
                audioItem.subtitleStatus == SubtitleStatus.QUEUED) return@launch
            // 先置「队列中」，让用户立刻在列表里看到排队状态；Worker 取出后再翻成「生成中」。
            // 用共享版：同一个音频在其他播放列表里的条目也应显示排队中，保持一致。
            repository.setSubtitleQueuedShared(audioItem)
            SubtitleGenerationWorker.startGeneration(
                getApplication(),
                audioId,
                audioItem.filePath
            )
        }
    }

    /** 取消指定音频的字幕生成（会触发 native 侧 abort_callback 并释放模型内存） */
    fun cancelSubtitle(audioId: Long) {
        SubtitleGenerationWorker.cancelGeneration(getApplication(), audioId)
    }

    fun addAudioItems(playlistId: Long, items: List<AudioImport>) {
        viewModelScope.launch {
            repository.addAudioItems(playlistId, items)
        }
    }

    /** 长按拖动排序结束后，把新顺序（音频 id 按显示顺序）写回数据库 */
    fun updateAudioOrder(orderedIds: List<Long>) {
        viewModelScope.launch {
            repository.updateAudioOrder(orderedIds)
        }
    }
}
