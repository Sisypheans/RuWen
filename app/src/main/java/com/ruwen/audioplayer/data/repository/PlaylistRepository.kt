package com.ruwen.audioplayer.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.ruwen.audioplayer.data.dao.AudioItemDao
import com.ruwen.audioplayer.data.dao.PlaylistDao
import com.ruwen.audioplayer.data.db.TransactionRunner
import com.ruwen.audioplayer.data.entity.AudioImport
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.Playlist
import com.ruwen.audioplayer.data.entity.SubtitleStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val audioItemDao: AudioItemDao,
    /** 多步写库的原子化执行器（见 TransactionRunner 注释） */
    private val tx: TransactionRunner
) {

    fun getAllPlaylists(): LiveData<List<Playlist>> = playlistDao.getAllPlaylists()

    /**
     * 各播放列表的音频条数（列表项展示「N 首」），key = playlistId。
     *
     * Room 的失效回调是**按表**触发的：`audio_items` 有任何写入，这个查询就会重跑并下发一次。
     * 这里用 Flow + `distinctUntilChanged()` 做一层去重——**结果没变就不下发**，
     * 否则「数据库里其实什么都没变，UI 却反复刷新」（这正是之前列表点击被吞的土壤）。
     */
    fun getAudioCounts(): LiveData<Map<Long, Int>> =
        playlistDao.getAudioCountsFlow()
            .map { rows -> rows.associate { it.playlistId to it.count } }
            .distinctUntilChanged()
            .asLiveData()

    fun getAudioItemsByPlaylist(playlistId: Long): LiveData<List<AudioItem>> =
        audioItemDao.getAudioItemsByPlaylist(playlistId)

    suspend fun getAudioItemsByPlaylistSync(playlistId: Long): List<AudioItem> =
        audioItemDao.getAudioItemsByPlaylistSync(playlistId)

    suspend fun createPlaylist(name: String): Long {
        val playlist = Playlist(name = name)
        return playlistDao.insertPlaylist(playlist)
    }

    suspend fun getPlaylistById(id: Long): Playlist? = playlistDao.getPlaylistById(id)

    suspend fun updatePlaylistName(id: Long, name: String) {
        val playlist = playlistDao.getPlaylistById(id) ?: return
        playlistDao.updatePlaylist(
            playlist.copy(name = name, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun deletePlaylist(id: Long) {
        playlistDao.deletePlaylistById(id)
    }

    suspend fun addAudioItem(playlistId: Long, title: String, filePath: String, duration: Long, fileSize: Long): Long {
        val position = audioItemDao.getNextPosition(playlistId)
        val audioItem = AudioItem(
            playlistId = playlistId,
            title = title,
            filePath = filePath,
            duration = duration,
            fileSize = fileSize,
            position = position
        )
        val id = audioItemDao.insertAudioItem(audioItem)
        updatePlaylistTimestamp(playlistId)
        return id
    }

    /**
     * 批量导入音频。
     *
     * **已存在则覆盖**：同一播放列表里已经有同一个文件（按 filePath 判定）时，
     * 只更新它的展示信息（标题/封面/时长/大小），**不动** id、position 与字幕状态。
     * 这样用户重新添加一次旧音频，就能把它刷新成「封面 + 内嵌标题」的形式，
     * 已生成的字幕也不会丢。
     *
     * 覆盖用 `updateDisplayInfo` 而不是 `@Update` 整行写入：整行写入会连
     * subtitlePath / subtitleStatus 一起覆盖，字幕就白了。
     */
    suspend fun addAudioItems(playlistId: Long, items: List<AudioImport>) {
        tx.runInTransaction {
            var position = audioItemDao.getNextPosition(playlistId)
            val newItems = mutableListOf<AudioItem>()
            // 本批次内也要按 filePath 去重：之前只与数据库比对，
            // 同一批里出现相同路径（重复提交/来源重复）会插入多行指向同一个文件的记录。
            val seenPaths = HashSet<String>()

            for (import in items) {
                val existing = audioItemDao.findByFilePath(playlistId, import.uri)
                if (existing != null) {
                    audioItemDao.updateDisplayInfo(
                        id = existing.id,
                        displayTitle = import.displayTitle,
                        coverPath = import.coverPath,
                        duration = import.duration,
                        fileSize = import.fileSize
                    )
                    continue
                }
                if (!seenPaths.add(import.uri)) continue
                // 字幕共享：同一音频（名称+时长+文件大小）若在其他播放列表里已生成过字幕，
                // 新导入的这条直接继承，不必再跑一次 Whisper 识别。
                val shared =
                    audioItemDao.findSiblingWithSubtitle(import.title, import.duration, import.fileSize)
                newItems += AudioItem(
                    playlistId = playlistId,
                    title = import.title,
                    filePath = import.uri,
                    duration = import.duration,
                    fileSize = import.fileSize,
                    position = position++,
                    displayTitle = import.displayTitle,
                    coverPath = import.coverPath,
                    coverSourceUrl = import.coverSourceUrl,
                    subtitlePath = shared?.subtitlePath,
                    subtitleStatus = if (shared != null) {
                        SubtitleStatus.GENERATED
                    } else {
                        SubtitleStatus.NOT_GENERATED
                    }
                )
            }

            if (newItems.isNotEmpty()) audioItemDao.insertAudioItems(newItems)
            updatePlaylistTimestamp(playlistId)
            updatePlaylistCoverIfDefault(playlistId)
        }
    }

    /**
     * 列表还是默认封面时，用**列表第一个音频**的封面补上（用户要求）。
     * 第一个音频没有封面（coverPath 为 null）就保持默认，不往后找。
     */
    private suspend fun updatePlaylistCoverIfDefault(playlistId: Long) {
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        if (!playlist.coverUri.isNullOrBlank()) return
        val firstCover = audioItemDao.getFirstAudioItemByPlaylist(playlistId)?.coverPath ?: return
        playlistDao.updatePlaylist(playlist.copy(coverUri = firstCover))
    }

    /** 长按拖动排序后按新顺序写回播放列表的 sortPosition（0..n-1） */
    suspend fun updatePlaylistOrder(orderedIds: List<Long>) {
        tx.runInTransaction {
            orderedIds.forEachIndexed { index, id ->
                playlistDao.updateSortPosition(id, index)
            }
        }
    }

    /** 读取全部播放列表（同步，孤儿清理用） */
    suspend fun getAllPlaylistsSync(): List<Playlist> = playlistDao.getAllPlaylistsSync()

    suspend fun deleteAudioItem(id: Long) {
        val item = audioItemDao.getAudioItemById(id)
        audioItemDao.deleteAudioItemById(id)
        item?.let { updatePlaylistTimestamp(it.playlistId) }
    }

    suspend fun deleteAudioItems(playlistId: Long, ids: List<Long>) {
        audioItemDao.deleteAudioItemsByIds(playlistId, ids)
        updatePlaylistTimestamp(playlistId)
    }

    /**
     * 播客侧删除音频（删除单集 / 取消订阅）时，把此前「从播客添加」引入播放列表的
     * 同一音频一并移除，并回收它的字幕文件与封面文件。
     *
     * 按 filePath 匹配而非 id：同一集可能被加进多个播放列表（多行），必须全删。
     *
     * @return 已无其他条目引用、可以安全删除的文件路径（字幕 + 封面）
     */
    suspend fun removeAudioByFilePath(filePath: String): List<String> = tx.runInTransaction {
        val subtitlePaths = audioItemDao.getSubtitlePathsByFilePath(filePath)
        val coverPaths = audioItemDao.getCoverPathsByFilePath(filePath)
        audioItemDao.deleteAudioItemsByFilePath(filePath)
        // 字幕与封面都是共享资产：只有确认再无条目引用时才允许删文件
        val orphans = mutableListOf<String>()
        subtitlePaths.filterTo(orphans) { audioItemDao.countAudioItemsWithSubtitle(it) == 0 }
        coverPaths.filterTo(orphans) { audioItemDao.countAudioItemsWithCover(it) == 0 }
        orphans
    }

    suspend fun updateSubtitlePath(audioId: Long, subtitlePath: String) {
        audioItemDao.updateSubtitleStatus(audioId, subtitlePath, SubtitleStatus.GENERATED.ordinal)
    }

    suspend fun setSubtitleGenerating(audioId: Long) {
        audioItemDao.updateSubtitleState(audioId, SubtitleStatus.GENERATING.ordinal)
    }

    /** 入队成功但尚未被 Worker 取出处理时，标记为「队列中」 */
    suspend fun setSubtitleQueued(audioId: Long) {
        audioItemDao.updateSubtitleState(audioId, SubtitleStatus.QUEUED.ordinal)
    }

    suspend fun setSubtitleFailed(audioId: Long, error: String? = null) {
        audioItemDao.setSubtitleFailed(audioId, SubtitleStatus.FAILED.ordinal, error)
    }

    // ------------------------------------------------------------------
    //  字幕共享版：以下按「名称+时长+文件大小」作用于该音频的**所有**条目
    // （同一个音频导入到多个播放列表会存在多行），而不是只改传入的那一条。
    //  中间状态（排队/生成中/失败）一并同步，这样其他列表里的同名音频也会显示
    //  相同的状态，既能保持一致观感，也避免用户在别处重复发起同一次识别。
    // ------------------------------------------------------------------

    suspend fun setSubtitleQueuedShared(item: AudioItem) {
        audioItemDao.updateSubtitleStateByIdentity(
            item.title, item.duration, item.fileSize,
            SubtitleStatus.QUEUED.ordinal
        )
    }

    suspend fun setSubtitleGeneratingShared(item: AudioItem) {
        audioItemDao.updateSubtitleStateByIdentity(
            item.title, item.duration, item.fileSize,
            SubtitleStatus.GENERATING.ordinal
        )
    }

    suspend fun updateSubtitlePathShared(item: AudioItem, subtitlePath: String) {
        audioItemDao.updateSubtitleStatusByIdentity(
            item.title, item.duration, item.fileSize,
            subtitlePath, SubtitleStatus.GENERATED.ordinal
        )
    }

    suspend fun setSubtitleFailedShared(item: AudioItem, error: String? = null) {
        audioItemDao.setSubtitleFailedByIdentity(
            item.title, item.duration, item.fileSize,
            SubtitleStatus.FAILED.ordinal, error
        )
    }

    /**
     * 取消生成后复位状态。**取消不算失败**，所以不置 FAILED、不留错误文案：
     * - 该音频此前已有字幕（「重新生成 → 取消」）→ 恢复为 [SubtitleStatus.GENERATED]，旧字幕继续可用；
     * - 从未生成过 → 回到 [SubtitleStatus.NOT_GENERATED]，用户可重新发起。
     */
    suspend fun resetSubtitleAfterCancel(audioId: Long) {
        val item = audioItemDao.getAudioItemById(audioId) ?: return
        val restored = if (!item.subtitlePath.isNullOrEmpty()) {
            SubtitleStatus.GENERATED.ordinal
        } else {
            SubtitleStatus.NOT_GENERATED.ordinal
        }
        audioItemDao.resetSubtitleAfterCancel(audioId, restored)
    }

    /**
     * 按传入顺序重排音频（长按拖动排序后调用）：position 从 0 递增写回。
     * 列表读取是 `ORDER BY position ASC`，所以写完再观察 LiveData 就是新顺序。
     */
    suspend fun updateAudioOrder(orderedIds: List<Long>) {
        tx.runInTransaction {
            orderedIds.forEachIndexed { index, id ->
                audioItemDao.updatePosition(id, index)
            }
        }
    }

    fun observeAudioItem(id: Long): LiveData<AudioItem?> =
        audioItemDao.observeAudioItemById(id)

    suspend fun getAudioItemById(id: Long): AudioItem? = audioItemDao.getAudioItemById(id)

    private suspend fun updatePlaylistTimestamp(playlistId: Long) {
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
    }
}
