package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务网格 Adapter（方案 §二/§三）：
 * - stableIds：包名+类名 64 位混合哈希（String.hashCode 32 位有碰撞风险）【二轮修订 P2】
 * - payload 按包名校验刷新；置顶用 notifyItemMoved 精确移动，图片路径与置顶路径均禁 notifyDataSetChanged
 * - Switch 时序纪律：先 setOnCheckedChangeListener(null) → setChecked → 再设回【二轮修订 P1】
 * - 锁按钮/开关为必备控件，宽度不足收缩描述文本；TouchDelegate 多矩形 ≥48dp
 * - 绑定期零回源：只读 IconCache 缓存，miss → 占位符 + 异步补齐
 */
public class ServiceAdapter extends RecyclerView.Adapter<ServiceAdapter.Holder> {

    public static final String PAYLOAD_STATE = "state";
    public static final String PAYLOAD_INFO = "info";

    public interface Callback {
        void onToggle(AccessibilityServiceInfo info, boolean checked);

        void onLockClick(AccessibilityServiceInfo info);

        void onItemClick(AccessibilityServiceInfo info);

        void onItemLongClick(AccessibilityServiceInfo info);

        boolean isTop(String serviceId);

        boolean isLocked(String serviceId);

        /** 定期重启摘要行（如 "每 24 小时"），null 表示未启用 */
        @Nullable
        String restartSummary(String serviceId);
    }

    private final List<AccessibilityServiceInfo> items;
    private final IconCache iconCache;
    private final Callback cb;
    private final Context appContext;
    private final Map<String, Boolean> enabledCache = new HashMap<>();
    private final Set<String> pendingEnable = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private boolean permissionGranted = true;

    public ServiceAdapter(Context context, List<AccessibilityServiceInfo> items, IconCache iconCache, Callback cb) {
        this.appContext = context.getApplicationContext();
        // 【S1】持有副本而非调用方 display 同一引用：否则 setItems 的 clear/addAll 清空的是同一对象 → 列表恒 0 行
        this.items = new ArrayList<>(items);
        this.iconCache = iconCache;
        this.cb = cb;
        setHasStableIds(true);
    }

    /** itemId = 包名+类名拼接的 FNV-1a 64 位哈希 */
    static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public void setItems(List<AccessibilityServiceInfo> newList) {
        items.clear();
        // 【S1】快照入列：构造/入列均为副本，items 不再与调用方列表同引用
        items.addAll(new ArrayList<>(newList));
        notifyDataSetChanged(); // 仅用于列表整体重载（安装列表变化），图片/置顶路径不经过此方法
    }

    /**
     * 【P1】置顶移动：adapter 内部 items 同步移动（add/remove 语义，与 notifyItemMoved 一致）。
     * 本 adapter 自 S1 起持有调用方 display 的副本，置顶路径调用方 sortDisplay 后必须
     * 先经此方法同步 items 再发 notifyItemMoved，保证 stableIds 通知序列与数据一致；
     * 此处不加任何 notify（通知由调用方发）。
     */
    public void moveItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        items.add(to, items.remove(from));
    }

    /** 局部刷新开关/锁定/警示态：仅对状态实际变化的行发 payload */
    public void refreshStates(String settingValue, Set<String> pending, Set<String> failedSet) {
        for (int i = 0; i < items.size(); i++) {
            String id = items.get(i).getId();
            boolean en = isEnabledIn(settingValue, id);
            boolean changed = !Boolean.valueOf(en).equals(enabledCache.get(id))
                    || pending.contains(id) != pendingEnable.contains(id)
                    || failedSet.contains(id) != failed.contains(id);
            enabledCache.put(id, en);
            if (changed) notifyItemChanged(i, PAYLOAD_STATE);
        }
        pendingEnable.clear();
        pendingEnable.addAll(pending);
        failed.clear();
        failed.addAll(failedSet);
    }

    public void setPermissionState(boolean granted) {
        if (permissionGranted == granted) return;
        permissionGranted = granted;
        if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), PAYLOAD_STATE);
    }

    public void setScrollingPaused(boolean paused) {
        iconCache.setPaused(paused);
    }

    /** 滑动停止后恢复预加载：仅补可见行的图标 */
    public void reloadVisibleIcons(RecyclerView rv) {
        if (iconCache.isPaused()) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            Holder h = (Holder) rv.getChildViewHolder(rv.getChildAt(i));
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size()) continue;
            AccessibilityServiceInfo info = items.get(pos);
            IconCache.Entry e = iconCache.peek(info.getId());
            if (e == null || !e.loaded) requestInfo(h, info);
        }
    }

    static boolean isEnabledIn(String settingValue, String serviceId) {
        // 【M1】收口 RestartPrefs 精确匹配（":" 切段精确相等 / 展开形态精确相等），消除 contains 子串误判
        return RestartPrefs.isEnabledIn(settingValue, serviceId);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_service, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        AccessibilityServiceInfo info = items.get(position);
        if (payloads.contains(PAYLOAD_INFO)) bindInfo(holder, info);
        if (payloads.contains(PAYLOAD_STATE)) bindState(holder, info);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final AccessibilityServiceInfo info = items.get(position);
        final String id = info.getId();

        // 启动流水线：轻量数据（服务类短名）同步渲染，图标/标签/描述异步补齐
        holder.tvTitle.setText(IconCache.shortClassName(id));
        holder.tvDesc.setText("");
        IconCache.Entry e = iconCache.peek(id);
        if (e != null && e.loaded) {
            bindInfo(holder, info);
        } else {
            holder.ivIcon.setImageBitmap(iconCache.placeholderFor(id));
            if (!iconCache.isPaused()) requestInfo(holder, info);
        }
        bindState(holder, info);

        holder.itemView.setOnClickListener(v -> cb.onItemClick(info));
        holder.itemView.setOnLongClickListener(v -> {
            cb.onItemLongClick(info);
            return true;
        });
    }

    private void requestInfo(Holder holder, final AccessibilityServiceInfo info) {
        final Holder bound = holder;
        final String id = info.getId();
        iconCache.load(info, () -> {
            // 【盲审修订 P0】回调按包名校验：防置顶/重排/重算 span 后刷错行或越界
            int pos = bound.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size()) return;
            if (!items.get(pos).getId().equals(id)) return;
            notifyItemChanged(pos, PAYLOAD_INFO);
        });
    }

    private void bindInfo(Holder holder, AccessibilityServiceInfo info) {
        String id = info.getId();
        IconCache.Entry e = iconCache.peek(id);
        if (e == null || !e.loaded) return;
        if (e.icon != null) {
            holder.ivIcon.setImageBitmap(e.icon);
        } else if (e.placeholder != null) {
            holder.ivIcon.setImageBitmap(e.placeholder);
        }
        String app = e.appLabel;
        String svc = e.serviceLabel != null ? e.serviceLabel : app;
        if (svc == null) svc = IconCache.shortClassName(id);
        if (app != null && svc != null && !app.equals(svc)) {
            holder.tvTitle.setText(app + "/" + svc);
        } else {
            holder.tvTitle.setText(svc);
        }
        updateDesc(holder, info);
    }

    private void bindState(Holder holder, AccessibilityServiceInfo info) {
        String id = info.getId();
        boolean enabled = Boolean.TRUE.equals(enabledCache.get(id));

        // 【二轮修订 P1】Switch 时序纪律：先摘 listener 再 setChecked 再设回
        holder.sw.setOnCheckedChangeListener(null);
        holder.sw.setChecked(enabled);
        // 【MISSING 14】pendingEnable 非空 = 重启补偿进行中，禁用该行开关防中途变更
        boolean restarting = pendingEnable.contains(id);
        holder.sw.setEnabled(permissionGranted && !restarting);
        holder.sw.setAlpha(permissionGranted ? 1f : 0.4f);
        holder.sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            enabledCache.put(id, isChecked);
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size()) return;
            cb.onToggle(items.get(pos), isChecked);
        });

        boolean locked = cb.isLocked(id);
        // M3 风格矢量锁：锁定=primary 实心，未锁定=弱化开锁
        holder.ibLock.setImageResource(locked ? R.drawable.ic_lock_locked : R.drawable.ic_lock_open);
        holder.ibLock.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        holder.ibLock.setOnClickListener(v -> cb.onLockClick(info));

        boolean top = cb.isTop(id);
        holder.tvTopBadge.setVisibility(top ? View.VISIBLE : View.GONE);
        // 【MISSING 11】"已自动恢复"警示角标：daemon 5 分钟内自动恢复过则复用 tv_badge_fail 切文案
        long restoredAt = appContext.getSharedPreferences("restart", 0)
                .getLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, 0);
        if (restoredAt > 0 && System.currentTimeMillis() - restoredAt <= RestartPrefs.AUTO_RESTORED_WINDOW_MS) {
            holder.tvFailBadge.setText(R.string.service_auto_restored);
            holder.tvFailBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tvFailBadge.setText(R.string.service_restart_failed);
            holder.tvFailBadge.setVisibility(failed.contains(id) ? View.VISIBLE : View.GONE);
        }
        holder.itemView.setActivated(top);
        // 【P1】置顶卡片加深：activated → colorSurfaceContainerHigh，否则 colorSurfaceContainerLow（MaterialColors 取 attr，深浅主题自动跟随）
        ((MaterialCardView) holder.itemView).setCardBackgroundColor(MaterialColors.getColor(holder.itemView, top
                ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                : com.google.android.material.R.attr.colorSurfaceContainerLow));

        updateDesc(holder, info);
    }

    /** 描述行内容优先级：failed 终态 > pendingEnable 过渡态 > 定期重启摘要 > 服务描述（【R5】终态警示优先） */
    private void updateDesc(Holder holder, AccessibilityServiceInfo info) {
        String id = info.getId();
        String desc;
        // 【R5】"恢复失败"为终态警示，优先级高于"重启中…"过渡态
        if (failed.contains(id)) {
            desc = appContext.getString(R.string.service_restart_failed);
        } else if (pendingEnable.contains(id)) {
            desc = appContext.getString(R.string.service_restarting);
        } else {
            String summary = cb.restartSummary(id);
            if (summary != null) {
                desc = summary;
            } else {
                IconCache.Entry e = iconCache.peek(id);
                String d = e != null ? e.description : null;
                desc = (d != null && d.length() > 0) ? d : appContext.getString(R.string.service_no_description);
            }
        }
        holder.tvDesc.setText(desc);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return hash64(items.get(position).getId());
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TouchDelegateLayout touchRoot;
        final ImageView ivIcon;
        final TextView tvTitle;
        final TextView tvDesc;
        final TextView tvTopBadge;
        final TextView tvFailBadge;
        final ImageButton ibLock;
        final MaterialSwitch sw;

        Holder(@NonNull View itemView) {
            super(itemView);
            touchRoot = itemView.findViewById(R.id.touch_root);
            ivIcon = itemView.findViewById(R.id.iv_icon);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvDesc = itemView.findViewById(R.id.tv_desc);
            tvTopBadge = itemView.findViewById(R.id.tv_top_badge);
            tvFailBadge = itemView.findViewById(R.id.tv_badge_fail);
            ibLock = itemView.findViewById(R.id.ib_lock);
            sw = itemView.findViewById(R.id.sw_switch);
            // 锁按钮与开关的命中区扩展至 ≥48dp（一次性注册，避免重复绑定）
            int min48 = Math.round(48 * itemView.getResources().getDisplayMetrics().density);
            touchRoot.addTouchDelegate(ibLock, min48);
            touchRoot.addTouchDelegate(sw, min48);
        }
    }
}
