package com.voiceannounce.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voiceannounce.VoiceAnnounce;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VoiceBot-style local phrase dispatch. Every utterance is matched against a
 * regex table built from phrase templates ("mine {count} down", "stop"). On a
 * match, the bound macro or inline steps run immediately — Gemini is bypassed.
 *
 * Phrase templates:
 *   - lowercase, whitespace-flexible
 *   - {name} captures a token, accessible to the macro as ${name}
 *   - first match wins; declare more specific phrases earlier
 *
 * Number-word normalization: "three" -> "3" so {count} captures cleanly.
 */
public final class TriggerMatcher {

    private TriggerMatcher() {}

    private static final List<Trigger> TRIGGERS = new ArrayList<>();
    private static final Map<String, Integer> NUM_WORDS = new HashMap<>();
    static {
        String[] words = {
            "zero","one","two","three","four","five","six","seven","eight","nine",
            "ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen",
            "seventeen","eighteen","nineteen","twenty"
        };
        for (int i = 0; i < words.length; i++) NUM_WORDS.put(words[i], i);
    }

    public static void loadFromGameDir(File gameDir) {
        TRIGGERS.clear();
        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) configDir.mkdirs();
        File f = new File(configDir, "voicecommand_triggers.json");
        if (!f.exists()) writeDefault(f);
        load(f);
    }

    private static void load(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            JsonArray arr = new JsonParser().parse(sb.toString()).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("phrases")) continue;
                JsonArray phrases = obj.getAsJsonArray("phrases");
                List<Pattern> patterns = new ArrayList<>();
                List<String> paramNames = new ArrayList<>();
                String firstPhrase = null;
                for (JsonElement p : phrases) {
                    String phrase = p.getAsString();
                    if (firstPhrase == null) firstPhrase = phrase;
                    Compiled c = compile(phrase);
                    patterns.add(c.pattern);
                    for (String n : c.paramNames) if (!paramNames.contains(n)) paramNames.add(n);
                }
                String macroName = obj.has("macro") ? obj.get("macro").getAsString() : null;
                JsonArray steps = obj.has("steps") ? obj.getAsJsonArray("steps") : null;
                TRIGGERS.add(new Trigger(firstPhrase, patterns, paramNames, macroName, steps));
            }
            VoiceAnnounce.LOGGER.info("[VoiceAnnounce] Loaded {} phrase triggers from {}",
                TRIGGERS.size(), f.getName());
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to load triggers: {}", e.getMessage());
        }
    }

    /** Returns a Match if the transcript hits any trigger, else null. */
    public static Match match(String transcript) {
        if (transcript == null || transcript.isEmpty()) return null;
        String norm = normalize(transcript);
        for (Trigger t : TRIGGERS) {
            for (Pattern p : t.patterns) {
                Matcher m = p.matcher(norm);
                if (m.matches()) {
                    Map<String, String> params = new HashMap<>();
                    for (String name : t.paramNames) {
                        try {
                            String v = m.group(name);
                            if (v != null) params.put(name, v);
                        } catch (IllegalArgumentException ignored) {}
                    }
                    return new Match(t, params);
                }
            }
        }
        return null;
    }

    private static String normalize(String s) {
        String lower = s.toLowerCase().trim();
        // strip terminal punctuation
        lower = lower.replaceAll("^[\\p{Punct}\\s]+", "")
                     .replaceAll("[\\p{Punct}\\s]+$", "");
        // collapse internal punctuation that whisper sometimes emits
        lower = lower.replaceAll("[,.!?]", " ").replaceAll("\\s+", " ").trim();
        // word -> digit
        String[] toks = lower.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toks.length; i++) {
            Integer n = NUM_WORDS.get(toks[i]);
            if (i > 0) sb.append(' ');
            sb.append(n != null ? n.toString() : toks[i]);
        }
        return sb.toString();
    }

    private static Compiled compile(String phrase) {
        String norm = phrase.toLowerCase().trim();
        // Escape regex metachars except {}
        StringBuilder esc = new StringBuilder();
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (c == '{' || c == '}') { esc.append(c); continue; }
            if ("\\.^$|?*+()[]".indexOf(c) >= 0) esc.append('\\');
            esc.append(c);
        }
        // Replace {name} with named capture.
        List<String> names = new ArrayList<>();
        Pattern paramPat = Pattern.compile("\\{(\\w+)\\}");
        Matcher m = paramPat.matcher(esc.toString());
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            names.add(name);
            m.appendReplacement(out, "(?<" + name + ">[\\\\w-]+)");
        }
        m.appendTail(out);
        // Whitespace tolerance
        String rx = out.toString().replaceAll("\\s+", "\\\\s+");
        return new Compiled(Pattern.compile("^" + rx + "$", Pattern.CASE_INSENSITIVE), names);
    }

    private static final class Compiled {
        final Pattern pattern;
        final List<String> paramNames;
        Compiled(Pattern p, List<String> n) { pattern = p; paramNames = n; }
    }

    public static final class Trigger {
        public final String firstPhrase;
        public final List<Pattern> patterns;
        public final List<String> paramNames;
        public final String macroName;     // null if inline
        public final JsonArray inlineSteps; // null if macro
        Trigger(String firstPhrase, List<Pattern> p, List<String> n, String macro, JsonArray steps) {
            this.firstPhrase = firstPhrase;
            this.patterns = Collections.unmodifiableList(p);
            this.paramNames = Collections.unmodifiableList(n);
            this.macroName = macro;
            this.inlineSteps = steps;
        }
    }

    public static final class Match {
        public final Trigger trigger;
        public final Map<String, String> params;
        Match(Trigger t, Map<String, String> p) { trigger = t; params = p; }
    }

    private static void writeDefault(File f) {
        try {
            f.getParentFile().mkdirs();
            // Triggers are intentionally single-primitive only — no camera +
            // action composition. Anything that needs orientation, sequencing,
            // or counting goes through Gemini, which can also persist a
            // multi-step routine via define_macro.
            String json = "[\n" +
                "  { \"phrases\": [\"jump\", \"hop\"],\n" +
                "    \"steps\": [{ \"tool\": \"jump\", \"args\": {} }] },\n" +
                "\n" +
                "  { \"phrases\": [\"stop\", \"halt\", \"freeze\", \"stop everything\"],\n" +
                "    \"steps\": [{ \"tool\": \"stop\", \"args\": {} }] },\n" +
                "\n" +
                "  { \"phrases\": [\"look down\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"down\" } }] },\n" +
                "  { \"phrases\": [\"look up\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"up\" } }] },\n" +
                "  { \"phrases\": [\"look forward\", \"look ahead\", \"face forward\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"forward\" } }] },\n" +
                "  { \"phrases\": [\"look north\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"north\" } }] },\n" +
                "  { \"phrases\": [\"look south\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"south\" } }] },\n" +
                "  { \"phrases\": [\"look east\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"east\" } }] },\n" +
                "  { \"phrases\": [\"look west\"],\n" +
                "    \"steps\": [{ \"tool\": \"look\", \"args\": { \"direction\": \"west\" } }] },\n" +
                "\n" +
                "  { \"phrases\": [\"open inventory\", \"inventory\"],\n" +
                "    \"steps\": [{ \"tool\": \"open_inventory\", \"args\": {} }] },\n" +
                "  { \"phrases\": [\"close\", \"close inventory\", \"close container\"],\n" +
                "    \"steps\": [{ \"tool\": \"close_container\", \"args\": {} }] },\n" +
                "\n" +
                "  { \"phrases\": [\"swap hands\", \"swap\"],\n" +
                "    \"steps\": [{ \"tool\": \"swap_hands\", \"args\": {} }] },\n" +
                "\n" +
                "  { \"phrases\": [\"drop\", \"drop one\"],\n" +
                "    \"steps\": [{ \"tool\": \"drop\", \"args\": { \"whole_stack\": false } }] },\n" +
                "  { \"phrases\": [\"drop stack\", \"drop the stack\", \"drop everything\"],\n" +
                "    \"steps\": [{ \"tool\": \"drop\", \"args\": { \"whole_stack\": true } }] },\n" +
                "\n" +
                "  { \"phrases\": [\"select slot {n}\", \"slot {n}\", \"hotbar {n}\"],\n" +
                "    \"steps\": [{ \"tool\": \"select_slot\", \"args\": { \"slot\": \"${n}\" } }] },\n" +
                "\n" +
                "  { \"phrases\": [\"sneak\", \"crouch\"],\n" +
                "    \"steps\": [{ \"tool\": \"sneak\", \"args\": { \"action\": \"toggle\" } }] },\n" +
                "  { \"phrases\": [\"sprint\"],\n" +
                "    \"steps\": [{ \"tool\": \"sprint\", \"args\": { \"action\": \"toggle\" } }] },\n" +
                "\n" +
                "  { \"phrases\": [\"respawn\"],\n" +
                "    \"steps\": [{ \"tool\": \"respawn\", \"args\": {} }] },\n" +
                "\n" +
                "  { \"phrases\": [\n" +
                "      \"break the block i'm looking at\",\n" +
                "      \"break the block im looking at\",\n" +
                "      \"break this block\",\n" +
                "      \"mine this block\",\n" +
                "      \"mine the block i'm looking at\",\n" +
                "      \"mine the block im looking at\"\n" +
                "    ],\n" +
                "    \"steps\": [{ \"tool\": \"mine_blocks\", \"args\": { \"count\": 1 } }] }\n" +
                "]\n";
            Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Failed to write default triggers: {}", e.getMessage());
        }
    }

    /** Test helper for dev diagnostics. */
    public static List<String> phraseList() {
        List<String> out = new ArrayList<>();
        for (Trigger t : TRIGGERS) out.add(t.firstPhrase);
        return Collections.unmodifiableList(out);
    }

    /** Convenience for callers that want to know how many triggers loaded. */
    public static int size() { return TRIGGERS.size(); }
}
