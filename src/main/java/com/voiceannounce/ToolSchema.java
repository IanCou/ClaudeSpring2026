package com.voiceannounce;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builds the Gemini function declarations for all voice-command primitives. */
final class ToolSchema {

    private ToolSchema() {}

    static JsonArray build() {
        JsonArray functions = new JsonArray();

        functions.add(fn("move",
            "Walk in a direction for up to duration_ms milliseconds.",
            new String[]{"direction"},
            prop("direction", enumStr("forward", "backward", "left", "right", "north", "south", "east", "west")),
            prop("duration_ms", intProp("How long to hold the movement key, max 5000."))));

        functions.add(fn("look",
            "Rotate the player camera to face a direction.",
            new String[]{"direction"},
            prop("direction", enumStr("forward", "backward", "left", "right", "north", "south", "east", "west", "up", "down"))));

        functions.add(fn("left_click",
            "Simulate the attack/mine button.",
            new String[]{"action"},
            prop("action", enumStr("press", "hold", "release")),
            prop("duration_ms", intProp("For hold, how long to hold (max 5000)."))));

        functions.add(fn("right_click",
            "Simulate the use/place/interact button. Also opens containers.",
            new String[]{"action"},
            prop("action", enumStr("press", "hold", "release")),
            prop("duration_ms", intProp("For hold, how long to hold (max 5000)."))));

        functions.add(fn("jump", "Jump once.", new String[0]));

        functions.add(fn("sneak",
            "Toggle sneak/crouch.",
            new String[0],
            prop("action", enumStr("toggle", "press", "release"))));

        functions.add(fn("sprint",
            "Toggle sprint.",
            new String[0],
            prop("action", enumStr("toggle", "press", "release"))));

        functions.add(fn("stop", "Release all movement and input keys immediately.", new String[0]));

        functions.add(fn("select_slot",
            "Switch to hotbar slot 1-9.",
            new String[]{"slot"},
            prop("slot", intProp("Hotbar slot (1-9)."))));

        functions.add(fn("swap_hands", "Swap main hand and offhand items.", new String[0]));

        functions.add(fn("drop",
            "Drop held item.",
            new String[0],
            prop("whole_stack", boolProp("Drop entire stack if true, else just one."))));

        functions.add(fn("open_inventory", "Open or close the player inventory.", new String[0]));

        functions.add(fn("close_container", "Close any open container UI (escape).", new String[0]));

        functions.add(fn("craft",
            "Craft an item directly (bypasses UI). Requires a crafting table within 4.5 blocks for 3x3 recipes.",
            new String[]{"item"},
            prop("item", stringProp("Registry name of item to craft, e.g. minecraft:stone_pickaxe.")),
            prop("quantity", intProp("How many to craft (default 1)."))));

        functions.add(fn("deposit",
            "Deposit items into a nearby open container.",
            new String[0],
            prop("item", stringProp("Registry name; omit for filter=all or filter=held.")),
            prop("filter", enumStr("matching", "held", "all", "excess"))));

        functions.add(fn("withdraw",
            "Withdraw items from a nearby open container.",
            new String[]{"item"},
            prop("item", stringProp("Registry name of item to withdraw.")),
            prop("count", intProp("How many to withdraw (default all)."))));

        functions.add(fn("respawn", "Click the respawn button (only if dead).", new String[0]));

        functions.add(fn("run_macro",
            "Run a pre-defined macro sequence by name. See PlayerState.macros for available names.",
            new String[]{"name"},
            prop("name", stringProp("The macro name to run."))));

        JsonObject wrapper = new JsonObject();
        wrapper.add("functionDeclarations", functions);
        JsonArray tools = new JsonArray();
        tools.add(wrapper);
        return tools;
    }

    // ----- builder helpers -----

    private static JsonObject fn(String name, String desc, String[] required, JsonObject... propPairs) {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", desc);

        JsonObject params = new JsonObject();
        params.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        for (JsonObject pair : propPairs) {
            String key = pair.get("__key").getAsString();
            pair.remove("__key");
            properties.add(key, pair);
        }
        params.add("properties", properties);

        if (required != null && required.length > 0) {
            JsonArray reqArr = new JsonArray();
            for (String r : required) reqArr.add(r);
            params.add("required", reqArr);
        }
        fn.add("parameters", params);
        return fn;
    }

    private static JsonObject prop(String key, JsonObject schema) {
        schema.addProperty("__key", key);
        return schema;
    }

    private static JsonObject enumStr(String... values) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        s.add("enum", arr);
        return s;
    }

    private static JsonObject stringProp(String desc) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.addProperty("description", desc);
        return s;
    }

    private static JsonObject intProp(String desc) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "integer");
        s.addProperty("description", desc);
        return s;
    }

    private static JsonObject boolProp(String desc) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "boolean");
        s.addProperty("description", desc);
        return s;
    }
}
