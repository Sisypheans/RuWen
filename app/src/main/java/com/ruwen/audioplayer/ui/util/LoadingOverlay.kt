package com.ruwen.audioplayer.ui.util

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ruwen.audioplayer.R

/**
 * 通用「加载中」遮罩层。
 *
 * **为什么不用 Dialog / ProgressDialog**：
 *  - ProgressDialog 已废弃；
 *  - AlertDialog（项目里原本的做法）默认 `cancelable=true`，**点一下弹窗外就 dismiss**，
 *    这正是「导入整个文件夹时点一下屏幕加载特效就消失」的根因；
 *  - Dialog 是独立 Window，盖不住同页面的标题栏与悬浮按钮，还要额外处理返回键。
 *
 * 这里改成往 Activity 的 content 容器（[android.R.id.content]）里塞一层 View：
 *  - `clickable=true` → 触摸被这层吃掉，点屏幕不会消失；
 *  - `elevation=32dp` → 盖在 AppBarLayout(6dp)、底部操作栏(8dp) 之上；
 *  - 返回键通过 [OnBackPressedDispatcher] 注册一个「空实现」回调拦截，
 *    只在遮罩显示期间启用（[OnBackPressedCallback.isEnabled]）。
 *
 * 用法（每个页面一行）：
 * ```
 * private val loadingOverlay by lazy { LoadingOverlay.attach(this) }
 * ...
 * loadingOverlay.show()
 * try { ... } finally { loadingOverlay.hide() }
 * ```
 *
 * 默认是**纯图标无文字**。只有确实要展示进度的场景（导入整个文件夹）
 * 才用 [updateMessage] 打开文字区——那是进度数字，不是「加载中…」这类静态文案。
 */
class LoadingOverlay private constructor(
    private val root: View,
    private val messageView: TextView,
    private val backCallback: OnBackPressedCallback
) {

    /** 当前是否正在显示（供调用方做防重复点击判断） */
    var isShowing: Boolean = false
        private set

    /**
     * 显示遮罩。
     *
     * @param message 可选文案；传 null / 空串则只显示转圈图标。
     */
    fun show(message: CharSequence? = null) {
        isShowing = true
        applyMessage(message)
        root.visibility = View.VISIBLE
        // 显示期间吞掉返回键：加载不可中断，避免「返回了但任务还在跑」的半吊子状态
        backCallback.isEnabled = true
    }

    /**
     * 更新（或清除）进度文案。仅在显示中生效，隐藏状态下调用是空操作。
     */
    fun updateMessage(message: CharSequence?) {
        if (!isShowing) return
        applyMessage(message)
    }

    fun hide() {
        isShowing = false
        root.visibility = View.GONE
        backCallback.isEnabled = false
    }

    private fun applyMessage(message: CharSequence?) {
        messageView.text = message
        messageView.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    companion object {

        /**
         * 把遮罩挂到 Activity 上。**必须在 setContentView 之后调用**
         * （否则 [android.R.id.content] 里还没有内容，遮罩会挂在空容器上）。
         */
        fun attach(activity: AppCompatActivity): LoadingOverlay {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = activity.layoutInflater
                .inflate(R.layout.view_loading_overlay, content, false)

            // 空实现 = 什么都不做，即「返回键被消费掉、但不执行任何返回动作」
            val callback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = Unit
            }
            activity.onBackPressedDispatcher.addCallback(activity, callback)

            content.addView(root)
            return LoadingOverlay(
                root = root,
                messageView = root.findViewById(R.id.loadingMessage),
                backCallback = callback
            )
        }
    }
}
