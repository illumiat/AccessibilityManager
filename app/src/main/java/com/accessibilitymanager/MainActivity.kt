package com.accessibilitymanager

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.accessibilitymanager.core.designsystem.component.AppNavigationBar
import com.accessibilitymanager.core.designsystem.component.NavItem
import com.accessibilitymanager.core.designsystem.theme.AppTheme

/**
 * 单 Activity 容器（**P2：外壳迁 Compose**）。
 *
 * 现状：**底栏为 Compose**（`AppNavigationBar`），内容区仍是两个 View Fragment
 * （`HomeFragment` / `SettingsFragment`），按 P3/P5 逐页迁移。
 *
 * ## 与旧实现的差异（均为 View 特有 workaround 的移除，非行为变更）
 *
 * - 旧 `BottomNavigationView.setSelectedItemId()` 会回调自身 → 需要 `suppressNavCallback` 抑制。
 *   Compose 的 `NavigationBarItem` 不自触发，该标志**不再需要**。
 * - 旧实现用 `int` 索引 + `syncBottomNav()` 双向同步；现在**单一数据源**：
 *   `currentTab` 是 Compose 状态，底栏直接读它，无需回写。
 *
 * ## 保留的行为（既有裁决，不得回退）
 *
 * - **切 tab 用 `show()/hide()` 而非 replace** —— 保留列表滚动位置与状态；
 * - `onSaveInstanceState` 持久化当前 tab（FragmentManager 不保存可见性，旋转/进程重建需自行恢复）。
 */
class MainActivity : AppCompatActivity() {

    /** 当前 tab：**Compose 状态**，底栏读它，变化即重组。 */
    private var currentTab by mutableStateOf(TAB_HOME)

    private lateinit var navItems: List<NavItem>

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题套用必须在 super.onCreate() 之前：主题属性是静态资源，无法热替换。
        ThemePref.applyTo(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        savedInstanceState?.getString(KEY_TAB)?.let { currentTab = it }

        // 标签文案由调用方提供（设计系统不持有业务文案）；两态图标是 MD3 NavigationBar 的形态要求。
        navItems = listOf(
            NavItem(
                key = TAB_HOME,
                label = getString(R.string.tab_home),
                selectedIcon = Icons.Filled.Home,
                unselectedIcon = Icons.Outlined.Home,
            ),
            NavItem(
                key = TAB_SETTINGS,
                label = getString(R.string.tab_settings),
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
            ),
        )

        findViewById<ComposeView>(R.id.compose_bottom_bar).setContent {
            AppTheme {
                AppNavigationBar(
                    items = navItems,
                    selectedKey = currentTab,
                    onSelect = { showTab(it) },
                    // 活动窗口尚未开启 edge-to-edge：系统已为内容让出底部区域，
                    // 这里传 0 避免底栏被二次让位而顶高（缺陷清单模式 9）。
                    // 【P6】全页面 Compose 化并统一 edge-to-edge 后，改回默认 insets。
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }

        showTab(currentTab)
    }

    private fun showTab(tab: String) {
        currentTab = tab
        val fm = supportFragmentManager
        val tx: FragmentTransaction = fm.beginTransaction()

        val tag = if (tab == TAB_HOME) TAB_HOME else TAB_SETTINGS
        val existing = fm.findFragmentByTag(tag)
        val toHide = fm.findFragmentByTag(if (tab == TAB_HOME) TAB_SETTINGS else TAB_HOME)


        val toShow: Fragment = existing ?: if (tab == TAB_HOME) HomeFragment() else SettingsFragment()
        if (existing == null) {
            tx.add(R.id.fragment_container, toShow, tag)
        } else {
            tx.show(toShow)
        }
        toHide?.let { tx.hide(it) }
        tx.commitNowAllowingStateLoss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab)
    }

    private companion object {
        const val KEY_TAB = "current_tab"
        const val TAB_HOME = "home"
        const val TAB_SETTINGS = "settings"
    }
}
