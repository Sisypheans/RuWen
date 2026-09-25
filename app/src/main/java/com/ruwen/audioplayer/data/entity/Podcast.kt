package com.ruwen.audioplayer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 已订阅的播客（对应一条 RSS 订阅源）。
 *
 * 与本地音频（[AudioItem]）完全解耦：播客模块只负责"订阅 / 浏览 / 下载音频文件"，
 * 不参与播放；下载完成的单集由用户在播放列表详情页通过"从播客添加"引入播放列表。
 */
@Entity(
    tableName = "podcasts",
    // 同一个 RSS 地址不允许重复订阅
    indices = [Index(value = ["feedUrl"], unique = true)]
)
data class Podcast(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String? = null,
    /** RSS <description>，可能是 HTML */
    val description: String? = null,
    /** 播客封面图 URL */
    val imageUrl: String? = null,
    /** RSS 订阅地址（唯一） */
    val feedUrl: String,
    /** 播客主页链接 */
    val link: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** 上次成功刷新 RSS 的时间戳；0 表示从未刷新过 */
    val lastRefreshedAt: Long = 0,

    /**
     * 单集排序：**每个播客各记一套**（用户选择后落库，退出页面/杀进程都保持）。
     *
     * 之前放全局 SharedPreferences，用户要求改成按播客独立保存，
     * 所以从 DB v10 起落在 podcasts 表上。默认 = 按发布日期倒序（最新在前）。
     */
    val sortByTitle: Boolean = false,
    val sortAscending: Boolean = false
)
