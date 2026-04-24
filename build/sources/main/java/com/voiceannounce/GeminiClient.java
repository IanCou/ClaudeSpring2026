package com.voiceannounce;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GeminiClient {

    private static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL
            + ":generateContent";

    private final String apiKey;
    private final ConversationHistory history = new ConversationHistory();
    private final JsonArray toolDeclarations;

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey;
        this.toolDeclarations = ToolSchema.build();
    }

    public ConversationHistory history() {
        return history;
    }

    /**
     * Sends a transcript with current state, returns the tool calls Gemini wants to
     * execute.
     */
    public List<ToolCall> sendTurn(String transcript, JsonObject playerState) throws IOException {
        history.appendUser(transcript, playerState);

        JsonObject req = new JsonObject();
        req.add("systemInstruction", systemInstruction());
        req.add("contents", history.buildContents());
        req.add("tools", toolDeclarations);

        JsonObject resp = post(req);
        List<ToolCall> calls = parseCalls(resp);
        String text = parseText(resp);

        if (!calls.isEmpty()) {
            history.appendModelFunctionCalls(calls);
        } else if (text != null && !text.isEmpty()) {
            history.appendModelText(text);
        }
        return calls;
    }

    public void reportResults(List<ToolResult> results) {
        if (results != null && !results.isEmpty())
            history.appendToolResults(results);
    }

    /**
     * Notes an error without spending another API call — just a side channel in
     * history.
     */
    public void noteSystemMessage(String note) {
        ToolResult r = ToolResult.fail("_system", note);
        history.appendToolResults(Collections.singletonList(r));
    }

    // -----------------------------------------------------------------------

    private JsonObject post(JsonObject body) throws IOException {
        URL url = new URL(ENDPOINT + "?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        java.io.InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
        }
        if (code < 200 || code >= 300) {
            throw new IOException("Gemini HTTP " + code + ": " + sb.toString());
        }
        return new JsonParser().parse(sb.toString()).getAsJsonObject();
    }

    private List<ToolCall> parseCalls(JsonObject resp) {
        List<ToolCall> out = new ArrayList<>();
        if (!resp.has("candidates"))
            return out;
        JsonArray candidates = resp.getAsJsonArray("candidates");
        if (candidates.size() == 0)
            return out;
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts"))
            return out;
        for (JsonElement el : content.getAsJsonArray("parts")) {
            JsonObject part = el.getAsJsonObject();
            if (part.has("functionCall")) {
                JsonObject fc = part.getAsJsonObject("functionCall");
                String name = fc.get("name").getAsString();
                JsonObject args = fc.has("args") && fc.get("args").isJsonObject()
                        ? fc.getAsJsonObject("args")
                        : new JsonObject();
                out.add(new ToolCall(name, args));
            }
        }
        return out;
    }

    private String parseText(JsonObject resp) {
        if (!resp.has("candidates"))
            return null;
        JsonArray candidates = resp.getAsJsonArray("candidates");
        if (candidates.size() == 0)
            return null;
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts"))
            return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : content.getAsJsonArray("parts")) {
            JsonObject part = el.getAsJsonObject();
            if (part.has("text"))
                sb.append(part.get("text").getAsString());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private JsonObject systemInstruction() {
        JsonObject obj = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text",
                "You are a Minecraft voice command interpreter for an accessibility mod. " +
                        "Translate spoken commands into sequences of tool calls using the provided primitives. " +
                        "Rules:\n" +
                        "- You are a sequencer, not a planner. Every step must be fully specified by the player.\n" +
                        "- Reject goal-seeking commands like 'go get wood' — respond with a text explanation instead.\n"
                        +
                        "- Chain up to 5 tool calls per turn. Total movement capped at 20 blocks.\n" +
                        "- Movement directions 'forward/backward/left/right' are relative to player facing. " +
                        "'north/south/east/west' are absolute world axes.\n" +
                        "- Use 1 block ~= 250ms duration for walking; 2.5 blocks/sec sprint.\n" +
                        "- When the player asks for a specific number of blocks broken " +
                        "('break three blocks below me', 'mine 5 down'), call mine_blocks(direction, count). " +
                        "It holds attack until exactly count blocks have been broken (event-counted) and is " +
                        "the only reliable way to honor a count — left_click hold uses time, not blocks. " +
                        "Use direction='down' for blocks below, 'up' for above, 'forward' for one ahead.\n" +
                        "- For ad-hoc mining without a specific count (left_click action=hold), use 5000ms " +
                        "(the per-step cap). Releasing the attack key resets block damage to zero, so " +
                        "always prefer one long hold over multiple short holds. Bare-hand break times: " +
                        "dirt 0.75s, stone 7.5s, oak log 3s, cobblestone 7.5s.\n" +
                        "- Camera control is the player's. Do NOT prepend a `look` step unless the " +
                        "player explicitly asks for it ('look down then mine'), or the requested action " +
                        "names a direction the camera obviously is not facing ('break the block above me' " +
                        "when no direction is implied by current aim). Phrases like 'the block I'm looking " +
                        "at', 'this block', 'in front of my crosshair', 'the one I'm aimed at' mean RESPECT " +
                        "current aim — emit only the action (mine_blocks / left_click), no look. When the " +
                        "player says 'below me' / 'above me' / 'in front of me' as a directional reference, " +
                        "you may include a `look` step, but if the player has clearly already aimed (recent " +
                        "look in the conversation, or 'the block I'm looking at'), skip it.\n" +
                        "- Before calling craft/deposit/withdraw, check the PlayerState inventory and container contents. "
                        +
                        "If the player doesn't have what's needed, reply with text explaining why instead of calling a tool.\n"
                        +
                        "- Macro management is automatic, not opt-in. The player will not say 'save a " +
                        "macro'. You decide. For every multi-step intent the player describes, in the " +
                        "same turn:\n" +
                        "    1. Scan PlayerState.macros. If an existing macro matches the intent " +
                        "(by description, not just exact name — 'dig forward' matches 'tunnel forward'), " +
                        "       call run_macro({name}) and emit nothing else.\n" +
                        "    2. Otherwise call define_macro({name, description, steps}) with a short " +
                        "       lowercase name and the steps you would have emitted directly, then call " +
                        "       run_macro({name}) right after. The flattener registers the macro before " +
                        "       expanding the run_macro, so the action still runs this turn.\n" +
                        "  Do NOT define a macro for trivial one-shot commands ('jump', 'look up', " +
                        "'drop one', 'stop') or ad-hoc one-offs ('walk three blocks left'). Define only " +
                        "  when the named action would plausibly be repeated in future sessions " +
                        "(tunnel/harvest/return-home/etc).\n" +
                        "  Macro step lists must satisfy the same caps as a direct chain (5 steps, " +
                        "  20 blocks total movement, 5000ms per step).\n" +
                        "- Tool results are fed back to you in the next turn; use them to decide next steps.\n" +
                        "- Never invent raw /commands. Never exceed 20 blocks of movement or 5 steps per chain.");
        parts.add(p);
        obj.add("parts", parts);
        return obj;
    }
}
