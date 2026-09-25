package com.ruwen.audioplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.whisper.WhisperLanguage
import com.ruwen.audioplayer.whisper.WhisperManager
import com.ruwen.audioplayer.whisper.WhisperModel
import com.ruwen.audioplayer.whisper.WhisperModelManager
import com.ruwen.audioplayer.whisper.WhisperSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「字幕识别模型」设置弹窗（纯代码构建 UI，无对应 layout xml）。
 *
 * 当前形态：
 *  - 顶部「识别语言」选择：一个 outlined 按钮，点击弹出**纵向单选列表**（官方推荐的前 12 种语言，
 *    按 whisper.cpp `g_lang` 顺序排列，英语在最前）；
 *  - 模型行只展示「名称 / 大小 / 是否已下载」，右侧一个**纯图标**操作按钮：未下载显示下载图标、
 *    已下载显示删除图标（图标为全球通用语义，不附加文字，降低学习成本）；
 *  - 点击行本身 = 选中该模型（单选语义，带勾选高亮）；选中未下载的模型即触发下载流程；
 *  - 兼容模式 / 词级时间戳仍作为勾选项保留。
 *
 * 语言与模型**解耦**：英语用 `.en` 模型，其余 11 种语言共用同一套多语言模型。
 * 切换语言不会强制换模型，仅在所选模型与目标语言类别（纯英文 ↔ 多语言）不符时做一致性兜底。
 *
 * 线程数选择、性能自检（benchmark）已从本对话框移除。
 */
object WhisperModelSettingsDialog {

    /**
     * 当前正在显示的对话框，用于防止重复弹出。
     *
     * 一旦叠加两层，屏幕上会**同时**出现两个语言的模型列表（两个 "small · Q8_0"
     * 各带一个选中态），看起来就像「英语和日语的 small 被同时选中」。
     * 列表本身每次都是按当前语言重建的，同一个对话框内不可能出现两个语言的模型。
     */
    private var currentDialog: AlertDialog? = null

    /**
     * 对话框运行期的可变状态。
     *
     * 之所以抽成类、并把下面的构建函数写成成员函数而不是 show() 内的局部函数：
     * Kotlin 的**局部函数不支持前向引用**（必须先声明后使用），而这里的逻辑互相调用
     * （删除模型后要重建列表、切换语言要重建列表并刷新按钮），局部函数无法表达。
     */
    private class Ui(
        val activity: AppCompatActivity,
        var language: WhisperLanguage,
        /**
         * 当前选中的模型 —— **全局唯一**，且只由用户在卡片上手动点击来改变。
         *
         * 关键：它**不随语言切换而变化**。选中项若不属于当前显示的语言类别，列表里就一行都不高亮
         * （id 匹配不上）；切回它所属的语言时又会重新高亮。
         * 可空仅作防御，null 表示确实没有任何选中。
         */
        var model: WhisperModel?,
        val container: LinearLayout,
        val btnLanguage: MaterialButton
    ) {
        /** 每个模型行的卡片（key = 模型 id），用于刷新「选中」描边与底色 */
        val rows = mutableMapOf<String, MaterialCardView>()
        /** 每个模型行的单选圆点（key = 模型 id），与 rows 同步刷新 */
        val radios = mutableMapOf<String, MaterialRadioButton>()
    }

    fun show(activity: AppCompatActivity) {
        // 已有对话框在显示 → 直接返回，不再重复创建（避免两层叠加）
        if (currentDialog?.isShowing == true) return

        val context = activity

        val initialLanguage = WhisperSettings.selectedLanguage(context)
        // 回显「当前生效的那个模型」。selectedModel() 内部已保证返回值类别与当前语言一致
        // （保存的模型类别不符时回退到该语言的默认档），所以打开时总有一项是选中的。
        val initialModel = WhisperSettings.selectedModel(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        // ---- 识别语言：标签 + 按钮（点击弹出纵向单选列表）----
        // 按钮用 outlined 样式自带描边，右侧加一个下拉箭头图标暗示「可展开」；
        // 文字直接显示语言的母语名（如「中文」「English」），无需额外说明。
        val langRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val langLabel = TextView(context).apply {
            text = context.getString(R.string.whisper_model_language)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        val btnLanguage = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            insetTop = 0
            insetBottom = 0
            iconGravity = MaterialButton.ICON_GRAVITY_END
            setIconResource(R.drawable.ic_arrow_drop_down)
            iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.text_secondary)
            )
        }
        langRow.addView(
            langLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        langRow.addView(
            btnLanguage,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 12)
            }
        )
        root.addView(
            langRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(context, 4), 0, dp(context, 8)) }
        )
        root.addView(divider(context))

        // ---- 模型列表容器（随语言切换重建）----
        val modelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(modelContainer)

        val ui = Ui(
            activity = activity,
            language = initialLanguage,
            model = initialModel,
            container = modelContainer,
            btnLanguage = btnLanguage
        )
        btnLanguage.setOnClickListener { showLanguagePicker(ui) }

        // ---- 兼容模式 ----
        val baselineCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.whisper_model_force_baseline)
            isChecked = WhisperSettings.forceBaselineNative(context)
            setPadding(0, 12, 0, 12)
        }
        root.addView(baselineCheckBox)

        // ---- 词级时间戳 ----
        val wordTsCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.whisper_model_word_timestamps)
            isChecked = WhisperSettings.wordTimestamps(context)
            setPadding(0, 12, 0, 12)
        }
        root.addView(wordTsCheckBox)

        val scrollView = ScrollView(context).apply { addView(root) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.whisper_model_dialog_title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 没有选中（例如刚切换过语言），或选中的模型类别与当前语言不符时，
                // 不允许保存：直接保存会把错类别的模型设为当前模型。明确提示用户先选一个。
                val chosen = ui.model
                val wantEnglishOnly = (ui.language == WhisperLanguage.ENGLISH)
                if (chosen == null || chosen.isEnglishOnly != wantEnglishOnly) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.whisper_model_pick_in_language,
                            ui.language.displayName
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                // 分别落盘：模型 id 与识别语言各自独立保存（二者已解耦）
                WhisperSettings.setSelectedModel(context, chosen)
                WhisperSettings.setSelectedLanguage(context, ui.language)
                WhisperSettings.setForceBaselineNative(context, baselineCheckBox.isChecked)
                WhisperSettings.setWordTimestamps(context, wordTsCheckBox.isChecked)
                dialog.dismiss()
            }
        }

        // 关闭后清空引用，否则后续再打开时会被上面的「防重复」判断挡住
        dialog.setOnDismissListener { currentDialog = null }

        syncLanguageButton(ui)
        rebuildModels(ui)
        currentDialog = dialog
        dialog.show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun divider(context: Context) = android.view.View(context).apply {
        // 与上方的语言行留出间距，避免按钮描边紧贴分区线
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            .apply { topMargin = dp(context, 14) }
        setBackgroundColor(0x1F000000)
    }

    // ------------------------------------------------------------------
    //  语言选择
    // ------------------------------------------------------------------

    /**
     * 弹出纵向单选列表选择识别语言。
     *
     * 列表按 [WhisperLanguage] 声明顺序展示（即 whisper.cpp `g_lang` 官方推荐顺序，英语在最前），
     * 每行直接显示语言母语名。选中即切换，并关闭本层选择框。
     */
    private fun showLanguagePicker(ui: Ui) {
        val context = ui.activity
        val langs = WhisperLanguage.values()
        val names = langs.map { it.displayName }.toTypedArray()
        val current = langs.indexOf(ui.language)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.whisper_model_language)
            .setSingleChoiceItems(names, current) { d, which ->
                setLanguage(ui, langs[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 切换语言：刷新语言按钮文字 + 重建模型列表，并把默认选中项切到该语言。
     *
     * **这里刻意不落盘**：语言与模型是独立存储的。若在此处直接 setSelectedLanguage，
     * 用户「切到中文 → 点取消」就会留下「language=zh 但 model_id 仍是英文模型」的半截状态，
     * 随后 [WhisperSettings.selectedModel] 的一致性保护会把它回退成中文默认模型——
     * 用户以为什么都没保存，实际语言已经变了。所以语言只在点「确定」时写入。
     */
    private fun setLanguage(ui: Ui, language: WhisperLanguage) {
        if (language == ui.language) return
        ui.language = language
        // **刻意不动 ui.model**：选中的那个模型是全局唯一的状态，切换语言不改变它。
        // 它属于当前语言类别时高亮，不属于时列表里一行都不亮（id 匹配不上），切回来又亮。
        // 例如：英语选 small → 切中文（不亮）→ 中文选 base（全局选中被覆盖为 base）
        //      → 切英语（不亮）→ 切回中文（重新显示 base 选中）。
        syncLanguageButton(ui)
        rebuildModels(ui)
    }

    /** 刷新语言按钮文字（显示当前语言的母语名） */
    private fun syncLanguageButton(ui: Ui) {
        ui.btnLanguage.text = ui.language.displayName
    }

    // ------------------------------------------------------------------
    //  列表构建（写成成员函数：互相调用不受「局部函数必须先声明后使用」的限制）
    // ------------------------------------------------------------------

    /**
     * 刷新模型行的选中态。
     *
     * 选中 = 加粗描边（主色）+ 浅紫底 + 单选圆点勾选。
     * 这是 Material 3 里「单选列表项」的标准表达，比原先只换一层背景色清晰得多。
     */
    private fun updateSelection(ui: Ui) {
        val context = ui.activity
        val strokeSelected = ContextCompat.getColor(context, R.color.purple_primary)
        val strokeDefault = ContextCompat.getColor(context, R.color.divider)
        val fillSelected = ContextCompat.getColor(context, R.color.purple_50)
        val fillDefault = ContextCompat.getColor(context, R.color.background)

        for ((id, card) in ui.rows) {
            // ui.model 为 null（没有任何选中）时，所有行都不高亮
            val selected = id == ui.model?.id
            // 注意：必须用 setStrokeColor(int)，不能写 strokeColor = int ——
            // MaterialCardView 的 getStrokeColor() 返回 ColorStateList，与 setStrokeColor(int)
            // 类型不匹配，Kotlin 无法合成属性（会直接编译失败）。
            card.setStrokeColor(if (selected) strokeSelected else strokeDefault)
            card.strokeWidth = if (selected) dp(context, 2) else dp(context, 1)
            card.setCardBackgroundColor(if (selected) fillSelected else fillDefault)
            ui.radios[id]?.isChecked = selected
        }
    }

    /** 按当前语言重建整个模型列表 */
    private fun rebuildModels(ui: Ui) {
        ui.rows.clear()
        ui.radios.clear()   // 与新卡片一起清掉，否则会残留已 detach 的 RadioButton 引用
        ui.container.removeAllViews()
        for (model in WhisperModel.valuesForLanguage(ui.language)) {
            ui.container.addView(buildRow(ui, model))
        }
        updateSelection(ui)
    }

    /** 选中某个模型；若它还没下载，顺带触发下载流程 */
    private fun selectModel(ui: Ui, model: WhisperModel) {
        ui.model = model
        updateSelection(ui)
        if (!WhisperModelManager.isDownloaded(ui.activity, model) &&
            !WhisperModelManager.isBundled(ui.activity, model) &&
            model.downloadable
        ) {
            confirmAndStartDownload(ui, model)
        }
    }

    /** 删除已下载模型的二次确认 */
    private fun showDeleteConfirm(ui: Ui, model: WhisperModel) {
        val context = ui.activity
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete)
            .setMessage(
                context.getString(R.string.whisper_model_delete_confirm, model.displayName)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                WhisperModelManager.delete(context, model)
                rebuildModels(ui)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 构建单个模型行（Material 3 卡片式）：
     *
     * ```
     * ┌──────────────────────────────────────────┐
     * │ ◉  small · Q8_0               [↓图标]   │   选中：加粗主色描边 + 浅紫底
     * │    大小 约 252 MB  未下载                 │
     * └──────────────────────────────────────────┘
     * ```
     * 单选圆点不单独响应点击，整张卡片负责选中，避免「点圆点没反应」的割裂感。
     * 右侧操作为**纯图标**按钮：下载（品牌色箭头）/ 删除（警示红垃圾桶），全球通用语义，不附文字。
     */
    private fun buildRow(ui: Ui, model: WhisperModel): View {
        val context = ui.activity
        val downloaded =
            WhisperModelManager.isDownloaded(context, model) ||
                WhisperModelManager.isBundled(context, model)
        val selected = model.id == ui.model?.id

        val card = MaterialCardView(
            context,
            null,
            com.google.android.material.R.attr.materialCardViewOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
            radius = dp(context, 12).toFloat()
            cardElevation = 0f
            strokeWidth = if (selected) dp(context, 2) else dp(context, 1)
            // 同上：strokeColor 无法合成为属性，只能用 setStrokeColor(int)
            setStrokeColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.purple_primary else R.color.divider
                )
            )
            setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.purple_50 else R.color.background
                )
            )
            isClickable = true
            setOnClickListener { selectModel(ui, model) }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        }

        val radio = MaterialRadioButton(context).apply {
            isChecked = selected
            isClickable = false
            isFocusable = false
        }
        row.addView(radio)

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(TextView(context).apply {
            text = model.displayName
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        texts.addView(TextView(context).apply {
            text = context.getString(
                R.string.whisper_model_size,
                WhisperModelManager.formatSize(model.approxSizeBytes)
            )
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        texts.addView(TextView(context).apply {
            text = if (downloaded) {
                context.getString(R.string.whisper_model_downloaded)
            } else {
                context.getString(R.string.whisper_model_not_downloaded_status)
            }
            textSize = 13f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (downloaded) R.color.purple_primary else R.color.text_secondary
                )
            )
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(context, 12) }
        )

        // 纯图标操作按钮：下载 = 品牌色箭头；删除 = 警示红垃圾桶。
        // 用 actionButtonStyle（自带涟漪触控反馈），40dp 最小触控区，符合 Material 图标按钮规范。
        val action = if (downloaded) {
            iconButton(context, R.drawable.ic_delete, R.color.error, R.string.delete)
        } else {
            iconButton(
                context,
                R.drawable.ic_download,
                R.color.purple_primary,
                R.string.whisper_model_download
            )
        }
        action.setOnClickListener {
            if (downloaded) {
                showDeleteConfirm(ui, model)
            } else {
                selectModel(ui, model)
            }
        }
        row.addView(action)

        card.addView(row)
        ui.rows[model.id] = card
        ui.radios[model.id] = radio
        return card
    }

    /** 生成一个纯图标按钮（下载 / 删除），带 contentDescription 保证无障碍可读 */
    private fun iconButton(
        context: Context,
        iconRes: Int,
        tintRes: Int,
        contentDescRes: Int
    ): ImageButton =
        ImageButton(context, null, android.R.attr.actionButtonStyle).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, tintRes)
            )
            contentDescription = context.getString(contentDescRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // 保证最小 40dp 触控区（Material 推荐图标按钮尺寸），同时避免过大
            val size = (40 * context.resources.displayMetrics.density).toInt()
            minimumWidth = size
            minimumHeight = size
        }

    /**
     * 下载前检查网络：计量网络（移动数据 / 按流量计费的 Wi-Fi）上先让用户确认，
     * 因为 small 档约 0.25GB、medium 档约 0.8GB，直接用流量下载可能很贵。
     */
    private fun confirmAndStartDownload(ui: Ui, model: WhisperModel) {
        val context = ui.activity
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val isMetered = connectivityManager?.isActiveNetworkMetered ?: true
        val hasNetwork = connectivityManager?.activeNetwork != null

        if (!hasNetwork) {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.whisper_model_no_network)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        if (!isMetered) {
            startDownload(ui, model)
            return
        }

        MaterialAlertDialogBuilder(context)
            .setMessage(
                context.getString(
                    R.string.whisper_model_metered_confirm,
                    model.displayName,
                    WhisperModelManager.formatSize(model.approxSizeBytes)
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.whisper_model_download) { _, _ ->
                startDownload(ui, model)
            }
            .show()
    }

    private fun startDownload(ui: Ui, model: WhisperModel) {
        val activity = ui.activity
        val context = ui.activity
        val progressBar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            }
        val statusText = TextView(context).apply {
            text = context.getString(R.string.whisper_model_downloading, 0)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(progressBar)
            addView(statusText)
        }

        // 用户主动取消时置位，避免最后再弹一个「下载失败：已取消」
        var userCancelled = false
        // 下载协程自己调用 dialog.dismiss() 时也会触发 onDismissListener；
        // 若不加以区分，就会把「自己所在的 job」取消掉（竞态：可能出现既弹
        // 「下载失败」又没真正中断，或结果对话框被跳过）。
        var finished = false
        var job: Job? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(model.displayName)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                userCancelled = true
                d.dismiss()
            }
            .create()

        dialog.setOnDismissListener { if (!finished) job?.cancel() }
        dialog.show()

        job = activity.lifecycleScope.launch {
            val result = WhisperModelManager.download(context, model) { downloaded, total ->
                activity.runOnUiThread {
                    if (total > 0) {
                        progressBar.isIndeterminate = false
                        progressBar.max = 100
                        progressBar.progress = ((downloaded * 100) / total).toInt()
                        statusText.text = context.getString(
                            R.string.whisper_model_downloading,
                            ((downloaded * 100) / total).toInt()
                        )
                    } else {
                        statusText.text = WhisperModelManager.formatSize(downloaded)
                    }
                }
            }

            finished = true
            dialog.dismiss()
            if (userCancelled && !result.isSuccess) return@launch

            // 下载成功后刷新列表：该行从「未下载」变为「已下载」，并保持选中态。
            // 否则用户下载完还得手动关掉/重开对话框才能看到状态变化。
            if (result.isSuccess) rebuildModels(ui)

            val message = if (result.isSuccess) {
                context.getString(R.string.whisper_model_download_ok)
            } else {
                context.getString(
                    R.string.whisper_model_download_failed,
                    result.exceptionOrNull()?.message ?: context.getString(R.string.error_unknown)
                )
            }
            MaterialAlertDialogBuilder(context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
