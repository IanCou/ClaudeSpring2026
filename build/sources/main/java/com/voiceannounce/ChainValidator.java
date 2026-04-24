package com.voiceannounce;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChainValidator {

    private ChainValidator() {}

    private static final int MAX_STEPS = 5;
    private static final int MAX_TOTAL_MOVEMENT_MS = 20 * 250; // ~20 blocks
    private static final int MAX_DURATION_PER_STEP_MS = 5000;

    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
        "move", "look", "left_click", "right_click", "jump", "sneak", "sprint",
        "stop", "select_slot", "swap_hands", "drop", "open_inventory",
        "close_container", "craft", "deposit", "withdraw", "respawn",
        "mine_blocks", "run_macro", "define_macro"
    ));

    public static Set<String> allowedTools() {
        return Collections.unmodifiableSet(ALLOWED);
    }

    public static Result validate(List<ToolCall> chain) {
        if (chain == null || chain.isEmpty()) return Result.ok();
        if (chain.size() > MAX_STEPS) {
            return Result.fail("rejected — chain exceeds " + MAX_STEPS + " step limit");
        }

        int totalMoveMs = 0;
        for (ToolCall c : chain) {
            if (!ALLOWED.contains(c.name)) {
                return Result.fail("rejected — unknown tool: " + c.name);
            }
            int dur = c.argInt("duration_ms", 0);
            if (dur > MAX_DURATION_PER_STEP_MS) {
                return Result.fail("rejected — duration_ms exceeds " + MAX_DURATION_PER_STEP_MS + " cap");
            }
            if (c.name.equals("move")) {
                totalMoveMs += Math.max(250, dur == 0 ? 250 : dur);
            }
        }
        if (totalMoveMs > MAX_TOTAL_MOVEMENT_MS) {
            return Result.fail("rejected — chain exceeds 20 block movement limit");
        }
        return Result.ok();
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        private Result(boolean ok, String error) { this.ok = ok; this.error = error; }
        static Result ok() { return new Result(true, null); }
        static Result fail(String e) { return new Result(false, e); }
    }
}
