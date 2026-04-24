package com.voiceannounce;

import com.voiceannounce.handler.MineBlocksHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Queues tool calls and executes them sequentially on the game tick.
 * For movement steps with duration_ms, waits until the hold expires before advancing.
 */
public class CommandQueue {

    private final Deque<ToolCall> queue = new ArrayDeque<>();
    private final List<ToolResult> results = new ArrayList<>();
    private final InputState inputState;

    private ToolCall current;
    private long currentReadyAt;

    public CommandQueue(InputState inputState) {
        this.inputState = inputState;
    }

    public synchronized void submit(List<ToolCall> chain) {
        for (ToolCall c : chain) queue.add(c);
    }

    public synchronized void clear() {
        queue.clear();
        current = null;
        currentReadyAt = 0;
        if (MineBlocksHandler.isActive()) MineBlocksHandler.abort(inputState);
    }

    public synchronized boolean isIdle() {
        return current == null && queue.isEmpty();
    }

    /** Called each client tick from the main thread. */
    public synchronized void tick() {
        inputState.tick();

        // mine_blocks runs asynchronously — gate the queue until the handler reports done.
        if (current != null && current.name.equals("mine_blocks")) {
            if (MineBlocksHandler.isComplete()) {
                int got = MineBlocksHandler.finish(inputState);
                int want = MineBlocksHandler.targetCount();
                results.add(got >= want
                    ? ToolResult.ok("mine_blocks", "broke " + got + " block(s)")
                    : ToolResult.fail("mine_blocks", "broke " + got + " of " + want + " before timeout"));
                current = null;
            } else {
                return;
            }
        }

        if (current != null) {
            if (System.currentTimeMillis() < currentReadyAt) return;
            current = null;
        }
        if (queue.isEmpty()) return;

        current = queue.pollFirst();

        if (current.name.equals("mine_blocks")) {
            startMineBlocks(current);
            return;
        }

        ToolResult r = CommandExecutor.execute(current, inputState);
        results.add(r);

        // Timed steps wait for the hold to finish before popping the next.
        int dur = current.argInt("duration_ms", 0);
        if (dur > 0 && (current.name.equals("move") || current.name.equals("left_click")
                || current.name.equals("right_click"))) {
            currentReadyAt = System.currentTimeMillis() + dur + 40;
        } else {
            currentReadyAt = System.currentTimeMillis() + 60; // small spacing
        }
    }

    private void startMineBlocks(ToolCall call) {
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) {
            results.add(ToolResult.fail("mine_blocks", "no player"));
            current = null;
            return;
        }
        String dir = call.argString("direction", "");
        switch (dir) {
            case "up":       p.rotationPitch = -85f; break;
            case "down":     p.rotationPitch = 85f; break;
            case "forward":  p.rotationPitch = 0f; break;
            default: /* unset / unknown — leave camera alone */ break;
        }
        int count = Math.max(1, Math.min(20, call.argInt("count", 1)));
        MineBlocksHandler.start(count, inputState);
        currentReadyAt = Long.MAX_VALUE; // controlled by handler completion
    }

    public synchronized List<ToolResult> drainResults() {
        List<ToolResult> copy = new ArrayList<>(results);
        results.clear();
        return copy;
    }
}
