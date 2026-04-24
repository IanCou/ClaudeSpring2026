package com.voiceannounce;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Rolling conversation history in the Gemini contents format.
 * Capped at MAX_TURNS (user + model pairs). Oldest turns drop first.
 */
public class ConversationHistory {

    private static final int MAX_TURNS = 10;

    // Each element is one Gemini content object: {role, parts:[...]}
    private final Deque<JsonObject> messages = new ArrayDeque<>();

    public synchronized void appendUser(String transcript, JsonObject playerState) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        StringBuilder sb = new StringBuilder();
        sb.append("Player said: \"").append(transcript).append("\"\n\n");
        sb.append("Current state:\n").append(playerState.toString());
        textPart.addProperty("text", sb.toString());
        parts.add(textPart);
        msg.add("parts", parts);
        messages.add(msg);
        trim();
    }

    public synchronized void appendModelFunctionCalls(List<ToolCall> calls) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "model");
        JsonArray parts = new JsonArray();
        for (ToolCall c : calls) {
            JsonObject part = new JsonObject();
            JsonObject fc = new JsonObject();
            fc.addProperty("name", c.name);
            fc.add("args", c.args);
            part.add("functionCall", fc);
            parts.add(part);
        }
        msg.add("parts", parts);
        messages.add(msg);
        trim();
    }

    public synchronized void appendModelText(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "model");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        msg.add("parts", parts);
        messages.add(msg);
        trim();
    }

    public synchronized void appendToolResults(List<ToolResult> results) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        for (ToolResult r : results) {
            JsonObject part = new JsonObject();
            JsonObject fr = new JsonObject();
            fr.addProperty("name", r.name);
            JsonObject response = new JsonObject();
            response.addProperty("status", r.ok ? "ok" : "error");
            response.addProperty("message", r.message);
            fr.add("response", response);
            part.add("functionResponse", fr);
            parts.add(part);
        }
        msg.add("parts", parts);
        messages.add(msg);
        trim();
    }

    public synchronized JsonArray buildContents() {
        JsonArray arr = new JsonArray();
        for (JsonObject m : messages) arr.add(m);
        return arr;
    }

    public synchronized void clear() {
        messages.clear();
    }

    private void trim() {
        // One turn = one user + one model message pair. MAX_TURNS * 2 raw messages.
        // We also have tool-result user messages between turns, so keep ~3x cap.
        int hardCap = MAX_TURNS * 3;
        while (messages.size() > hardCap) {
            messages.pollFirst();
        }
    }
}
