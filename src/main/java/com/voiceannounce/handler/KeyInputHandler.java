package com.voiceannounce.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
public class KeyInputHandler {

    public static final KeyBinding KEY_SPEAK = new KeyBinding(
        "key.voiceannounce.speak",
        Keyboard.KEY_V,
        "key.categories.voiceannounce"
    );

    private boolean wasKeyDown = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.world == null) {
            if (wasKeyDown) {
                VoiceRecognitionThread.stopCapture();
                wasKeyDown = false;
            }
            return;
        }

        boolean isKeyDown;
        if (mc.currentScreen != null) {
            // KeyBinding polling is suspended while a GUI is open (crafting
            // table, chest, inventory, etc). Read the raw keyboard state so
            // the player can still hold V to talk inside menus.
            isKeyDown = Keyboard.isCreated() && Keyboard.isKeyDown(KEY_SPEAK.getKeyCode());
        } else {
            isKeyDown = KEY_SPEAK.isKeyDown();
        }

        if (isKeyDown && !wasKeyDown) {
            VoiceRecognitionThread.startCapture();
        } else if (!isKeyDown && wasKeyDown) {
            VoiceRecognitionThread.stopCapture();
        }

        wasKeyDown = isKeyDown;
    }
}
