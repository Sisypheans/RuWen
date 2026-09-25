package com.ruwen.audioplayer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ruwen.audioplayer.data.entity.AudioItem

@Dao
interface AudioItemDao {

    @Query("SELECT * FROM audio_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getAudioItemsByPlaylist(playlistId: Long): LiveData<List<AudioItem>>

    @Query("SELECT * FROM audio_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getAudioItemsByPlaylistSync(playlistId: Long): List<AudioItem>

    @Query("SELECT * FROM audio_items WHERE id = :id")
    suspend fun getAudioItemById(id: Long): AudioItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioItem(audioItem: AudioItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioItems(audioItems: List<AudioItem>): List<Long>

    @Update
    suspend fun updateAudioItem(audioItem: AudioItem)

    @Delete
    suspend fun deleteAudioItem(audioItem: AudioItem)

    @Query("DELETE FROM audio_items WHERE id = :id")
    suspend fun deleteAudioItemById(id: Long)

    @Query("DELETE FROM audio_items WHERE playlistId = :playlistId AND id IN (:ids)")
    suspend fun deleteAudioItemsByIds(playlistId: Long, ids: List<Long>)

    // ------------------------------------------------------------------
    //  展示标题 / 封面（DB v7 起）
    // ------------------------------------------------------------------

    /** 同一播放列表里是否已经存在这个音频文件（用于「已存在则覆盖」） */
    @Query("SELECT * FROM audio_items WHERE playlistId = :playlistId AND filePath = :filePath LIMIT 1")
    suspend fun findByFilePath(playlistId: Long, filePath: String): AudioItem?

    /**
     * 只更新展示相关的四列，不动 title / subtitlePath / position 等。
     * 覆盖导入必须这样写：用 @Update 整行覆盖会把字幕状态一起抹掉。
     */
    @Query(
        "UPDATE audio_items SET displayTitle = :displayTitle, coverPath = :coverPath, " +
            "duration = :duration, fileSize = :fileSize WHERE id = :id"
    )
    suspend fun updateDisplayInfo(
        id: Long,
        displayTitle: String?,
        coverPath: String?,
        duration: Long,
        fileSize: Long
    )

    /** 删除前取出该音频的封面文件（同一音频的多行共享一份，故 DISTINCT） */
    @Query(
        "SELECT DISTINCT coverPath FROM audio_items " +
            "WHERE filePath = :filePath AND coverPath IS NOT NULL AND coverPath != ''"
    )
    suspend fun getCoverPathsByFilePath(filePath: String): List<String>

    // ------------------------------------------------------------------
    //  封面补下载（CoverBackfill）：导入时没网 → 封面落盘失败 → 条目一直是占位图。
    //  事后按 [AudioItem.coverSourceUrl] 补回来，故需要「查缺失」与「按 filePath 写回」
    //  两个能力（同一音频多行共享一份封面，写回必须按 filePath 而不是按 id）。
    // ------------------------------------------------------------------

    /** 缺封面的条目（含同一音频在多个列表里的多行，调用方按 filePath 去重后再下载） */
    @Query(
        "SELECT * FROM audio_items " +
            "WHERE coverPath IS NULL OR coverPath = ''"
    )
    suspend fun getAudioItemsMissingCover(): List<AudioItem>

    @Query("UPDATE audio_items SET coverPath = :coverPath WHERE filePath = :filePath")
    suspend fun updateCoverPathByFilePath(filePath: String, coverPath: String?)

    /** 顺带把来源地址补上（历史条目在 v11 之前没有这一列，反查到之后回填，下次不必再查） */
    @Query("UPDATE audio_items SET coverSourceUrl = :url WHERE filePath = :filePath")
    suspend fun updateCoverSourceUrlByFilePath(filePath: String, url: String?)

    /**
     * 封面文件是否还被**别处**引用。
     * 同一音频加进多个播放列表时多行指向同一份封面，删文件前必须确认没有残留引用，
     * 否则别的列表里的条目会变成空图。
     */
    @Query("SELECT COUNT(*) FROM audio_items WHERE coverPath = :coverPath")
    suspend fun countAudioItemsWithCover(coverPath: String): Int

    // ------------------------------------------------------------------
    //  播客联动：播客侧删除单集 / 取消订阅时，要把此前「从播客添加」引入
    //  播放列表的同一音频一并移除，否则列表里会留下指向已删除文件的死条目。
    //  按 filePath 匹配而不是按 id：同一集可能被加进多个播放列表（多行），必须全删。
    // ------------------------------------------------------------------

    @Query("DELETE FROM audio_items WHERE filePath = :filePath")
    suspend fun deleteAudioItemsByFilePath(filePath: String)

    /** 删除前取出该音频关联的字幕文件（同一音频的多行共享一份，故 DISTINCT） */
    @Query(
        "SELECT DISTINCT subtitlePath FROM audio_items " +
            "WHERE filePath = :filePath AND subtitlePath IS NOT NULL AND subtitlePath != ''"
    )
    suspend fun getSubtitlePathsByFilePath(filePath: String): List<String>

    /**
     * 字幕文件是否被**别处**仍在引用。字幕按「名称+时长+文件大小」共享，
     * 不同 filePath 的条目也可能指向同一份字幕，因此删文件前必须先确认没有残留引用。
     */
    @Query("SELECT COUNT(*) FROM audio_items WHERE subtitlePath = :subtitlePath")
    suspend fun countAudioItemsWithSubtitle(subtitlePath: String): Int

    // ------------------------------------------------------------------
    //  字幕状态（DB v9 起只存状态与错误文案；进度在内存，见 SubtitleProgressStore）
    // ------------------------------------------------------------------

    @Query("UPDATE audio_items SET subtitlePath = :subtitlePath, subtitleStatus = :status, subtitleError = NULL WHERE id = :id")
    suspend fun updateSubtitleStatus(id: Long, subtitlePath: String?, status: Int)

    /** 只改状态（排队 / 生成中）：进度不落库，故没有 progress/eta 参数 */
    @Query("UPDATE audio_items SET subtitleStatus = :status, subtitleError = NULL WHERE id = :id")
    suspend fun updateSubtitleState(id: Long, status: Int)

    @Query("UPDATE audio_items SET subtitleStatus = :status, subtitleError = :error WHERE id = :id")
    suspend fun setSubtitleFailed(id: Long, status: Int, error: String?)

    /**
     * 取消生成后复位状态：清掉错误。
     * 刻意**不动 subtitlePath** —— 若是「重新生成时取消」，旧字幕不应被丢弃，
     * 由调用方根据 path 是否为空决定恢复成 GENERATED 还是 NOT_GENERATED。
     */
    @Query("UPDATE audio_items SET subtitleStatus = :status, subtitleError = NULL WHERE id = :id")
    suspend fun resetSubtitleAfterCancel(id: Long, status: Int)

    @Query("SELECT * FROM audio_items WHERE id = :id")
    fun observeAudioItemById(id: Long): LiveData<AudioItem?>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM audio_items WHERE playlistId = :playlistId")
    suspend fun getNextPosition(playlistId: Long): Int

    /** 列表里排在最前面的那条音频（用于「列表封面 = 第一个音频的封面」） */
    @Query("SELECT * FROM audio_items WHERE playlistId = :playlistId ORDER BY position ASC LIMIT 1")
    suspend fun getFirstAudioItemByPlaylist(playlistId: Long): AudioItem?

    // ------------------------------------------------------------------
    //  孤儿文件清理：取出仍被引用的全部封面/字幕路径
    // ------------------------------------------------------------------

    @Query("SELECT DISTINCT coverPath FROM audio_items WHERE coverPath IS NOT NULL AND coverPath != ''")
    suspend fun getAllCoverPaths(): List<String>

    @Query("SELECT DISTINCT subtitlePath FROM audio_items WHERE subtitlePath IS NOT NULL AND subtitlePath != ''")
    suspend fun getAllSubtitlePaths(): List<String>

    @Query("SELECT COUNT(*) FROM audio_items WHERE playlistId = :playlistId")
    suspend fun getAudioItemCount(playlistId: Long): Int

    /** 更新单条的排序位置（长按拖动排序后按新顺序逐条写回） */
    @Query("UPDATE audio_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    // ------------------------------------------------------------------
    //  字幕共享：以下按「名称 + 时长 + 文件大小」而非 id 更新。
    //  同一个音频导入到多个播放列表会有多行记录（id 不同但标识相同），
    //  字幕属于「音频」而非「某一条列表记录」，故这些操作必须作用于所有同标识的行。
    // ------------------------------------------------------------------

    @Query(
        "UPDATE audio_items SET subtitlePath = :subtitlePath, subtitleStatus = :status, " +
            "subtitleError = NULL " +
            "WHERE title = :title AND duration = :duration AND fileSize = :fileSize"
    )
    suspend fun updateSubtitleStatusByIdentity(
        title: String,
        duration: Long,
        fileSize: Long,
        subtitlePath: String?,
        status: Int
    )

    @Query(
        "UPDATE audio_items SET subtitleStatus = :status, subtitleError = NULL " +
            "WHERE title = :title AND duration = :duration AND fileSize = :fileSize"
    )
    suspend fun updateSubtitleStateByIdentity(
        title: String,
        duration: Long,
        fileSize: Long,
        status: Int
    )

    @Query(
        "UPDATE audio_items SET subtitleStatus = :status, subtitleError = :error " +
            "WHERE title = :title AND duration = :duration AND fileSize = :fileSize"
    )
    suspend fun setSubtitleFailedByIdentity(
        title: String,
        duration: Long,
        fileSize: Long,
        status: Int,
        error: String?
    )

    @Query(
        "UPDATE audio_items SET subtitleStatus = :status, subtitleError = NULL " +
            "WHERE title = :title AND duration = :duration AND fileSize = :fileSize"
    )
    suspend fun resetSubtitleAfterCancelByIdentity(
        title: String,
        duration: Long,
        fileSize: Long,
        status: Int
    )

    /**
     * 找出同一音频（同标识）中「已经生成过字幕」的那一行，供新导入的条目继承字幕。
     * 只要任意一份已生成即可，多行内容一致（它们共享同一个字幕文件）。
     */
    @Query(
        "SELECT * FROM audio_items " +
            "WHERE title = :title AND duration = :duration AND fileSize = :fileSize " +
            "AND subtitlePath IS NOT NULL AND subtitlePath != '' LIMIT 1"
    )
    suspend fun findSiblingWithSubtitle(
        title: String,
        duration: Long,
        fileSize: Long
    ): AudioItem?
}
