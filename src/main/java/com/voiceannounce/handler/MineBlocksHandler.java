package com.voiceannounce.handler;

import com.voiceannounce.InputState;
import com.voiceannounce.VoiceAnnounce;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts player block-break events. The CommandQueue uses this to back a
 * mine_blocks(direction, count) primitive that holds the attack key until
 * exactly count blocks have been broken (or a 30 second safety timeout).
 *
 * BlockEvent.BreakEvent fires on the integrated server thread in SP; the
 * counter is atomic so the client tick can read it cleanly.
 */
@SideOnly(Side.CLIENT)
public class MineBlocksHandler {

    private static final long SAFETY_TIMEOUT_MS = 30_000L;

    private static final AtomicInteger broken = new AtomicInteger(0);
    private static volatile boolean active = false;
    private static volatile int target = 0;
    private static volatile long startTime = 0L;

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent e) {
        if (!active) return;
        EntityPlayerSP me = Minecraft.getMinecraft().player;
        if (me == null) return;
        if (e.getPlayer() == null) return;
        if (!e.getPlayer().getUniqueID().equals(me.getUniqueID())) return;
        broken.incrementAndGet();
    }

    public static void start(int count, InputState input) {
        broken.set(0);
        target = Math.max(1, count);
        active = true;
        startTime = System.currentTimeMillis();
        input.hold(InputState.attack(), SAFETY_TIMEOUT_MS);
    }

    public static boolean isComplete() {
        if (!active) return true;
        if (broken.get() >= target) return true;
        return System.currentTimeMillis() - startTime > SAFETY_TIMEOUT_MS;
    }

    public static int finish(InputState input) {
        active = false;
        input.release(InputState.attack());
        return broken.get();
    }

    public static void abort(InputState input) {
        if (!active) return;
        VoiceAnnounce.LOGGER.info("[VoiceAnnounce] mine_blocks aborted at {}/{}", broken.get(), target);
        finish(input);
    }

    public static int targetCount() { return target; }
    public static boolean isActive() { return active; }
}
