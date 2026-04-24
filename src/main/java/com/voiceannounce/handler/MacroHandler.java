package com.voiceannounce.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voiceannounce.ToolCall;
import com.voiceannounce.ToolResult;
import com.voiceannounce.VoiceAnnounce;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MacroHandler {

    private MacroHandler() {}

    private static final Map<String, Macro> MACROS = new LinkedHashMap<>();
    private static volatile List<ToolCall> expansion = new ArrayList<>();

    public static void loadFromGameDir(File gameDir) {
        MACROS.clear();
        File f = new File(gameDir, "config/voicecommand_macros.json");
        if (!f.exists()) { writeDefault(f); }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line);
            JsonArray arr = new JsonParser().parse(sb.toString()).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.get("name").getAsString();
                String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                JsonArray steps = obj.getAsJsonArray("steps");
                MACROS.put(name.toLowerCase(), new Macro(name, desc, steps));
            }
            VoiceAnnounce.LOGGER.info("[VoiceAnnounce] Loaded {} macros from {}", MACROS.size(), f);
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to load macros: {}", e.getMessage());
        }
    }

    private static void writeDefault(File f) {
        try {
            f.getParentFile().mkdirs();
            String defaultJson = "[\n" +
                "  {\n" +
                "    \"name\": \"mine routine\",\n" +
                "    \"description\": \"Look down and break the block under you, then step forward.\",\n" +
                "    \"steps\": [\n" +
                "      { \"tool\": \"look\", \"args\": { \"direction\": \"down\" } },\n" +
                "      { \"tool\": \"left_click\", \"args\": { \"action\": \"hold\", \"duration_ms\": 5000 } },\n" +
                "      { \"tool\": \"move\", \"args\": { \"direction\": \"forward\", \"duration_ms\": 300 } }\n" +
                "    ]\n" +
                "  }\n" +
                "]\n";
            java.nio.file.Files.write(f.toPath(), defaultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to write default macros: {}", e.getMessage());
        }
    }

    /** Returns macro names + descriptions for the PlayerState payload. */
    public static JsonArray listForPrompt() {
        JsonArray arr = new JsonArray();
        for (Macro m : MACROS.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", m.name);
            obj.addProperty("description", m.description);
            arr.add(obj);
        }
        return arr;
    }

    /**
     * Instead of calling CommandExecutor recursively, we expand macro steps into a list that the
     * caller (VoiceRecognitionThread) pulls after the turn and prepends to the queue.
     */
    public static ToolResult runMacro(ToolCall call) {
        String name = call.argString("name", "").toLowerCase();
        Macro m = MACROS.get(name);
        if (m == null) return ToolResult.fail("run_macro", "unknown macro: " + name);

        List<ToolCall> steps = new ArrayList<>();
        for (JsonElement el : m.steps) {
            JsonObject obj = el.getAsJsonObject();
            String tool = obj.get("tool").getAsString();
            JsonObject args = obj.has("args") ? obj.getAsJsonObject("args") : new JsonObject();
            steps.add(new ToolCall(tool, args));
        }
        expansion = steps;
        return ToolResult.ok("run_macro", "macro '" + m.name + "' expanded to " + steps.size() + " steps");
    }

    /** Pulled by VoiceRecognitionThread after Gemini returns, to flatten macros into the queue. */
    public static List<ToolCall> drainExpansion() {
        List<ToolCall> e = expansion;
        expansion = new ArrayList<>();
        return e;
    }

    private static final class Macro {
        final String name;
        final String description;
        final JsonArray steps;
        Macro(String n, String d, JsonArray s) { name = n; description = d; steps = s; }
    }
}
