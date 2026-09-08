package com.accessibilitymanager;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生 TouchDelegate 单父 View 仅支持一个矩形 —— 本容器自定义 dispatchTouchEvent
 * 多矩形分发，将锁形按钮与开关的命中区扩展至 ≥48dp（视觉不变）。方案 §二【二轮修订 P1】。
 */
public class TouchDelegateLayout extends LinearLayout {

    private static class Delegate {
        final View target;
        final int minSizePx;

        Delegate(View target, int minSizePx) {
            this.target = target;
            this.minSizePx = minSizePx;
        }
    }

    private final List<Delegate> delegates = new ArrayList<>();

    public TouchDelegateLayout(Context context) {
        super(context);
    }

    public TouchDelegateLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 将 target 的触摸命中区扩展为以自身中心为中心、边长 ≥ minSizePx 的矩形 */
    public void addTouchDelegate(View target, int minSizePx) {
        delegates.add(new Delegate(target, minSizePx));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        for (int i = 0; i < delegates.size(); i++) {
            Delegate d = delegates.get(i);
            View child = d.target;
            Rect r = new Rect();
            child.getHitRect(r);
            int half = Math.max(d.minSizePx, Math.max(r.width(), r.height())) / 2;
            r.set(r.centerX() - half, r.centerY() - half, r.centerX() + half, r.centerY() + half);
            if (r.contains((int) event.getX(), (int) event.getY())) {
                MotionEvent shifted = MotionEvent.obtain(event);
                shifted.offsetLocation(-child.getLeft(), -child.getTop());
                boolean handled = child.dispatchTouchEvent(shifted);
                shifted.recycle();
                return handled;
            }
        }
        return super.dispatchTouchEvent(event);
    }
}
