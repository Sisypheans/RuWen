package com.ruwen.audioplayer.whisper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.RuWenApplication
import com.ruwen.audioplayer.data.SubtitleProgressStore
import com.ruwen.audioplayer.data.entity.SubtitleIdentity
import com.ruwen.audioplayer.data.repository.PlaylistRepository
import com.ruwen.audioplayer.ui.MainActivity
import com.ruwen.audioplayer.util.SubtitleUtils
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 字幕生成 Worker
 * 使用 WorkManager 在后台执行字幕生成任务。
 *
 * FIFO 串行队列：所有音频共用同一个唯一工作名 [QUEUE_WORK_NAME]，
 * doWork() 内部循环抽干 [SubtitleQueue]，保证严格按入队顺序串行生成，
 * 不会并发（避免多份模型同时加载、抢同一组 CPU 核心）。
 */
class SubtitleGenerationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as RuWenApplication
        val whisperManager = WhisperManager.getInstance(applicationContext)
        val repository = app.playlistRepository
        val ctx = applicationContext

        try {
            while (true) {
                val entry = SubtitleQueue.peek(ctx) ?: break
                SubtitleQueue.currentAudioId = entry.audioId
                try {
                    processOne(ctx, repository, whisperManager, entry)
                    // 按 audioId 精确出队，而不是 pop 队首：
                    // 取消会从队列里移除条目，若此处仍 pop 队首，可能误删排在后面的「下一条」。
                    SubtitleQueue.remove(ctx, entry.audioId)
                } catch (e: CancellationException) {
                    // 无论整体取消还是用户取消当前单条，都先出队并复位状态
                    SubtitleQueue.remove(ctx, entry.audioId)
                    Log.w(TAG, "Subtitle generation cancelled for audioId=${entry.audioId}")
                    // 取消不是失败：复位成「未生成」/「保留旧字幕」，用户可重新发起。
                    // 若不复位，该条会一直停留在 GENERATING，用户回到列表看到「生成中」，
                    // 会以为取消没生效、或以为它又在生成了。
                    runCatching { repository.resetSubtitleAfterCancel(entry.audioId) }
                        .onFailure { Log.w(TAG, "Reset subtitle status after cancel failed", it) }
                    runCatching {
                        withContext(NonCancellable) { whisperManager.release() }
                    }.onFailure { Log.w(TAG, "Release whisper context failed", it) }
                    // 整个 worker 被 WorkManager 停止 → 原样抛出，交给 WorkManager 判定取消。
                    // 用 ListenableWorker 自带的 isStopped（无需额外 import）：
                    // 用户取消「当前这一条」时走的是 WhisperManager.cancel()（native abort），
                    // 并不会停掉 worker，所以 isStopped 仍为 false → 继续处理队列里的下一条。
                    if (isStopped) throw e
                    // 否则仅用户取消当前这一条（native abort）→ 继续下一条
                } catch (e: Exception) {
                    // 单条失败也出队，不能卡住整条队列
                    SubtitleQueue.remove(ctx, entry.audioId)
                    val message = e.message ?: getString(R.string.error_unknown)
                    Log.e(TAG, "Subtitle generation failed for audioId=${entry.audioId}: $message", e)
                    // 失败同样同步给同音频的所有条目：它们共享同一份字幕，成败应当一致
                    val failedItem = repository.getAudioItemById(entry.audioId)
                    if (failedItem != null) {
                        repository.setSubtitleFailedShared(failedItem, message)
                    } else {
                        repository.setSubtitleFailed(entry.audioId, message)
                    }
                    sendFailureNotification(message)
                }
            }
        } finally {
            SubtitleQueue.currentAudioId = null
        }

        // 竞态兜底：退出循环时队列里又来了新条目，retry 让 worker 重新跑一遍。
        // 注意：withContext 不是 inline 函数，这里不能用非局部 return，只能作为最后一个表达式返回。
        if (SubtitleQueue.peek(ctx) != null) Result.retry() else Result.success()
    }

    /**
     * 处理单条音频的字幕生成。
     * 成功自然返回；任何异常都向上抛，由 doWork 的循环统一做「出队 + 置 FAILED/继续」。
     */
    private suspend fun processOne(
        context: Context,
        repository: PlaylistRepository,
        whisperManager: WhisperManager,
        entry: SubtitleQueue.Entry
    ) {
        val audioId = entry.audioId
        val audioUri = entry.uri

        // 字幕共享：先取出音频条目，用「名称+时长+文件大小」派生共享字幕文件名。
        // 同一个音频可能存在于多个播放列表（多行记录），生成一次后所有条目共享同一份字幕。
        val item = repository.getAudioItemById(audioId)
            ?: throw IllegalStateException(context.getString(R.string.err_audio_missing))
        val sharedFileName = SubtitleIdentity.fileNameOf(item)

        // 更新状态为生成中（同音频的所有条目同步，避免用户在别处重复发起同一次识别）
        repository.setSubtitleGeneratingShared(item)

        // 尝试显示前台通知（可能失败，不影响功能）
        try {
            setForeground(createForegroundInfo(0, 0L))
        } catch (e: Exception) {
            // 前台服务不可用，继续作为后台任务运行
        }

        // 队列剩余条数（当前这条还在队列里，所以 -1）
        val remaining = (SubtitleQueue.size(context) - 1).coerceAtLeast(0)

        // 进度只走内存（SubtitleProgressStore）+ 通知，**不写数据库**：
        // 进度是连续值、解码阶段上百次回调，写库会让所有观察 audio_items 的列表被反复刷新。
        // 数据库里只保留离散状态（生成中 / 已生成 / 失败），由下面几处 setXxx 负责。
        var lastPublishedProgress = -1

        try {
            // 执行字幕生成（写入共享字幕文件）
            val subtitlePath = whisperManager.generateSubtitle(
                Uri.parse(audioUri),
                sharedFileName
            ) { progress, etaMillis ->
                if (progress == lastPublishedProgress) return@generateSubtitle
                lastPublishedProgress = progress
                try {
                    updateNotification(progress, etaMillis, remaining)
                } catch (e: Exception) {
                    // 通知更新失败，忽略
                }
                SubtitleProgressStore.publish(sharedFileName, progress, etaMillis)
            }

            // 更新数据库（成功：写入路径并清除历史错误）——作用于该音频的所有条目
            repository.updateSubtitlePathShared(item, subtitlePath)
        } finally {
            // 无论成功、失败还是取消，都要把内存里的进度清掉，否则 UI 会一直停在"生成中 xx%"
            SubtitleProgressStore.clear(sharedFileName)
        }

        // 发送完成通知
        sendCompletionNotification()
    }

    private fun createForegroundInfo(progress: Int, etaMillis: Long): ForegroundInfo {
        createChannel()

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.subtitle_notification_generating))
            .setContentText(progressText(progress, etaMillis))
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Android 14+ 需要指定前台服务类型
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(progress: Int, etaMillis: Long, remaining: Int = 0) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.subtitle_notification_generating))
            .setContentText(progressText(progress, etaMillis, remaining))
            .setSmallIcon(R.drawable.ic_subtitle)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /** 进度 + 剩余时间文案；etaMillis <= 0 时只显示百分比；remaining > 0 时附队列剩余条数 */
    private fun progressText(progress: Int, etaMillis: Long, remaining: Int = -1): String {
        val base = if (etaMillis > 0L) {
            getString(
                R.string.subtitle_notification_progress,
                progress,
                SubtitleUtils.formatEta(applicationContext, etaMillis)
            )
        } else {
            "$progress%"
        }
        return if (remaining > 0) {
            getString(R.string.subtitle_notification_progress_queued, base, remaining)
        } else {
            base
        }
    }

    /** 取字符串资源（Worker 不是 Context，统一走 applicationContext） */
    private fun getString(resId: Int, vararg args: Any): String =
        applicationContext.getString(resId, *args)

    private fun sendCompletionNotification() {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.subtitle_notification_done_title))
            .setContentText(getString(R.string.subtitle_notification_done_text))
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun sendFailureNotification(message: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeMessage = message.ifBlank { getString(R.string.error_unknown) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.subtitle_notification_failed_title))
            .setContentText(safeMessage.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeMessage))
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.subtitle_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.subtitle_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SubtitleGenerationWorker"
        private const val CHANNEL_ID = "subtitle_generation"
        private const val NOTIFICATION_ID = 1001

        /** 所有音频共用同一个唯一工作名，doWork 内部循环串行抽干队列 */
        private const val QUEUE_WORK_NAME = "subtitle_generation_queue"

        /**
         * 入队一个字幕生成任务：
         * 1. 先写 [SubtitleQueue]（SharedPreferences 持久化、去重、FIFO）；
         * 2. 用单一唯一工作名 + KEEP 触发 worker：
         *    - 已有 worker 在跑（RUNNING 属 uncompleted）→ KEEP 不会重复启动，
         *      正在跑的 worker 自己的循环会取出新条目；
         *    - 没有 worker 在跑 → KEEP 会新起一个。
         */
        fun startGeneration(context: Context, audioId: Long, audioUri: String) {
            val enqueued = SubtitleQueue.enqueue(context, audioId, audioUri)
            if (!enqueued) return   // 已在队列中（去重），不重复入队

            val workRequest = OneTimeWorkRequestBuilder<SubtitleGenerationWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                QUEUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * 取消指定音频的字幕生成：
         * - 若它正在跑（[SubtitleQueue.currentAudioId] == audioId）→ 让 native 立即 abort，
         *   worker 捕获 CancellationException 后把该条出队并继续下一条；
         * - 否则它还在队列里没开始 → 直接移除队列（该条从未被置过 GENERATING，
         *   DB 状态本就是 NOT_GENERATED，无需额外复位）。
         * 不使用 cancelUniqueWork / cancelAllWorkByTag，否则会把整条队列一起干掉。
         */
        fun cancelGeneration(context: Context, audioId: Long) {
            // 1) 无论它是否正在跑，都**先从队列移除**。
            //    正在跑的那条若只调 native abort 而不出队，一旦 worker 因异常或被 WorkManager
            //    停止而没走到出队逻辑，它就会在下一轮循环里被重新捡起来——
            //    表现就是「明明点了取消，它却又自己开始生成」。
            //    出队统一用 remove(audioId) 而不是 pop()，不会误伤排在后面的条目。
            SubtitleQueue.remove(context, audioId)

            // 2) 若它正在跑：立即置位 native abort 标志，让阻塞的 whisper_full 尽快返回
            if (SubtitleQueue.currentAudioId == audioId) {
                runCatching { WhisperManager.getInstance(context).cancel() }
            }

            // 3) 立即复位数据库状态。native abort 要等当前 whisper 调用返回才真正生效，
            //    这期间若不复位，用户回到列表会看到该条仍显示「生成中」，以为取消没生效。
            val app = context.applicationContext as? RuWenApplication ?: return
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { app.playlistRepository.resetSubtitleAfterCancel(audioId) }
            }
        }
    }
}
