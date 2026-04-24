package com.voiceannounce;

import com.google.gson.JsonObject;

public class ToolCall {
    public final String name;
    public final JsonObject args;

    public ToolCall(String name, JsonObject args) {
        this.name = name;
        this.args = args != null ? args : new JsonObject();
    }

    public String argString(String key, String defaultValue) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : defaultValue;
    }

    public int argInt(String key, int defaultValue) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : defaultValue;
    }

    public double argDouble(String key, double defaultValue) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsDouble() : defaultValue;
    }

    public boolean argBool(String key, boolean defaultValue) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsBoolean() : defaultValue;
    }
}
