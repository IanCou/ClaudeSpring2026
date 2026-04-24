package com.voiceannounce.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.voiceannounce.ChainValidator;
import com.voiceannounce.ToolCall;
import com.voiceannounce.ToolResult;
import com.voiceannounce.VoiceAnnounce;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads macros from two sources, both under .minecraft/config/:
 *   voicecommand_macros.json  — static {tool, args} sequences (simple, no logic).
 *   voicecommand_macros.js    — Nashorn-evaluated scripts that can branch on PlayerState.
 *
 * In both cases the result is a List&lt;ToolCall&gt; spliced into the chain before
 * ChainValidator runs, so the 5-step / 20-block / 5000ms-per-step caps still apply.
 */
public final class MacroHandler {

    private MacroHandler() {}

    private static final Map<String, JsonMacro> JSON_MACROS = new LinkedHashMap<>();
    private static ScriptEngine jsEngine;
    private static File jsonMacroFile;

    private static final String JS_RUNTIME =
        "var __macros = {};\n" +
        "function registerMacro(m) {\n" +
        "  if (!m || !m.name) throw new Error('macro requires a name');\n" +
        "  __macros[m.name.toLowerCase()] = m;\n" +
        "}\n" +
        "function tool(name, args) { return { tool: name, args: args || {} }; }\n" +
        "function __expandMacro(name, stateJson, paramsJson) {\n" +
        "  var m = __macros[name.toLowerCase()];\n" +
        "  if (!m || typeof m.build !== 'function') return null;\n" +
        "  var state;\n" +
        "  try { state = JSON.parse(stateJson); } catch (e) { state = {}; }\n" +
        "  var params;\n" +
        "  try { params = paramsJson ? JSON.parse(paramsJson) : {}; } catch (e) { params = {}; }\n" +
        "  var out = m.build(state, params);\n" +
        "  return Array.isArray(out) ? out : [];\n" +
        "}\n" +
        "function __listMacros() {\n" +
        "  var arr = [];\n" +
        "  for (var k in __macros) {\n" +
        "    arr.push({ name: __macros[k].name, description: __macros[k].description || '' });\n" +
        "  }\n" +
        "  return arr;\n" +
        "}\n";

    public static void loadFromGameDir(File gameDir) {
        JSON_MACROS.clear();
        jsEngine = null;

        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) configDir.mkdirs();

        File jsonFile = new File(configDir, "voicecommand_macros.json");
        jsonMacroFile = jsonFile;
        if (!jsonFile.exists()) writeDefaultJson(jsonFile);
        loadJson(jsonFile);

        File jsFile = new File(configDir, "voicecommand_macros.js");
        if (!jsFile.exists()) writeDefaultJs(jsFile);
        loadJs(jsFile);
    }

    private static void loadJson(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line);
            JsonArray arr = new JsonParser().parse(sb.toString()).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.get("name").getAsString();
                String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                JsonArray steps = obj.getAsJsonArray("steps");
                JSON_MACROS.put(name.toLowerCase(), new JsonMacro(name, desc, steps));
            }
            VoiceAnnounce.LOGGER.info("[VoiceAnnounce] Loaded {} JSON macros from {}", JSON_MACROS.size(), f.getName());
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to load JSON macros: {}", e.getMessage());
        }
    }

    private static void loadJs(File f) {
        ScriptEngine engine = resolveEngine();
        if (engine == null) {
            VoiceAnnounce.LOGGER.warn("[VoiceAnnounce] Nashorn JS engine not available; JS macros disabled.");
            return;
        }
        try {
            engine.eval(JS_RUNTIME);
            String userScript = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            engine.eval(userScript);
            jsEngine = engine;
            int count = 0;
            try {
                Object listed = ((Invocable) engine).invokeFunction("__listMacros");
                if (listed instanceof Bindings) count = readArrayLength((Bindings) listed);
            } catch (Exception ignored) {}
            VoiceAnnounce.LOGGER.info("[VoiceAnnounce] Loaded {} JS macros from {}", count, f.getName());
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to load JS macros: {}", e.getMessage());
            jsEngine = null;
        }
    }

    private static ScriptEngine resolveEngine() {
        // First try the standard service lookup with the bootstrap loader (Forge's
        // classloader can hide META-INF/services entries from the default lookup).
        try {
            ScriptEngine eng = new ScriptEngineManager(null).getEngineByName("nashorn");
            if (eng != null) return eng;
        } catch (Throwable ignored) {}
        // Fallback: instantiate Nashorn directly.
        try {
            Class<?> cls = Class.forName("jdk.nashorn.api.scripting.NashornScriptEngineFactory");
            Object factory = cls.newInstance();
            return (ScriptEngine) cls.getMethod("getScriptEngine").invoke(factory);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns macro {name, description} pairs to inject into PlayerState for Gemini. */
    public static JsonArray listForPrompt() {
        JsonArray arr = new JsonArray();
        for (JsonMacro m : JSON_MACROS.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", m.name);
            obj.addProperty("description", m.description);
            arr.add(obj);
        }
        if (jsEngine != null) {
            try {
                Object listed = ((Invocable) jsEngine).invokeFunction("__listMacros");
                if (listed instanceof Bindings) {
                    Bindings b = (Bindings) listed;
                    int len = readArrayLength(b);
                    for (int i = 0; i < len; i++) {
                        Object item = b.get(String.valueOf(i));
                        if (!(item instanceof Bindings)) continue;
                        Bindings entry = (Bindings) item;
                        JsonObject obj = new JsonObject();
                        obj.addProperty("name", String.valueOf(entry.get("name")));
                        Object d = entry.get("description");
                        obj.addProperty("description", (d != null ? d.toString() : "") + " (script)");
                        arr.add(obj);
                    }
                }
            } catch (Exception ignored) {}
        }
        return arr;
    }

    /**
     * Resolves a macro by name and returns its expanded steps for the supplied
     * state. Returns null if no macro by that name is registered.
     */
    public static List<ToolCall> expandMacro(String name, JsonObject state) {
        return expandMacro(name, state, null);
    }

    /**
     * Same as expandMacro but with a captured-token map (from TriggerMatcher).
     * Tokens are accessible to JS macros as the second argument of build(state, params),
     * and to JSON macro steps via "${name}" string substitution in args values.
     */
    public static List<ToolCall> expandMacro(String name, JsonObject state, Map<String, String> params) {
        if (name == null) return null;
        String key = name.toLowerCase().trim();

        JsonMacro json = JSON_MACROS.get(key);
        if (json != null) return convertJsonSteps(json.steps, params);

        if (jsEngine != null) {
            try {
                String paramsJson = paramsToJson(params);
                Object result = ((Invocable) jsEngine).invokeFunction(
                    "__expandMacro", name, state == null ? "{}" : state.toString(), paramsJson);
                if (result == null) return null;
                return convertJsResult(result);
            } catch (Exception e) {
                VoiceAnnounce.LOGGER.error("[VoiceAnnounce] JS macro '{}' threw: {}", name, e.getMessage());
                return new ArrayList<>();
            }
        }
        return null;
    }

    private static String paramsToJson(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "{}";
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> e : params.entrySet()) {
            obj.addProperty(e.getKey(), e.getValue());
        }
        return obj.toString();
    }

    /**
     * Legacy entry point kept so a stray run_macro reaching the queue degrades
     * gracefully; in normal flow, run_macro is replaced by its expansion in
     * VoiceRecognitionThread before validation.
     */
    public static ToolResult runMacro(ToolCall call) {
        return ToolResult.ok("run_macro", "noop (expansion happens pre-queue)");
    }

    /**
     * Registers a new macro from a Gemini-issued define_macro call.
     * Validates each step's tool name against ChainValidator's allowed set
     * (minus define_macro itself, to avoid recursion). Persists the full
     * JSON_MACROS map back to voicecommand_macros.json.
     */
    public static ToolResult defineMacro(ToolCall call) {
        String name = call.argString("name", "").trim();
        if (name.isEmpty()) return ToolResult.fail("define_macro", "missing name");

        String description = call.argString("description", "");

        JsonElement stepsEl = call.args.get("steps");
        if (stepsEl == null || !stepsEl.isJsonArray()) {
            return ToolResult.fail("define_macro", "missing or non-array steps");
        }
        JsonArray stepsArr = stepsEl.getAsJsonArray();
        if (stepsArr.size() == 0) {
            return ToolResult.fail("define_macro", "steps cannot be empty");
        }

        java.util.Set<String> allowed = new java.util.HashSet<>(ChainValidator.allowedTools());
        allowed.remove("define_macro");

        JsonArray normalized = new JsonArray();
        for (JsonElement el : stepsEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                return ToolResult.fail("define_macro", "each step must be an object");
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("tool")) {
                return ToolResult.fail("define_macro", "step missing 'tool'");
            }
            String tool = obj.get("tool").getAsString();
            if (!allowed.contains(tool)) {
                return ToolResult.fail("define_macro", "unknown tool in step: " + tool);
            }
            JsonObject step = new JsonObject();
            step.addProperty("tool", tool);
            JsonElement args = obj.get("args");
            step.add("args", args != null && args.isJsonObject() ? args : new JsonObject());
            normalized.add(step);
        }

        JSON_MACROS.put(name.toLowerCase(), new JsonMacro(name, description, normalized));

        try {
            persistJsonMacros();
        } catch (Exception e) {
            return ToolResult.fail("define_macro",
                "registered in memory but persistence failed: " + e.getMessage());
        }
        return ToolResult.ok("define_macro",
            "macro '" + name + "' saved with " + normalized.size() + " steps");
    }

    private static void persistJsonMacros() throws java.io.IOException {
        if (jsonMacroFile == null) return;
        JsonArray arr = new JsonArray();
        for (JsonMacro m : JSON_MACROS.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", m.name);
            obj.addProperty("description", m.description);
            obj.add("steps", m.steps);
            arr.add(obj);
        }
        Files.write(jsonMacroFile.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ----- conversion helpers -----

    private static List<ToolCall> convertJsonSteps(JsonArray steps) {
        return convertJsonSteps(steps, null);
    }

    public static List<ToolCall> convertJsonSteps(JsonArray steps, Map<String, String> params) {
        List<ToolCall> out = new ArrayList<>();
        if (steps == null) return out;
        boolean withParams = params != null && !params.isEmpty();
        for (JsonElement el : steps) {
            JsonObject obj = el.getAsJsonObject();
            String tool = obj.get("tool").getAsString();
            JsonObject src = obj.has("args") && obj.get("args").isJsonObject()
                ? obj.getAsJsonObject("args") : new JsonObject();
            JsonObject args = withParams ? cloneObject(src) : src;
            if (withParams) substituteParams(args, params);
            out.add(new ToolCall(tool, args));
        }
        return out;
    }

    private static JsonObject cloneObject(JsonObject src) {
        return new JsonParser().parse(src.toString()).getAsJsonObject();
    }

    private static final java.util.regex.Pattern TEMPLATE = java.util.regex.Pattern.compile("^\\$\\{(\\w+)\\}$");

    private static void substituteParams(JsonObject obj, Map<String, String> params) {
        for (Map.Entry<String, JsonElement> e : new ArrayList<>(obj.entrySet())) {
            JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                substituteParams(v.getAsJsonObject(), params);
                continue;
            }
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) continue;
            String s = v.getAsString();
            java.util.regex.Matcher m = TEMPLATE.matcher(s);
            if (!m.matches()) continue;
            String name = m.group(1);
            String val = params.get(name);
            if (val == null) continue;
            try { obj.addProperty(e.getKey(), Long.parseLong(val)); continue; } catch (Exception ignored) {}
            try { obj.addProperty(e.getKey(), Double.parseDouble(val)); continue; } catch (Exception ignored) {}
            obj.addProperty(e.getKey(), val);
        }
    }

    private static List<ToolCall> convertJsResult(Object jsArray) {
        List<ToolCall> out = new ArrayList<>();
        if (!(jsArray instanceof Bindings)) return out;
        Bindings b = (Bindings) jsArray;
        int len = readArrayLength(b);
        for (int i = 0; i < len; i++) {
            Object item = b.get(String.valueOf(i));
            if (!(item instanceof Bindings)) continue;
            Bindings step = (Bindings) item;
            Object toolName = step.get("tool");
            if (toolName == null) continue;
            JsonElement argsEl = valueToJson(step.get("args"));
            JsonObject args = argsEl != null && argsEl.isJsonObject() ? argsEl.getAsJsonObject() : new JsonObject();
            out.add(new ToolCall(String.valueOf(toolName), args));
        }
        return out;
    }

    private static int readArrayLength(Bindings b) {
        Object lenObj = b.get("length");
        if (lenObj instanceof Number) return ((Number) lenObj).intValue();
        return b.size();
    }

    private static JsonElement valueToJson(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof Number) {
            Number n = (Number) v;
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < Long.MAX_VALUE) {
                return new JsonPrimitive(n.longValue());
            }
            return new JsonPrimitive(d);
        }
        if (v instanceof Boolean) return new JsonPrimitive((Boolean) v);
        if (v instanceof Bindings) {
            Bindings b = (Bindings) v;
            Object lenObj = b.get("length");
            if (lenObj instanceof Number) {
                JsonArray arr = new JsonArray();
                int len = ((Number) lenObj).intValue();
                for (int i = 0; i < len; i++) arr.add(valueToJson(b.get(String.valueOf(i))));
                return arr;
            }
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Object> e : b.entrySet()) {
                obj.add(e.getKey(), valueToJson(e.getValue()));
            }
            return obj;
        }
        return new JsonPrimitive(v.toString());
    }

    // ----- defaults -----

    private static void writeDefaultJson(File f) {
        try {
            f.getParentFile().mkdirs();
            // Empty by design. Multi-step routines are Gemini's job: it composes
            // them on demand and persists reusable ones via define_macro.
            String defaultJson = "[]\n";
            Files.write(f.toPath(), defaultJson.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to write default macros: {}", e.getMessage());
        }
    }

    private static void writeDefaultJs(File f) {
        try {
            f.getParentFile().mkdirs();
            String defaultJs =
                "// Voice Command — scripted macros (Nashorn / ES5).\n" +
                "//\n" +
                "// API:\n" +
                "//   registerMacro({\n" +
                "//     name: 'macro name',\n" +
                "//     description: 'shown to the model so it knows what the macro does',\n" +
                "//     build: function(state) { return [ tool(...), tool(...) ]; }\n" +
                "//   });\n" +
                "//   tool(name, argsObject) — wraps a tool call.\n" +
                "//\n" +
                "// `state` is the same PlayerState the model sees. Useful fields:\n" +
                "//   state.heldItem               (registry name string, or 'empty')\n" +
                "//   state.inventory.hotbar       (array of {slot,item,count})\n" +
                "//   state.inventory.main         (array of {slot,item,count})\n" +
                "//   state.position.{x,y,z}\n" +
                "//   state.facing                 (yaw degrees)\n" +
                "//   state.health, state.hunger\n" +
                "//   state.nearbyContainer        (object or undefined)\n" +
                "//\n" +
                "// Returned steps still pass through ChainValidator: max 5 steps,\n" +
                "// max 20 blocks total movement, max 5000 ms per step.\n" +
                "//\n" +
                "// ----------\n" +
                "\n" +
                "registerMacro({\n" +
                "  name: 'tower up',\n" +
                "  description: 'Jump and place a block beneath you in one motion. Aim straight " +
                "down and hold a placeable block before invoking — the macro does NOT move the " +
                "camera. Repeat to keep towering.',\n" +
                "  build: function(state) {\n" +
                "    return [\n" +
                "      tool('jump', {}),\n" +
                "      tool('right_click', { action: 'press' })\n" +
                "    ];\n" +
                "  }\n" +
                "});\n" +
                "\n" +
                "registerMacro({\n" +
                "  name: 'safe drop stack',\n" +
                "  description: 'Drop the held stack only if it is not a tool (pickaxe/sword/axe/shovel).',\n" +
                "  build: function(state) {\n" +
                "    var held = (state && state.heldItem) || '';\n" +
                "    var protect = ['pickaxe', 'sword', 'axe', 'shovel'];\n" +
                "    for (var i = 0; i < protect.length; i++) {\n" +
                "      if (held.indexOf(protect[i]) !== -1) return [];\n" +
                "    }\n" +
                "    return [tool('drop', { whole_stack: true })];\n" +
                "  }\n" +
                "});\n" +
                "\n" +
                "registerMacro({\n" +
                "  name: 'pick best pick',\n" +
                "  description: 'Switch hotbar to the best pickaxe in the hotbar (diamond > iron > stone > wood).',\n" +
                "  build: function(state) {\n" +
                "    var ranking = ['diamond_pickaxe', 'iron_pickaxe', 'stone_pickaxe', 'wooden_pickaxe'];\n" +
                "    var hotbar = (state && state.inventory && state.inventory.hotbar) || [];\n" +
                "    for (var r = 0; r < ranking.length; r++) {\n" +
                "      for (var i = 0; i < hotbar.length; i++) {\n" +
                "        var s = hotbar[i];\n" +
                "        if (s && s.item && s.item.indexOf(ranking[r]) !== -1) {\n" +
                "          return [tool('select_slot', { slot: s.slot + 1 })];\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    return [];\n" +
                "  }\n" +
                "});\n";
            Files.write(f.toPath(), defaultJs.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to write default JS macros: {}", e.getMessage());
        }
    }

    private static final class JsonMacro {
        final String name;
        final String description;
        final JsonArray steps;
        JsonMacro(String n, String d, JsonArray s) { name = n; description = d; steps = s; }
    }
}
