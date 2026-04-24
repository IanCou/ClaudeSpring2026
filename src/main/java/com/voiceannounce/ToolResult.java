package com.voiceannounce;

public class ToolResult {
    public final String name;
    public final boolean ok;
    public final String message;

    private ToolResult(String name, boolean ok, String message) {
        this.name = name;
        this.ok = ok;
        this.message = message;
    }

    public static ToolResult ok(String name, String message) {
        return new ToolResult(name, true, message);
    }

    public static ToolResult fail(String name, String message) {
        return new ToolResult(name, false, message);
    }
}
