package com.limelight.binding.input.virtual_controller;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.KeyboardPacket;

/**
 * 把虚拟手柄的按键位映射成**键盘事件**。
 *
 * <p>为什么需要它（MineHermes M5）：
 * Minecraft Java 版**只接受键盘**（WASD/空格）驱动角色，不认识手柄输入。
 * 而 Artemis 的虚拟手柄发的是 {@link ControllerPacket} 手柄按键码，
 * 所以直接用它按方向键，游戏里角色不会动。
 *
 * <p>解法：在虚拟手柄状态提交时做一次"改道"——把选定的手柄位
 * 翻译成键盘的按下/抬起事件。这样：
 * <ul>
 *   <li>不需要 mod，不需要主机侧转换</li>
 *   <li>正常手柄逻辑（物理手柄、摇杆、扳机）完全不受影响</li>
 *   <li>键盘通路本就在用（Game.java 处理真实键盘输入），复用同一条通道</li>
 * </ul>
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>边沿检测</b>：只在按键状态"变化"时发事件，不是每帧都发</li>
 *   <li><b>可用 {@link #releaseAll()} 兜底</b>：隐藏虚拟手柄 / 断开 / 暂停时调用，
 *       避免"按键卡住"（松开事件丢失会让角色一直走）</li>
 *   <li><b>keyMap 由 {@link KeyboardTranslator#translate} 生成</b>：
 *       不自己拼 0x80 前缀，避免主机侧译码错误</li>
 * </ol>
 *
 * <p>线程：只在主线程被调用（虚拟手柄的输入回调都在 UI 线程）。
 */
public class VirtualKeyMapper {

    /** 参与映射的手柄位掩码（只有这些位会转成键盘） */
    private static final int MAPPED_MASK =
            ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG |
            ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
            ControllerPacket.A_FLAG;

    private final KeyboardTranslator translator;
    private final NvConnection conn;

    /** 上一次的输入位图（用于边沿检测），只记录 MAPPED_MASK 内的位 */
    private int lastInputMap = 0;

    public VirtualKeyMapper(KeyboardTranslator translator, NvConnection conn) {
        this.translator = translator;
        this.conn = conn;
    }

    /**
     * 处理一次虚拟手柄状态变化。
     *
     * @param inputMap 虚拟手柄的按键位图（{@link ControllerPacket} 的 *_FLAG）
     * @param modifier 修饰键状态（目前固定传 0；虚拟按键暂不发修饰键）
     */
    public void process(int inputMap, byte modifier) {
        if (conn == null || translator == null) {
            return;
        }

        final int current = inputMap & MAPPED_MASK;
        final int changed = current ^ lastInputMap;
        if (changed == 0) {
            // 无变化，不发任何事件（这是避免刷屏与卡键的关键）
            return;
        }

        if ((changed & ControllerPacket.UP_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_W,
                    (current & ControllerPacket.UP_FLAG) != 0, modifier);
        }
        if ((changed & ControllerPacket.DOWN_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_S,
                    (current & ControllerPacket.DOWN_FLAG) != 0, modifier);
        }
        if ((changed & ControllerPacket.LEFT_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_A,
                    (current & ControllerPacket.LEFT_FLAG) != 0, modifier);
        }
        if ((changed & ControllerPacket.RIGHT_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_D,
                    (current & ControllerPacket.RIGHT_FLAG) != 0, modifier);
        }
        if ((changed & ControllerPacket.A_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_SPACE,
                    (current & ControllerPacket.A_FLAG) != 0, modifier);
        }

        lastInputMap = current;
    }

    /**
     * 释放所有已映射的按键。用于隐藏虚拟手柄、断开连接、Activity 暂停/销毁。
     * 不做这一步的风险：松开事件丢失 → 角色一直往前走。
     */
    public void releaseAll(byte modifier) {
        if (conn == null || translator == null || lastInputMap == 0) {
            lastInputMap = 0;
            return;
        }
        if ((lastInputMap & ControllerPacket.UP_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_W, false, modifier);
        }
        if ((lastInputMap & ControllerPacket.DOWN_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_S, false, modifier);
        }
        if ((lastInputMap & ControllerPacket.LEFT_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_A, false, modifier);
        }
        if ((lastInputMap & ControllerPacket.RIGHT_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_D, false, modifier);
        }
        if ((lastInputMap & ControllerPacket.A_FLAG) != 0) {
            sendKey(android.view.KeyEvent.KEYCODE_SPACE, false, modifier);
        }
        lastInputMap = 0;
    }

    private void sendKey(int androidKeyCode, boolean down, byte modifier) {
        // 复用 KeyboardTranslator，得到符合 GFE 协议的 keyMap（含 0x80 前缀）
        short keyMap = translator.translate(androidKeyCode, -1);
        if (keyMap == 0) {
            return;   // 未映射的键，直接忽略
        }
        conn.sendKeyboardInput(keyMap,
                down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP,
                modifier, (byte) 0);
    }
}
