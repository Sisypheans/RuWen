package com.ruwen.audioplayer.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.db.AppDatabase
import com.ruwen.audioplayer.databinding.ActivityMainBinding
import com.ruwen.audioplayer.ui.home.PlaylistHomeFragment
import com.ruwen.audioplayer.ui.podcast.PodcastFragment
import com.ruwen.audioplayer.ui.search.SearchFragment
import com.ruwen.audioplayer.util.OrphanCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主宿主：单 Activity + 三 Fragment（首页 / 播客 / 搜索）。
 *
 * 底部导航只负责切换 navHostContainer 里的 Fragment，并同步标题栏文案；
 * 工具栏、迷你播放栏、底部导航本身都是全局共享的，不随页面切换重建。
 * 权限申请与全局菜单（字幕模型设置）也保留在本宿主上。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var miniPlayerController: MiniPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        // 迷你栏参与布局，页面容器会自动避让，无需再做 padding 补偿。
        // 本页迷你栏下面紧接底部导航 → 进度条兼作两者的分隔线（不靠阴影分层）。
        miniPlayerController = MiniPlayerController(this, progressAsDivider = true)

        setupBottomNavigation()
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        miniPlayerController.onStart()
    }

    override fun onStop() {
        miniPlayerController.onStop()
        super.onStop()
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        // 首次进入展示首页
        if (supportFragmentManager.findFragmentById(R.id.navHostContainer) == null) {
            showPage(R.id.nav_home)
        }
    }

    /**
     * 切换页面。已创建过的 Fragment 用 hide/show 复用，避免每次切页都重建、
     * 丢失列表滚动位置与加载状态。
     */
    private fun showPage(itemId: Int) {
        val tag = "page_$itemId"
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        fm.fragments.forEach { fragment ->
            if (fragment.isVisible && fragment.tag != tag) transaction.hide(fragment)
        }

        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.navHostContainer, createFragmentFor(itemId), tag)
        }
        transaction.commit()

        supportActionBar?.title = getString(titleResFor(itemId))
    }

    private fun createFragmentFor(itemId: Int): Fragment = when (itemId) {
        R.id.nav_podcast -> PodcastFragment()
        R.id.nav_search -> SearchFragment()
        else -> PlaylistHomeFragment()
    }

    private fun titleResFor(itemId: Int): Int = when (itemId) {
        R.id.nav_podcast -> R.string.nav_podcast
        R.id.nav_search -> R.string.nav_search
        else -> R.string.playlists
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsMenu(item)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 设置菜单（参考 Salt Player）：在三点按钮**左下方**弹出小卡片菜单，
     * 两项 = 模型设置（原「字幕识别模型」）/ 清除缓存（清理孤儿文件）。
     * Gravity.END 让菜单右缘对齐按钮右缘 → 视觉上出现在按钮左下方。
     */
    private fun showSettingsMenu(anchor: MenuItem) {
        val toolbar = binding.toolbar
        val anchorView = toolbar.findViewById<View>(R.id.action_settings) ?: toolbar
        val popup = PopupMenu(this, anchorView, Gravity.END)
        popup.setForceShowIcon(true)
        popup.menu.add(Menu.NONE, 1, Menu.NONE, R.string.menu_whisper_model)
        popup.menu.add(Menu.NONE, 2, Menu.NONE, R.string.menu_clear_cache)
        popup.setOnMenuItemClickListener { clicked ->
            when (clicked.itemId) {
                1 -> {
                    WhisperModelSettingsDialog.show(this)
                    true
                }
                2 -> {
                    confirmClearCache()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 清除缓存：先确认，再在 IO 线程清理孤儿文件，完成后提示数量 */
    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_clear_cache)
            .setMessage(R.string.clear_cache_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    val dao = AppDatabase.getDatabase(applicationContext)
                    val result = withContext(Dispatchers.IO) {
                        OrphanCleaner.clean(
                            applicationContext,
                            dao.audioItemDao(),
                            dao.playlistDao()
                        )
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.clear_cache_done, result.deletedFiles),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }
}
