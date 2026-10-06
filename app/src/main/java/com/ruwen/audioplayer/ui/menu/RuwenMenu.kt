package com.ruwen.audioplayer.ui.menu

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ListPopupWindow
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.databinding.ItemMenuOptionBinding

/**
 * 弹出菜单的一项。
 *
 * @param id 业务自定义 id（用调用方自己的常量即可，不依赖 R.id）
 * @param label 文案
 * @param iconRes 图标资源；图标渲染在条目**最右侧**
 * @param enabled 是否可点（false 时整行按 M3 规范降到 38% 透明度且不响应点击）
 */
data class MenuOption(
    val id: Int,
    val label: CharSequence,
    val iconRes: Int,
    val enabled: Boolean = true
)

/**
 * 全项目统一的弹出菜单。
 *
 * **为什么不用系统 PopupMenu**：
 * 1. 系统 PopupMenu 的图标只能渲染在左侧（leading），而本项目约定图标统一在**右侧**；
 * 2. 系统 PopupMenu 的选中/多选态只能靠 `setGroupCheckable` 的原生单选圆圈，那是 M2 时代产物，
 *    在 M3 主题下是一排突兀的黑点。
 *
 * 因此这里用 `ListPopupWindow` + 自定义条目布局：保留「锚在按钮下方弹出」的手感，
 * 同时完全掌控布局（图标右置）与配色（白底，与主题 `popupMenuStyle` 一致）。
 */
object RuwenMenu {

    /**
     * 弹层右缘相对锚点按钮右缘的**内缩距离（dp）**。
     *
     * 锚点是 48dp 的三点 ImageButton（padding 12dp + 24dp 图标居中，见
     * `item_playlist.xml` / `item_audio.xml`）或工具栏同样规格的 ActionMenuItemView。
     * 因此「三个圆点所在的竖线」距按钮右缘 = 12dp（padding）+ 12dp（图标半宽）= 24dp。
     *
     * 用户 2026-10-06 拍板：弹层右缘应压在这条竖线上（而不是按钮右缘 / 图标右缘），
     * 这是主流 App 里最常见的观感。
     */
    private const val ANCHOR_RIGHT_INSET_DP = 24

    /** 弹层与屏幕边缘的最小留白（dp），防止极端窄屏 / 超长文案时贴边 */
    private const val SCREEN_EDGE_MARGIN_DP = 8

    /**
     * 在 [anchor] 下方弹出菜单。
     *
     * 右缘对齐**锚点三点图标所在的竖线**：不用 `setDropDownGravity(Gravity.END)`
     * （它对齐的是按钮右缘，比圆点竖线还右 24dp），而是自己按像素算 horizontalOffset，
     * 结果不依赖框架对 gravity 的解释，也方便以后微调。
     *
     * @param onSelect 仅在选中**可用**项时回调（禁用项点击不关闭菜单）
     */
    fun show(
        context: Context,
        anchor: View,
        options: List<MenuOption>,
        onSelect: (MenuOption) -> Unit
    ) {
        if (options.isEmpty()) return
        val adapter = MenuOptionAdapter(context, options)
        val popup = ListPopupWindow(context)
        popup.setAdapter(adapter)
        popup.anchorView = anchor
        popup.setBackgroundDrawable(
            AppCompatResources.getDrawable(context, com.ruwen.audioplayer.R.drawable.bg_popup_menu)
        )
        // 与主题里溢出菜单的 dropDownVerticalOffset 保持一致：从锚点下方 4dp 展开，不压住按钮
        val density = context.resources.displayMetrics.density
        popup.verticalOffset = (4 * density).toInt()
        popup.isModal = true
        // 宽度按内容测量（M3 菜单最小宽度 112dp），避免短文案时被压成一团
        val contentWidth =
            maxOf(measureContentWidth(context, adapter), (112 * density).toInt())
        popup.width = contentWidth

        // 水平位置：默认 gravity（START|TOP）下，弹层左边 = 锚点左边 + horizontalOffset，
        // 于是「右缘 = 锚点右缘 - 内缩」= 锚点宽 - 弹层宽 - 内缩。
        val anchorWidth = anchor.width
        if (anchorWidth > 0) {
            var offset = anchorWidth - contentWidth - (ANCHOR_RIGHT_INSET_DP * density).toInt()
            // 兜底：极窄屏上别顶到左边缘（此时宁可牺牲右缘对齐）
            val anchorLeft = IntArray(2).also { anchor.getLocationOnScreen(it) }[0]
            val minLeft = (SCREEN_EDGE_MARGIN_DP * density).toInt()
            if (anchorLeft + offset < minLeft) offset = minLeft - anchorLeft
            popup.horizontalOffset = offset
        } else {
            // 锚点尚未布局（极少数情况）：退化成右缘对齐按钮右缘
            popup.setDropDownGravity(Gravity.END)
        }

        popup.setOnItemClickListener { _, _, position, _ ->
            val option = options.getOrNull(position) ?: return@setOnItemClickListener
            if (!option.enabled) return@setOnItemClickListener
            popup.dismiss()
            onSelect(option)
        }
        popup.show()
    }

    /** 条目固定装饰宽度：左右内边距 12dp + 图标 24dp + 图标左侧间距 12dp */
    private const val ITEM_CHROME_DP = 12 + 24 + 12

    /** 量文字宽度时给的上限（dp）：只用于「别让它按屏幕宽去量」，实际取文字自身宽度 */
    private const val TEXT_MEASURE_LIMIT_DP = 400

    /**
     * 测量最宽条目，作为弹层宽度。
     *
     * **为什么不能直接 measure 整行**：条目里的文字是 `layout_width=0dp + weight=1`，
     * 在 UNSPECIFIED 下 LinearLayout 会按「EXACTLY 0」去量它（只有 EXACTLY 模式才会走
     * 「先跳过、有剩余空间再按权重补量」的优化），量出来只剩固定装饰的宽度，
     * 于是长文案的菜单会被压到最小宽度 112dp、文字被省略号截断。
     * 所以这里只量**文字本身**（用条目里那个已经应用好 textAppearance 的 TextView），
     * 再加上固定的装饰宽度。
     */
    private fun measureContentWidth(context: Context, adapter: BaseAdapter): Int {
        val density = context.resources.displayMetrics.density
        val chrome = (ITEM_CHROME_DP * density).toInt()
        val textSpec = View.MeasureSpec.makeMeasureSpec(
            (TEXT_MEASURE_LIMIT_DP * density).toInt(), View.MeasureSpec.AT_MOST
        )
        var maxWidth = 0
        for (i in 0 until adapter.count) {
            val view = adapter.getView(i, null, null) ?: continue
            val label: TextView = view.findViewById(R.id.tvOptionLabel)
            label.measure(
                textSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            maxWidth = maxOf(maxWidth, chrome + label.measuredWidth)
        }
        return maxWidth
    }
}

private class MenuOptionAdapter(
    private val context: Context,
    private val options: List<MenuOption>
) : BaseAdapter() {

    override fun getCount(): Int = options.size
    override fun getItem(position: Int): MenuOption = options[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val binding = if (convertView == null) {
            ItemMenuOptionBinding.inflate(LayoutInflater.from(context), parent, false)
        } else {
            ItemMenuOptionBinding.bind(convertView)
        }
        val option = getItem(position)
        val label: TextView = binding.tvOptionLabel
        val icon: ImageView = binding.ivOptionIcon
        label.text = option.label
        icon.setImageResource(option.iconRes)
        // 禁用项：M3 规范为 38% 透明度
        binding.optionRow.alpha = if (option.enabled) 1f else 0.38f
        return binding.root
    }
}
