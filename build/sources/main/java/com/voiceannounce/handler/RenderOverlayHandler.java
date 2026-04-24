package com.voiceannounce.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class RenderOverlayHandler {

    public enum State { IDLE, LISTENING, TRANSCRIBING }

    private static volatile State state = State.IDLE;

    public static void setState(State s) { state = s; }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (state == State.IDLE) return;

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        ScaledResolution sr = new ScaledResolution(mc);

        String label;
        if (state == State.LISTENING) {
            boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
            String dot = blink ? "\u00a7c" : "\u00a77";
            label = dot + "\u25cf \u00a7fListening\u2026";
        } else {
            // TRANSCRIBING — solid yellow dot
            label = "\u00a7e\u25cf \u00a7fTranscribing\u2026";
        }

        int x = sr.getScaledWidth() - font.getStringWidth(label) - 6;
        int y = 6;
        int p = 3;
        net.minecraft.client.gui.Gui.drawRect(
            x - p, y - p,
            x + font.getStringWidth(label) + p, y + font.FONT_HEIGHT + p,
            0xAA_000000
        );
        font.drawString(label, x, y, 0xFFFFFF);
    }
}
