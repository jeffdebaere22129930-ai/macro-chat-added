package com.primitive;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the player's inputs once per client tick and plays them back.
 *
 * Recorded per tick: movement keys, jump, sneak, sprint, attack, use, drop, swap-hands,
 * pick-block, the selected hotbar slot and the look direction (yaw / pitch).
 * Not recorded: inventory clicks, chat, anything done inside a screen.
 */
public final class MacroEngine {
    private MacroEngine() {}

    public enum State { IDLE, RECORDING, PLAYING }

    public static State state = State.IDLE;

    /** Key order used in the bitmask. Indexes below HOLD_KEYS are "held" keys, the rest are click-style. */
    private static final int HOLD_KEYS = 7;
    private static final int MAX_TICKS = 72_000; // 1 hour safety cap

    // ---- recording buffers
    private static final List<Integer> rKeys = new ArrayList<>();
    private static final List<Float> rYaw = new ArrayList<>();
    private static final List<Float> rPitch = new ArrayList<>();
    private static final List<Integer> rSlot = new ArrayList<>();
    private static final List<Integer> rChatTick = new ArrayList<>();
    private static final List<String> rChatText = new ArrayList<>();

    // ---- playback state
    private static Macro current;
    private static int idx;
    private static int prevMask;
    private static int chatPos;
    private static float yawOff;
    private static float pitchOff;

    public static int recordedTicks() { return rKeys.size(); }
    public static int playIndex() { return idx; }
    public static Macro playing() { return state == State.PLAYING ? current : null; }

    private static KeyBinding[] inputs(MinecraftClient mc) {
        GameOptions o = mc.options;
        return new KeyBinding[]{
                o.forwardKey, o.backKey, o.leftKey, o.rightKey, o.jumpKey, o.sneakKey, o.sprintKey,
                o.attackKey, o.useKey, o.dropKey, o.swapHandsKey, o.pickItemKey
        };
    }

    private static void toast(MinecraftClient mc, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("Primitive \u00bb " + msg).formatted(Formatting.GREEN), true);
        }
    }

    /** Sets a key down/up, also working for "toggle sneak / toggle sprint" keybinds. */
    private static void force(KeyBinding kb, boolean down) {
        if (kb.isPressed() == down) return;
        kb.setPressed(down);
        if (kb.isPressed() != down) kb.setPressed(true); // toggle-style binds only flip on a press
    }

    // ================================================================ tick hooks

    public static void startTick(MinecraftClient mc) {
        if (mc.player == null) {
            if (state == State.RECORDING) stopRecording(mc, true);
            else if (state == State.PLAYING) stopPlayback(mc, null);
            return;
        }

        if (state == State.RECORDING) {
            if (mc.currentScreen == null) capture(mc); // time spent in menus is not recorded
            if (rKeys.size() >= MAX_TICKS) stopRecording(mc, true);
        } else if (state == State.PLAYING) {
            if (mc.currentScreen != null) {
                stopPlayback(mc, "Macro stopped");
                return;
            }
            apply(mc);
        }
    }

    public static void endTick(MinecraftClient mc) {
        // advance playback first, so a macro started this tick begins on frame 0
        if (state == State.PLAYING && mc.player != null && current != null) {
            idx++;
            if (idx >= current.length()) {
                if (Store.settings.loop) {
                    idx = 0;
                    chatPos = 0;
                } else {
                    stopPlayback(mc, "Macro finished");
                }
            }
            if (state == State.PLAYING) aim(mc, idx);
        }

        if (mc.player == null) return;

        while (Keys.OPEN_GUI.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new PrimitiveScreen());
        }
        while (Keys.RECORD.wasPressed()) {
            if (chord(Keys.RECORD_MOD)) toggleRecording(mc);
        }
        while (Keys.PLAY.wasPressed()) {
            if (chord(Keys.PLAY_MOD)) togglePlayback(mc);
        }
    }

    /** The optional 2nd key: if it is unbound only the main key is needed, otherwise it must be held. */
    private static boolean chord(KeyBinding modifier) {
        return modifier.isUnbound() || modifier.isPressed();
    }

    // ================================================================ chat

    /** Called by Fabric when the player sends a chat message. */
    public static void onChat(String message) {
        if (state == State.RECORDING && message != null && !message.isBlank()) {
            rChatTick.add(rKeys.size());
            rChatText.add(message);
        }
    }

    /** Called by Fabric when the player sends a command (text comes without the leading slash). */
    public static void onCommand(String command) {
        if (state == State.RECORDING && command != null && !command.isBlank()) {
            rChatTick.add(rKeys.size());
            rChatText.add("/" + command);
        }
    }

    private static void sendChat(MinecraftClient mc, String text) {
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (mc.player == null || net == null) return;
        if (text.startsWith("/")) net.sendChatCommand(text.substring(1));
        else net.sendChatMessage(text);
    }

    // ================================================================ recording

    public static void toggleRecording(MinecraftClient mc) {
        if (state == State.RECORDING) stopRecording(mc, true);
        else startRecording(mc);
    }

    private static void startRecording(MinecraftClient mc) {
        if (mc.player == null) return;
        stopPlayback(mc, null);
        clearBuffers();
        state = State.RECORDING;
        toast(mc, "Recording... press the record key again to stop");
    }

    private static void stopRecording(MinecraftClient mc, boolean save) {
        if (state != State.RECORDING) return;
        state = State.IDLE;

        // a macro that is only chat still needs a couple of (idle) frames to play on
        while (save && !rChatText.isEmpty() && rKeys.size() < 2 && mc.player != null) {
            rKeys.add(0);
            rYaw.add(mc.player.getYaw());
            rPitch.add(mc.player.getPitch());
            rSlot.add(mc.player.getInventory().getSelectedSlot());
        }

        int n = rKeys.size();
        if (save && n >= 2) {
            Macro m = new Macro();
            m.name = Store.nextDefaultName();
            m.keys = new int[n];
            m.yaw = new float[n];
            m.pitch = new float[n];
            m.slot = new byte[n];
            for (int i = 0; i < n; i++) {
                m.keys[i] = rKeys.get(i);
                m.yaw[i] = rYaw.get(i);
                m.pitch[i] = rPitch.get(i);
                m.slot[i] = rSlot.get(i).byteValue();
            }
            int c = rChatText.size();
            m.chatTick = new int[c];
            m.chatText = new String[c];
            for (int i = 0; i < c; i++) {
                m.chatTick[i] = Math.min(rChatTick.get(i), n - 1); // messages sent after the last tick go on the last tick
                m.chatText[i] = rChatText.get(i);
            }
            Store.add(m);
            toast(mc, "Saved \"" + m.name + "\" (" + m.duration() + (c > 0 ? ", " + c + " chat" : "") + ")");
        } else {
            toast(mc, "Nothing recorded");
        }
        clearBuffers();
    }

    private static void clearBuffers() {
        rKeys.clear();
        rYaw.clear();
        rPitch.clear();
        rSlot.clear();
        rChatTick.clear();
        rChatText.clear();
    }

    private static void capture(MinecraftClient mc) {
        KeyBinding[] ks = inputs(mc);
        int mask = 0;
        for (int i = 0; i < ks.length; i++) {
            if (ks[i].isPressed()) mask |= 1 << i;
        }
        rKeys.add(mask);
        rYaw.add(mc.player.getYaw());
        rPitch.add(mc.player.getPitch());
        rSlot.add(mc.player.getInventory().getSelectedSlot());
    }

    // ================================================================ playback

    public static void togglePlayback(MinecraftClient mc) {
        if (state == State.PLAYING) {
            stopPlayback(mc, "Macro stopped");
        } else if (state == State.RECORDING) {
            toast(mc, "Stop recording first");
        } else {
            play(Store.selected());
        }
    }

    public static void play(Macro m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || state == State.RECORDING) return;
        if (m == null || m.length() == 0) {
            toast(mc, "No macro selected - open the GUI and pick one");
            return;
        }

        current = m;
        idx = 0;
        prevMask = 0;
        chatPos = 0;
        if (Store.settings.relativeAim) {
            yawOff = mc.player.getYaw() - m.yaw[0];
            pitchOff = mc.player.getPitch() - m.pitch[0];
        } else {
            yawOff = 0;
            pitchOff = 0;
        }
        aim(mc, 0);
        state = State.PLAYING;
        toast(mc, "Playing \"" + m.name + "\"" + (Store.settings.loop ? " (loop)" : ""));
    }

    public static void stopPlayback(MinecraftClient mc, String message) {
        if (state != State.PLAYING) return;
        for (KeyBinding k : inputs(mc)) force(k, false);
        state = State.IDLE;
        current = null;
        idx = 0;
        prevMask = 0;
        if (message != null) toast(mc, message);
    }

    /** Applies the keys + hotbar slot of the current frame. Runs at the START of a tick, before movement. */
    private static void apply(MinecraftClient mc) {
        Macro m = current;
        if (m == null || idx >= m.length()) return;

        KeyBinding[] ks = inputs(mc);
        int mask = m.keys[idx];
        for (int b = 0; b < ks.length; b++) {
            boolean now = ((mask >> b) & 1) != 0;
            boolean was = ((prevMask >> b) & 1) != 0;
            force(ks[b], now);
            // attack / use / drop / swap / pick need a real "click" event, not just a held state
            if (b >= HOLD_KEYS && now && !was) {
                KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(ks[b]));
            }
        }
        prevMask = mask;

        while (chatPos < m.chatText.length && m.chatTick[chatPos] <= idx) {
            sendChat(mc, m.chatText[chatPos]);
            chatPos++;
        }

        int slot = m.slot[idx];
        PlayerInventory inv = mc.player.getInventory();
        if (slot >= 0 && slot < 9 && inv.getSelectedSlot() != slot) {
            inv.setSelectedSlot(slot);
        }
    }

    /**
     * Sets the look direction for frame i. Done at the END of the previous tick so the camera
     * interpolates smoothly between frames instead of snapping.
     */
    private static void aim(MinecraftClient mc, int i) {
        Macro m = current;
        if (m == null || mc.player == null || i < 0 || i >= m.length()) return;
        float p = Math.max(-90f, Math.min(90f, m.pitch[i] + pitchOff));
        mc.player.setYaw(m.yaw[i] + yawOff);
        mc.player.setPitch(p);
    }
}
