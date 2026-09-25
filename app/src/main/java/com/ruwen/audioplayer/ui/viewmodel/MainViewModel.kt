package com.ruwen.audioplayer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.Playlist
import com.ruwen.audioplayer.data.repository.PlaylistRepository
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PlaylistRepository = (application as RuWenApplication).playlistRepository

    val playlists: LiveData<List<Playlist>> = repository.getAllPlaylists()

    /** 各播放列表的音频条数（列表项展示「N 首」），key = playlistId */
    val audioCounts: LiveData<Map<Long, Int>> = repository.getAudioCounts()

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    /** 长按拖动排序落库：按新顺序写 sortPosition，落库后 LiveData 自动按新序推送 */
    fun updatePlaylistOrder(orderedIds: List<Long>) {
        viewModelScope.launch {
            repository.updatePlaylistOrder(orderedIds)
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch {
            repository.updatePlaylistName(id, name)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
        }
    }
}
