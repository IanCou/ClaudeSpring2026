package com.voiceannounce.handler;

import com.voiceannounce.ToolCall;
import com.voiceannounce.ToolResult;

public final class StorageHandler {
    private StorageHandler() {}

    public static ToolResult deposit(ToolCall call) {
        return ToolResult.fail("deposit", "not yet implemented");
    }

    public static ToolResult withdraw(ToolCall call) {
        return ToolResult.fail("withdraw", "not yet implemented");
    }
}
