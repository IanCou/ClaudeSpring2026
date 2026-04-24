package com.voiceannounce.handler;

import com.voiceannounce.CommandQueue;
import com.voiceannounce.GeminiClient;
import com.voiceannounce.ToolResult;
import com.voiceannounce.VoiceAnnounce;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

@SideOnly(Side.CLIENT)
public class GameTickHandler {

    private final CommandQueue queue;
    private boolean wasIdle = true;

    public GameTickHandler(CommandQueue queue) {
        this.queue = queue;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getMinecraft().player == null) return;

        queue.tick();

        // When the queue transitions from busy → idle, report accumulated results to Gemini
        boolean idleNow = queue.isIdle();
        if (idleNow && !wasIdle) {
            List<ToolResult> results = queue.drainResults();
            GeminiClient client = VoiceAnnounce.getClient();
            if (client != null && !results.isEmpty()) {
                client.reportResults(results);
            }
        }
        wasIdle = idleNow;
    }
}
