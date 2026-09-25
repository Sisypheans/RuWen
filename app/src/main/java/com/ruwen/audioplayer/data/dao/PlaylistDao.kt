package com.ruwen.audioplayer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ruwen.audioplayer.data.entity.Playlist
import kotlinx.coroutines.flow.Flow

/** 播放列表的音频计数行（首页列表项展示「N 首」用） */
data class PlaylistAudioCount(
    val playlistId: Long,
    val count: Int
)

@Dao
interface PlaylistDao {

    /** 默认按 sortPosition ASC, id ASC = 创建顺序；长按拖动后按用户顺序（sortPosition 已重写） */
    @Query("SELECT * FROM playlists ORDER BY sortPosition ASC, id ASC")
    fun getAllPlaylists(): LiveData<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): Playlist?

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylistsSync(): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: Long)

    /** 长按拖动排序后按新顺序逐条写回排序位 */
    @Query("UPDATE playlists SET sortPosition = :position WHERE id = :id")
    suspend fun updateSortPosition(id: Long, position: Int)

    /**
     * 各播放列表的音频条数（列表项展示「N 首」）。
     *
     * 返回 **Flow** 而不是 LiveData：Flow 可以在上层接 `distinctUntilChanged()`，
     * 让「查出来的结果其实没变」的失效回调不再下发到 UI；
     * LiveData 每次失效都会通知观察者，没有这一层去重。
     */
    @Query("SELECT playlistId AS playlistId, COUNT(id) AS count FROM audio_items GROUP BY playlistId")
    fun getAudioCountsFlow(): Flow<List<PlaylistAudioCount>>

    /** 孤儿清理用：所有仍被播放列表引用的封面文件路径 */
    @Query("SELECT DISTINCT coverUri FROM playlists WHERE coverUri IS NOT NULL AND coverUri != ''")
    suspend fun getAllCoverUris(): List<String>
}
