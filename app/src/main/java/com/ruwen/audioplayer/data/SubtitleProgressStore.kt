package com.ruwen.audioplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 字幕生成进度（内存态）。
 *
 * ## 为什么不放数据库
 * 进度是**连续变化值**：解码阶段每 1% 就要刷一次，长音频一轮下来上百次。
 * 早前把它写进 `audio_items`，后果是——Room 的失效回调是**按表**触发的，
 * 任何一行被写，所有查询这张表的 LiveData 都会重新下发，而 LiveData 不做相等去重，
 * 于是「数据库里什么都没变，整个 App 的列表都在重绑」，甚至把列表点击都吞掉了。
 *
 * 现在按业界做法分开：
 *  - **离散状态**（排队 / 生成中 / 已生成 / 失败 + 错误文案）→ 落库（`audio_items`）；
 *  - **连续进度**（百分比 + 预计剩余）→ 放这里（进程内 StateFlow），只驱动 UI 与通知。
 *
 * key 用共享字幕文件名（[com.ruwen.audioplayer.data.entity.SubtitleIdentity.fileNameOf]）：
 * 同一音频在多张播放列表里是多行记录，但共享同一份字幕，进度自然也该共享。
 *
 * 代价（已知且接受）：进程被杀后进度丢失，重新进页面只会看到「生成中」没有百分比——
 * 与播客下载进度的处理方式一致。
 */
object SubtitleProgressStore {

    /** 单条音频的生成进度 */
    data class Progress(val percent: Int, val etaMillis: Long)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** 当前所有正在生成的字幕进度，key = 共享字幕文件名 */
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    fun publish(key: String, percent: Int, etaMillis: Long) {
        _progress.update { it + (key to Progress(percent, etaMillis)) }
    }

    /** 生成结束（成功 / 失败 / 取消）后清掉该条进度 */
    fun clear(key: String) {
        _progress.update { it - key }
    }
}
