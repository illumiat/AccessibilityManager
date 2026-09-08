package com.accessibilitymanager;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** 8dp 网格间距（配合 RecyclerView 4dp padding，相邻卡片净间距 8dp）。方案 §二 */
public class GridSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int spacePx;

    public GridSpacingDecoration(int spacePx) {
        this.spacePx = spacePx;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        outRect.set(spacePx / 2, spacePx / 2, spacePx / 2, spacePx / 2);
    }
}
