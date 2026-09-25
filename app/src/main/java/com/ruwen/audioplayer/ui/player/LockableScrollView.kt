package com.ruwen.audioplayer.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 可「锁定」的 ScrollView：保留代码驱动的滚动能力（scrollTo / smoothScrollTo），
 * 但仅在 [isScrollable] 为真时才拦截 / 消费触摸事件；为 false 时对触摸完全透明，
 * 把所有手势让给外层容器（SwipeAwareFrameLayout）去判定。
 *
 * 用途背景：字幕区由外层容器统一识别四向滑动手势（左右跳段、上下重听 / 播放暂停），
 * 而内层仍需 ScrollView 来容纳「当前句 + 下一句」可能超出卡片高度的内容，并实现
 * 「自动滚回顶部」让正在听的那句始终可见。
 *
 * 若保留标准 ScrollView，它会在手指纵向移动时把纵向手势当成「滚动」抢走，
 * 导致外层容器的上下滑判定几乎失效（横向不受影响，因为 ScrollView 不横向滚动）。
 * 本类用 isScrollable=false 让 ScrollView 对触摸透明，从而消除该冲突。
 *
 * 实现采用社区 / 官方常用的「LockableScrollView」范式：覆写 onInterceptTouchEvent 与
 * onTouchEvent，仅在可滚动时才委派给父类。代码驱动的 scrollTo 与触摸开关互不影响。
 *
 * @param isScrollable 是否允许触摸滚动；默认 true（行为等同标准 ScrollView），
 *                     本项目中由 PlayerActivity 显式置为 false，使触摸手势交由外层容器处理。
 */
class LockableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** true = 行为同标准 ScrollView（可触摸滚动）；false = 触摸透明，仅保留程序化滚动。 */
    var isScrollable: Boolean = true
        set(value) {
            field = value
            // 切到不可滚动时复位滚动位置，避免残留偏移
            if (!value) scrollTo(0, 0)
        }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return isScrollable && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return isScrollable && super.onTouchEvent(ev)
    }
}
