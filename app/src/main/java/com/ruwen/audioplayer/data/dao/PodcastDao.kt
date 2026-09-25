package com.ruwen.audioplayer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ruwen.audioplayer.data.entity.DownloadStatus
import com.ruwen.audioplayer.data.entity.Episode
import com.ruwen.audioplayer.data.entity.Podcast

@Dao
interface PodcastDao {

    // ------------------------------------------------------------------
    //  播客
    // ------------------------------------------------------------------

    @Query("SELECT * FROM podcasts ORDER BY addedAt DESC")
    fun observePodcasts(): LiveData<List<Podcast>>

    @Query("SELECT * FROM podcasts ORDER BY addedAt DESC")
    suspend fun getPodcasts(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getPodcastById(id: Long): Podcast?

    /** 订阅前判重：同一个 RSS 地址只允许订阅一次 */
    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getPodcastByFeedUrl(feedUrl: String): Podcast?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcast(podcast: Podcast): Long

    @Delete
    suspend fun deletePodcast(podcast: Podcast)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcastById(id: Long)

    @Query("UPDATE podcasts SET lastRefreshedAt = :time WHERE id = :id")
    suspend fun updateRefreshedAt(id: Long, time: Long)

    /**
     * 保存该播客的单集排序（每个播客各一套）。
     * Room 会把 Boolean 落成 INTEGER（0/1），与 migration 里定义的列类型一致。
     */
    @Query("UPDATE podcasts SET sortByTitle = :byTitle, sortAscending = :ascending WHERE id = :id")
    suspend fun updateSortOrder(id: Long, byTitle: Boolean, ascending: Boolean)

    // ------------------------------------------------------------------
    //  单集
    // ------------------------------------------------------------------

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    fun observeEpisodes(podcastId: Long): LiveData<List<Episode>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    suspend fun getEpisodes(podcastId: Long): List<Episode>

    /**
     * 按本地音频路径反查单集（封面补下载用）。
     * v11 之前导入的条目没记 [AudioItem.coverSourceUrl]，只能靠
     * `audio_items.filePath == episodes.localPath` 找回远程封面地址。
     */
    @Query("SELECT * FROM episodes WHERE localPath IN (:paths)")
    suspend fun getEpisodesByLocalPaths(paths: List<String>): List<Episode>

    /**
     * 只改单集的封面一列（刷新时修正早期版本存进去的坏值）。
     *
     * **不能**用 `@Update` 整行写入：那会把 `localPath` / `downloadStatus` 一起覆盖，
     * 表现为「刷新一下下载就没了」（`saveNewEpisodes` 之所以用 IGNORE 也是这个原因）。
     */
    @Query(
        "UPDATE episodes SET imageUrl = :imageUrl " +
            "WHERE podcastId = :podcastId AND guid = :guid"
    )
    suspend fun updateEpisodeImageUrl(podcastId: Long, guid: String, imageUrl: String?)

    @Query("SELECT * FROM podcasts WHERE id IN (:ids)")
    suspend fun getPodcastsByIds(ids: List<Long>): List<Podcast>

    /** 已下载的单集：播放列表"从播客添加"时列出这些供勾选 */
    @Query(
        "SELECT * FROM episodes WHERE podcastId = :podcastId " +
            "AND downloadStatus = :status ORDER BY pubDate DESC"
    )
    suspend fun getDownloadedEpisodes(
        podcastId: Long,
        status: DownloadStatus = DownloadStatus.DOWNLOADED
    ): List<Episode>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisodeById(id: Long): Episode?

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun getEpisodesByIds(ids: List<Long>): List<Episode>

    /**
     * 刷新 RSS 后写入新单集。
     *
     * 必须用 IGNORE 而不是 REPLACE：REPLACE 会整行覆盖，把已下载单集的
     * localPath / downloadStatus / downloadedAt 一起清掉，导致"刷新一下下载就没了"。
     * IGNORE 下已存在（podcastId+guid 命中唯一索引）的行会被跳过，下载状态得以保留。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(episodes: List<Episode>): List<Long>

    @Update
    suspend fun updateEpisode(episode: Episode)

    /** 只更新下载相关三列，避免用整个实体覆盖时误伤其他字段 */
    @Query(
        "UPDATE episodes SET downloadStatus = :status, localPath = :path, " +
            "downloadedAt = :time WHERE id = :id"
    )
    suspend fun updateDownloadState(
        id: Long,
        status: DownloadStatus,
        path: String?,
        time: Long
    )

    @Query("SELECT * FROM episodes WHERE downloadStatus = :status")
    suspend fun getEpisodesByDownloadStatus(status: DownloadStatus): List<Episode>

    /** 取消订阅时级联清理（外键已配 CASCADE，这里供显式调用） */
    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteEpisodesOfPodcast(podcastId: Long)

    @Query("DELETE FROM episodes WHERE id IN (:ids)")
    suspend fun deleteEpisodesByIds(ids: List<Long>)
}
