package com.voiceannounce;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tracks keys currently held by the executor along with their automatic release time.
 * Must be ticked every client tick on the main thread.
 */
public class InputState {

    private final Map<KeyBinding, Long> heldUntil = new HashMap<>();

    public void hold(KeyBinding kb, long durationMs) {
        if (kb == null) return;
        KeyBinding.setKeyBindState(kb.getKeyCode(), true);
        if (durationMs > 0) {
            heldUntil.put(kb, System.currentTimeMillis() + durationMs);
        } else {
            heldUntil.put(kb, Long.MAX_VALUE);
        }
    }

    public void release(KeyBinding kb) {
        if (kb == null) return;
        KeyBinding.setKeyBindState(kb.getKeyCode(), false);
        heldUntil.remove(kb);
    }

    public void press(KeyBinding kb) {
        if (kb == null) return;
        KeyBinding.setKeyBindState(kb.getKeyCode(), true);
        KeyBinding.onTick(kb.getKeyCode());
        // Schedule release on next tick
        heldUntil.put(kb, System.currentTimeMillis() + 60);
    }

    public boolean isHeld(KeyBinding kb) {
        return heldUntil.containsKey(kb);
    }

    /** Called from the game tick — auto-releases expired held keys. */
    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<KeyBinding, Long>> it = heldUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<KeyBinding, Long> e = it.next();
            if (now >= e.getValue()) {
                KeyBinding.setKeyBindState(e.getKey().getKeyCode(), false);
                it.remove();
            }
        }
    }

    public void releaseAll() {
        for (KeyBinding kb : heldUntil.keySet()) {
            KeyBinding.setKeyBindState(kb.getKeyCode(), false);
        }
        heldUntil.clear();
    }

    // ----- Convenience: well-known bindings -----

    public static KeyBinding forward() { return Minecraft.getMinecraft().gameSettings.keyBindForward; }
    public static KeyBinding back()    { return Minecraft.getMinecraft().gameSettings.keyBindBack; }
    public static KeyBinding left()    { return Minecraft.getMinecraft().gameSettings.keyBindLeft; }
    public static KeyBinding right()   { return Minecraft.getMinecraft().gameSettings.keyBindRight; }
    public static KeyBinding jump()    { return Minecraft.getMinecraft().gameSettings.keyBindJump; }
    public static KeyBinding sneak()   { return Minecraft.getMinecraft().gameSettings.keyBindSneak; }
    public static KeyBinding sprint()  { return Minecraft.getMinecraft().gameSettings.keyBindSprint; }
    public static KeyBinding attack()  { return Minecraft.getMinecraft().gameSettings.keyBindAttack; }
    public static KeyBinding useItem() { return Minecraft.getMinecraft().gameSettings.keyBindUseItem; }
    public static KeyBinding inv()     { return Minecraft.getMinecraft().gameSettings.keyBindInventory; }
    public static KeyBinding drop()    { return Minecraft.getMinecraft().gameSettings.keyBindDrop; }
    public static KeyBinding swap()    { return Minecraft.getMinecraft().gameSettings.keyBindSwapHands; }
}
