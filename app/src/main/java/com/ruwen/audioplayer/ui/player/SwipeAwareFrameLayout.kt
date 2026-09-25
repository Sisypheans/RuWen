package com.ruwen.audioplayer.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 字幕区容器：识别四个方向的滑动手势并交给外层处理。
 *
 *  - 横向滑动（左 / 右）：跳上一段 / 下一段字幕（沿用原有逻辑）。
 *  - 上滑：回到当前字幕起点（重听当前段）。
 *  - 下滑：播放 / 暂停。
 *
 * 判定采用「视频播放器式」的 fling（速度 + 方向）识别，而非旧版「必须滑满 48dp 直线」的
 * 距离阈值——只要快速轻扫一下即可触发，更符合直觉、也更好触发。
 *
 * 用户在字幕区不需要手动滚动（字幕由代码自动滚到顶部），因此上 / 下滑可以整体接管，
 * 无需和纵向滚动消歧；一旦判定为某个方向的滑动就拦截，内层不再收到后续事件。
 *
 * 选择「自定义容器 + onInterceptTouchEvent」而非在子 View 上挂 GestureDetector，
 * 是因为拦截点由我们自己掌控：判定为滑动后彻底接管，能干净地避免手势被内层消费掉。
 */
class SwipeAwareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private companion object {
        /** 判定主轴方向的位移阈值（dp）。超过即视为「开始滑动」，用于锁定方向并接管事件。 */
        const val AXIS_LOCK_DP = 12
        /** 慢速滑动（未达 fling 速度）触发动作的最小位移（dp）；fling 速度判定不受此限。 */
        const val SWIPE_MIN_DISTANCE_DP = 24
    }

    private val axisLockPx =
        (AXIS_LOCK_DP * context.resources.displayMetrics.density).toInt()
    private val swipeMinDistancePx =
        (SWIPE_MIN_DISTANCE_DP * context.resources.displayMetrics.density).toInt()

    private var downX = 0f
    private var downY = 0f
    /** 0 = 未锁定；1 = 横向；2 = 纵向。锁定时本 View 接管后续事件。 */
    private var axisLocked = 0
    /** 本次手势是否已由 onFling 处理，避免 ACTION_UP 兜底再次触发。 */
    private var flingHandled = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // 视频播放器式判定：GestureDetector 仅在速度超过系统最小 fling 速度时才回调 onFling，
                // 所以这里无需再卡位移下限——快速轻扫即可触发，这正是要解决的「太难触发」问题。
                flingHandled = true
                dispatchSwipe(dx, dy)
                return true
            }
        }
    )

    private fun dispatchSwipe(dx: Float, dy: Float) {
        if (abs(dx) > abs(dy)) {
            // 横向：左滑 dx < 0 → 上一段；右滑 dx > 0 → 下一段
            onSwipeListener?.invoke(if (dx < 0) -1 else 1)
        } else {
            // 纵向：上滑 dy < 0 → 回当前字幕起点；下滑 dy > 0 → 播放 / 暂停
            onVerticalSwipeListener?.invoke(if (dy < 0) -1 else 1)
        }
    }

    /**
     * 横向滑动回调。
     * @param direction -1 = 左滑（跳到上一段字幕），+1 = 右滑（跳到下一段字幕）
     */
    var onSwipeListener: ((direction: Int) -> Unit)? = null

    /**
     * 纵向滑动回调。
     * @param direction -1 = 上滑（回到当前字幕起点，重听当前段），+1 = 下滑（播放 / 暂停）
     */
    var onVerticalSwipeListener: ((direction: Int) -> Unit)? = null

    /**
     * 横向拖动过程回调，用来做「跟手位移」的视觉反馈。
     * @param dx 相对按下点的横向位移（像素）；拖动中持续回调，
     *           手势结束/取消时回调 0f，由使用方把位移复位。
     */
    var onSwipeDrag: ((dx: Float) -> Unit)? = null

    /**
     * 纵向拖动过程回调，用来做「竖向跟手位移」的视觉反馈（上滑 / 下滑时字幕区随手指轻微上下移动）。
     * @param dy 相对按下点的纵向位移（像素，上滑为负、下滑为正）；拖动中持续回调，
     *           手势结束/取消时回调 0f，由使用方把位移复位。
     */
    var onVerticalSwipeDrag: ((dy: Float) -> Unit)? = null

    /**
     * 统一的「移动处理」：判定并锁定主轴 + 派发跟手位移回调。
     *
     * 必须由 onInterceptTouchEvent 与 onTouchEvent **同时**调用，不能只放在拦截里：
     * 当内层子 View 不消费 ACTION_DOWN 时（例如本项目的 LockableScrollView 已禁用触摸滚动），
     * ViewGroup 的 mFirstTouchTarget 为 null，后续 ACTION_MOVE 不再经过 onInterceptTouchEvent，
     * 而是直接进 onTouchEvent。若轴锁定只写在拦截里，axisLocked 会永远是 0，
     * 跟手反馈（onSwipeDrag / onVerticalSwipeDrag）将整体失效——而滑动动作本身仍能触发，
     * 表现为「能滑动但没有跟手动画」。
     */
    private fun handleMove(ev: MotionEvent) {
        if (axisLocked == 0) {
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (abs(dx) > axisLockPx && abs(dx) > abs(dy)) {
                axisLocked = 1
            } else if (abs(dy) > axisLockPx && abs(dy) >= abs(dx)) {
                axisLocked = 2
            }
        }
        if (axisLocked == 1) {
            // 横向：跟手位移反馈
            onSwipeDrag?.invoke(ev.x - downX)
        } else if (axisLocked == 2) {
            // 纵向：竖向跟手位移反馈
            onVerticalSwipeDrag?.invoke(ev.y - downY)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 把事件喂给 GestureDetector：即使本 View 尚未拦截，也能在 ACTION_UP 时识别 fling。
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                axisLocked = 0
                flingHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                handleMove(ev)
                // 一旦锁定方向就接管，内层不再收到后续事件（用户不需要手动滚动字幕）
                if (axisLocked != 0) return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                onSwipeDrag?.invoke(0f)   // 复位可能存在的横向位移
                onVerticalSwipeDrag?.invoke(0f)   // 复位可能存在的纵向位移
                axisLocked = 0
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 已被拦截的事件继续喂给 detector（fling 的判定需要完整事件流）
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 与 onInterceptTouchEvent 走同一套逻辑：子 View 不消费 DOWN 时，
                // MOVE 只会进到这里，轴锁定必须在此也能完成，否则跟手反馈失效。
                handleMove(ev)
            }
            MotionEvent.ACTION_UP -> {
                // fling 未命中（慢速但位移达标的滑动）时，按位移兜底触发一次
                if (!flingHandled) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > swipeMinDistancePx || abs(dy) > swipeMinDistancePx) {
                        dispatchSwipe(dx, dy)
                    }
                }
                onSwipeDrag?.invoke(0f)
                onVerticalSwipeDrag?.invoke(0f)
                axisLocked = 0
                flingHandled = false
            }
            MotionEvent.ACTION_CANCEL -> {
                onSwipeDrag?.invoke(0f)
                onVerticalSwipeDrag?.invoke(0f)
                axisLocked = 0
                flingHandled = false
            }
        }
        return true
    }
}
