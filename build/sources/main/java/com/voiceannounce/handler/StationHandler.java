package com.voiceannounce.handler;

import com.voiceannounce.ToolCall;
import com.voiceannounce.ToolResult;

public final class StationHandler {
    private StationHandler() {}

    public static ToolResult useFurnace(ToolCall call) {
        return ToolResult.fail("use_furnace", "not yet implemented");
    }

    public static ToolResult useAnvil(ToolCall call) {
        return ToolResult.fail("use_anvil", "not yet implemented");
    }

    public static ToolResult useEnchantingTable(ToolCall call) {
        return ToolResult.fail("use_enchanting_table", "not yet implemented");
    }

    public static ToolResult useBrewingStand(ToolCall call) {
        return ToolResult.fail("use_brewing_stand", "not yet implemented");
    }
}
