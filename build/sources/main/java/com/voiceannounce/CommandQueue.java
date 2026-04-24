package com.voiceannounce;

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
    }

    public synchronized boolean isIdle() {
        return current == null && queue.isEmpty();
    }

    /** Called each client tick from the main thread. */
    public synchronized void tick() {
        inputState.tick();

        if (current != null) {
            if (System.currentTimeMillis() < currentReadyAt) return;
            current = null;
        }
        if (queue.isEmpty()) return;

        current = queue.pollFirst();
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

    public synchronized List<ToolResult> drainResults() {
        List<ToolResult> copy = new ArrayList<>(results);
        results.clear();
        return copy;
    }
}
