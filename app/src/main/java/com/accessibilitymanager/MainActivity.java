package com.accessibilitymanager;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 单 Activity 容器：主页 / 设置双 Fragment show/hide 切换（保留列表滚动位置与状态）。
 * 【二轮修订 P0】FragmentManager 不保存可见性 —— onSaveInstanceState 持久化当前 tab，
 * 旋转/进程重建后按存档重新 show/hide 并同步底栏高亮。
 */
public class MainActivity extends AppCompatActivity {

    private static final String KEY_TAB = "current_tab";
    private static final String TAG_HOME = "home";
    private static final String TAG_SETTINGS = "settings";

    private BottomNavigationView bottomNav;
    private int currentTab = 0;
    private boolean suppressNavCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(KEY_TAB, 0);
        }

        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) return true;
            int id = item.getItemId();
            if (id == R.id.nav_home) showTab(0);
            else if (id == R.id.nav_settings) showTab(1);
            return true;
        });

        showTab(currentTab);
    }

    private void showTab(int index) {
        currentTab = index;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        Fragment home = fm.findFragmentByTag(TAG_HOME);
        Fragment settings = fm.findFragmentByTag(TAG_SETTINGS);
        Fragment toShow = index == 0 ? home : settings;
        Fragment toHide = index == 0 ? settings : home;
        if (toShow == null) {
            toShow = index == 0 ? new HomeFragment() : new SettingsFragment();
            tx.add(R.id.fragment_container, toShow, index == 0 ? TAG_HOME : TAG_SETTINGS);
        } else {
            tx.show(toShow);
        }
        if (toHide != null) tx.hide(toHide);
        tx.commitNowAllowingStateLoss();

        syncBottomNav();
    }

    private void syncBottomNav() {
        int target = currentTab == 0 ? R.id.nav_home : R.id.nav_settings;
        if (bottomNav.getSelectedItemId() != target) {
            suppressNavCallback = true;
            bottomNav.setSelectedItemId(target);
            suppressNavCallback = false;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_TAB, currentTab);
    }
}
