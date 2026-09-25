package com.ruwen.audioplayer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 列表封面文件路径。
     * null/空 = 默认封面（光盘占位图）；添加音频后若仍为默认，会用列表第一个音频的封面补上。
     * 注意：该列自 v1 就存在但一直未被使用，现在复用它存**本地封面文件路径**。
     */
    val coverUri: String? = null,
    /**
     * 手动排序位。默认 0 = 未拖动过，列表按 `sortPosition ASC, id ASC` 展示，
     * 即**创建顺序**；长按拖动后会按新顺序把 0..n-1 重写一遍。
     */
    val sortPosition: Int = 0
)

