package com.limelight.binding.input.touch;

import android.view.KeyEvent;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

/**
 * 快捷栏热区叠层（Selene / MineHermes）。
 *
 * <p>解决的问题：手机触屏无法像鼠标那样精确点中物品栏格子。
 * 本类在触摸事件入口处拦截"起点落在热栏格子上"的触摸，
 * 抬手时若未移动，则发送对应数字键（1-9）切换槽位。
 *
 * <p>坐标从哪来（重要）：
 * <ul>
 *   <li>热区位置由 <b>Minecraft 的布局公式</b> 算出，不是写死的</li>
 *   <li>依赖两个输入：<b>视频流分辨率</b> 与 <b>GUI 缩放</b>（本 mod 固定为 2）</li>
 *   <li>算出的归一化坐标再乘 <b>StreamView 的像素尺寸</b>，得到屏幕坐标</li>
 * </ul>
 * 因此不同分辨率/比例下自动适配，不需要向服务端请求坐标。
 *
 * <p>公式（GUI 像素）：
 * <pre>
 *   guiW = videoW / GUI_SCALE
 *   guiH = videoH / GUI_SCALE
 *   热栏左上 = ((guiW - 182) / 2,  guiH - 22)
 *   槽 i 左上 = 热栏左上 + (3 + i*20, 3)，尺寸 16x16
 * </pre>
 *
 * <p>手势规则（与 `手势分类模型.md` 一致）：
 * <ul>
 *   <li>起点在热区内 + 移动 &lt; 阈值 → 点击 → 切槽位</li>
 *   <li>起点在热区内 + 移动 ≥ 阈值 → 取消（不发任何输入，避免误触）</li>
 *   <li>起点在热区外 → 完全不干预，走原版逻辑</li>
 * </ul>
 */
public class HotbarOverlay {

    // ---- Minecraft GUI 布局常量（GUI 像素，写死源码里）----
    // GUI 缩放不再写死：从 Selene 设置（Minecraft GUI Scale）读取，因为高分辨率平板上
    // 缩放 2 会让物品栏过小。见 PreferenceConfiguration.guiScale。
    private static final int HOTBAR_W = 182;
    private static final int HOTBAR_H = 22;
    private static final int SLOT_INSET = 3;
    private static final int SLOT_SIZE = 16;
    private static final int SLOT_SPACING = 20;
    private static final int SLOT_COUNT = 9;

    private final NvConnection conn;
    private final KeyboardTranslator translator;
    private final float tapMoveThresholdPx;
    /** Minecraft GUI 缩放（1-6）：热区坐标依赖它，可运行时更新 */
    private volatile int guiScale = 2;

    // ---- 手势状态 ----
    private int pendingSlot = -1;
    private float downX;
    private float downY;
    private int missLogCount = 0;

    public HotbarOverlay(NvConnection conn, KeyboardTranslator translator, float tapMoveThresholdPx) {
        this.conn = conn;
        this.translator = translator;
        this.tapMoveThresholdPx = tapMoveThresholdPx;
    }

    /**
     * 更新 GUI 缩放（事件接口）。
     * <p>当前由 Game 在创建时传入；将来可通过协议/设置变更时随时调用。
     */
    public void setGuiScale(int guiScale) {
        if (guiScale >= 1 && guiScale <= 6) {
            this.guiScale = guiScale;
        }
    }

    public int getGuiScale() {
        return guiScale;
    }

    /**
     * 命中测试。坐标均为相对 StreamView 的像素。
     *
     * @return 0..8 表示命中的槽位；-1 表示未命中
     */
    public int hitTest(float x, float y, int viewW, int viewH, int videoW, int videoH) {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) {
            return -1;
        }

        // ---- 防御性预判（重要）----
        // 热栏永远在屏幕最底部，所以先用一个宽松的粗筛：
        // y 必须落在屏幕下方 25% 区域内，否则直接判为未命中。
        // 这样即使分辨率参数有误，也绝不会吞掉屏幕中部的拖拽（转视角）。
        if (y < viewH * 0.75f || y > viewH || x < 0 || x > viewW) {
            return -1;
        }

        // GUI 空间下的热栏几何
        final int guiW = Math.max(1, (int) Math.ceil((double) videoW / guiScale));
        final int guiH = Math.max(1, (int) Math.ceil((double) videoH / guiScale));
        final int hotbarLeft = (guiW - HOTBAR_W) / 2;
        final int hotbarTop = guiH - HOTBAR_H;

        // 归一化 → 屏幕像素
        final float scaleX = (float) viewW / guiW;
        final float scaleY = (float) viewH / guiH;

        for (int i = 0; i < SLOT_COUNT; i++) {
            float left = (hotbarLeft + SLOT_INSET + i * SLOT_SPACING) * scaleX;
            float top = (hotbarTop + SLOT_INSET) * scaleY;
            float right = left + SLOT_SIZE * scaleX;
            float bottom = top + SLOT_SIZE * scaleY;

            if (x >= left && x < right && y >= top && y < bottom) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 触摸按下。
     *
     * @return true 表示本类消费该事件（不再传给原版输入处理）
     */
    public boolean onTouchDown(float x, float y, int viewW, int viewH, int videoW, int videoH) {
        int slot = hitTest(x, y, viewW, viewH, videoW, videoH);
        if (slot < 0) {
            pendingSlot = -1;
            if (missLogCount < 5) {
                missLogCount++;
                com.limelight.LimeLog.info("HotbarOverlay: MISS xy=(" + (int) x + "," + (int) y + ")"
                        + " view=" + viewW + "x" + viewH
                        + " video=" + videoW + "x" + videoH);
            }
            return false;
        }
        pendingSlot = slot;
        downX = x;
        downY = y;
        com.limelight.LimeLog.info("HotbarOverlay: HIT slot=" + slot
                + " xy=(" + (int) x + "," + (int) y + ")"
                + " view=" + viewW + "x" + viewH
                + " video=" + videoW + "x" + videoH);
        return true;
    }

    /**
     * 触摸移动。
     *
     * @return true 表示消费（起点在热区内时，整个手势都由我们接管）
     */
    public boolean onTouchMove(float x, float y) {
        if (pendingSlot < 0) {
            return false;
        }
        float dx = x - downX;
        float dy = y - downY;
        if (dx * dx + dy * dy > tapMoveThresholdPx * tapMoveThresholdPx) {
            // 移动超过阈值：判定为拖拽，取消点击（仍消费，避免发出半截鼠标事件）
            pendingSlot = -1;
        }
        return true;
    }

    /**
     * 触摸抬起。若仍是点击，则切槽位。
     *
     * @return true 表示消费
     */
    public boolean onTouchUp(float x, float y) {
        if (pendingSlot < 0) {
            return false;
        }
        int slot = pendingSlot;
        pendingSlot = -1;
        sendHotbarKey(slot);
        return true;
    }

    /** 取消当前手势（如系统取消触摸） */
    public void cancel() {
        pendingSlot = -1;
    }

    /** 发送数字键 1..9 切换热栏槽位 */
    private void sendHotbarKey(int slot) {
        if (conn == null || translator == null || slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        // Android 的 KEYCODE_1..KEYCODE_9 是连续的
        int keyCode = KeyEvent.KEYCODE_1 + slot;
        short keyMap = translator.translate(keyCode, -1);
        if (keyMap == 0) {
            return;
        }
        conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
    }
}
