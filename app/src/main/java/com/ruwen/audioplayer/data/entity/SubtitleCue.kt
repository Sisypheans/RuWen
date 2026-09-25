package com.ruwen.audioplayer.data.entity

data class SubtitleCue(
    val index: Int,
    val startTime: Long, // milliseconds
    val endTime: Long,   // milliseconds
    val text: String
)
