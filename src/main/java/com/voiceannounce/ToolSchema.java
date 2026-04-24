package com.voiceannounce;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builds the Gemini function declarations for all voice-command primitives. */
final class ToolSchema {

    private ToolSchema() {
    }

    static JsonArray build() {
        JsonArray functions = new JsonArray();

        functions.add(fn("move",
                "Walk in a direction for up to duration_ms milliseconds.",
                new String[] { "direction" },
                prop("direction", enumStr("forward", "backward", "left", "right", "north", "south", "east", "west")),
                prop("duration_ms", intProp("How long to hold the movement key, max 5000."))));

        functions.add(fn("look",
                "Rotate the player camera to face a direction.",
                new String[] { "direction" },
                prop("direction", enumStr("forward", "backward", "left", "right", "north", "south", "east", "west",
                        "up", "down"))));

        functions.add(fn("left_click",
                "Simulate the attack/mine button.",
                new String[] { "action" },
                prop("action", enumStr("press", "hold", "release")),
                prop("duration_ms", intProp("For hold, how long to hold (max 5000)."))));

        functions.add(fn("right_click",
                "Simulate the use/place/interact button. Also opens containers.",
                new String[] { "action" },
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
                new String[] { "slot" },
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
                new String[] { "item" },
                prop("item", stringProp("Registry name of item to craft, e.g. minecraft:stone_pickaxe.")),
                prop("quantity", intProp("How many to craft (default 1)."))));

        functions.add(fn("deposit",
                "Deposit items into a nearby open container.",
                new String[0],
                prop("item", stringProp("Registry name; omit for filter=all or filter=held.")),
                prop("filter", enumStr("matching", "held", "all", "excess"))));

        functions.add(fn("withdraw",
                "Withdraw items from a nearby open container.",
                new String[] { "item" },
                prop("item", stringProp("Registry name of item to withdraw.")),
                prop("count", intProp("How many to withdraw (default all)."))));

        functions.add(fn("respawn", "Click the respawn button (only if dead).", new String[0]));

        functions.add(fn("mine_blocks",
                "Hold the attack button until exactly `count` blocks have been broken, then " +
                        "release. Use this whenever the player asks for a specific number of blocks " +
                        "broken. Works best with direction='down' (gravity feeds new blocks under the " +
                        "cursor). 'up' and 'forward' break only the block currently under the cursor; " +
                        "for those, chain mine_blocks(count=1) with move steps in between. " +
                        "OMIT direction entirely to keep the player's current camera aim — use this when " +
                        "the player references their current view ('the block I'm looking at', 'this block').",
                new String[] { "count" },
                prop("direction", enumStr("down", "up", "forward")),
                prop("count", intProp("Number of blocks to break, 1-20."))));

        functions.add(fn("run_macro",
                "Run a pre-defined macro sequence by name. See PlayerState.macros for available names.",
                new String[] { "name" },
                prop("name", stringProp("The macro name to run."))));

        functions.add(defineMacroFn());

        JsonObject wrapper = new JsonObject();
        wrapper.add("functionDeclarations", functions);
        JsonArray tools = new JsonArray();
        tools.add(wrapper);
        return tools;
    }

    private static JsonObject defineMacroFn() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", "define_macro");
        fn.addProperty("description",
                "Register a new macro the player can invoke later by name with run_macro. " +
                        "Use when the player asks to save, create, make, or define a macro. The macro " +
                        "is persisted across launches in voicecommand_macros.json.");

        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonArray req = new JsonArray();
        req.add("name");
        req.add("steps");
        params.add("required", req);

        JsonObject props = new JsonObject();
        props.add("name", stringProp("Macro name (case-insensitive)."));
        props.add("description", stringProp("Short human description of what the macro does."));

        JsonObject stepsSchema = new JsonObject();
        stepsSchema.addProperty("type", "array");
        stepsSchema.addProperty("description",
                "Ordered tool calls. Each step is {tool, args}, same shape as a direct call. " +
                        "Step caps still apply when the macro runs (5 steps, 20 blocks total movement, " +
                        "5000ms per step).");

        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonArray itemReq = new JsonArray();
        itemReq.add("tool");
        item.add("required", itemReq);
        JsonObject itemProps = new JsonObject();
        itemProps.add("tool", stringProp("Primitive tool name (move, look, left_click, etc)."));
        JsonObject argsSchema = new JsonObject();
        argsSchema.addProperty("type", "object");
        argsSchema.addProperty("description", "Args object for the tool.");
        itemProps.add("args", argsSchema);
        item.add("properties", itemProps);
        stepsSchema.add("items", item);

        props.add("steps", stepsSchema);
        params.add("properties", props);
        fn.add("parameters", params);
        return fn;
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
            for (String r : required)
                reqArr.add(r);
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
        for (String v : values)
            arr.add(v);
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
