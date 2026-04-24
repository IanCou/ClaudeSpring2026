package com.voiceannounce;

import com.voiceannounce.handler.CraftingHandler;
import com.voiceannounce.handler.MacroHandler;
import com.voiceannounce.handler.StationHandler;
import com.voiceannounce.handler.StorageHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.MathHelper;

public final class CommandExecutor {

    private CommandExecutor() {}

    public static ToolResult execute(ToolCall call, InputState input) {
        try {
            switch (call.name) {
                case "move":             return doMove(call, input);
                case "look":             return doLook(call);
                case "left_click":       return doClick(call, input, InputState.attack(), "left_click");
                case "right_click":      return doClick(call, input, InputState.useItem(), "right_click");
                case "jump":             return doTap(input, InputState.jump(), "jump");
                case "sneak":            return doToggleKey(call, input, InputState.sneak(), "sneak");
                case "sprint":           return doToggleKey(call, input, InputState.sprint(), "sprint");
                case "stop":             return doStop(input);
                case "mine_blocks":      return ToolResult.ok("mine_blocks", "started (queue handles completion)");
                case "select_slot":      return doSelectSlot(call);
                case "swap_hands":       return doTap(input, InputState.swap(), "swap_hands");
                case "drop":             return doDrop(call, input);
                case "open_inventory":   return doTap(input, InputState.inv(), "open_inventory");
                case "close_container":  return doCloseContainer();
                case "craft":            return CraftingHandler.craft(call);
                case "deposit":          return StorageHandler.deposit(call);
                case "withdraw":         return StorageHandler.withdraw(call);
                case "respawn":          return doRespawn();
                case "run_macro":        return MacroHandler.runMacro(call);
                case "define_macro":     return MacroHandler.defineMacro(call);
                default:                 return ToolResult.fail(call.name, "unknown tool");
            }
        } catch (Exception e) {
            return ToolResult.fail(call.name, "exception: " + e.getMessage());
        }
    }

    // ----- basic inputs -----

    private static ToolResult doMove(ToolCall call, InputState input) {
        String dir = call.argString("direction", "forward");
        int dur = Math.max(250, call.argInt("duration_ms", 500));
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) return ToolResult.fail("move", "no player");

        KeyBinding kb;
        switch (dir) {
            case "forward":  kb = InputState.forward(); break;
            case "backward": kb = InputState.back(); break;
            case "left":     kb = InputState.left(); break;
            case "right":    kb = InputState.right(); break;
            case "north": case "south": case "east": case "west":
                aimYawAbsolute(p, dir);
                kb = InputState.forward();
                break;
            default: return ToolResult.fail("move", "bad direction: " + dir);
        }
        input.hold(kb, dur);
        return ToolResult.ok("move", "moved " + dir + " for " + dur + "ms");
    }

    private static ToolResult doLook(ToolCall call) {
        String dir = call.argString("direction", "forward");
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) return ToolResult.fail("look", "no player");

        switch (dir) {
            case "up":       p.rotationPitch = -85f; break;
            case "down":     p.rotationPitch = 85f; break;
            case "forward":  p.rotationPitch = 0f; break;
            case "backward": p.rotationYaw += 180f; p.rotationPitch = 0f; break;
            case "left":     p.rotationYaw -= 90f; break;
            case "right":    p.rotationYaw += 90f; break;
            case "north": case "south": case "east": case "west":
                aimYawAbsolute(p, dir);
                break;
            default: return ToolResult.fail("look", "bad direction: " + dir);
        }
        return ToolResult.ok("look", "facing " + dir);
    }

    private static void aimYawAbsolute(EntityPlayerSP p, String cardinal) {
        // MC yaw 0 = south, 90 = west, 180 = north, 270 = east
        switch (cardinal) {
            case "south": p.rotationYaw = 0f;    break;
            case "west":  p.rotationYaw = 90f;   break;
            case "north": p.rotationYaw = 180f;  break;
            case "east":  p.rotationYaw = -90f;  break;
        }
    }

    private static ToolResult doClick(ToolCall call, InputState input, KeyBinding kb, String name) {
        String action = call.argString("action", "press");
        switch (action) {
            case "press":
                input.press(kb);
                return ToolResult.ok(name, "pressed");
            case "hold":
                int dur = Math.max(100, call.argInt("duration_ms", 500));
                input.hold(kb, dur);
                return ToolResult.ok(name, "held for " + dur + "ms");
            case "release":
                input.release(kb);
                return ToolResult.ok(name, "released");
            default:
                return ToolResult.fail(name, "bad action: " + action);
        }
    }

    private static ToolResult doTap(InputState input, KeyBinding kb, String name) {
        input.press(kb);
        return ToolResult.ok(name, "pressed");
    }

    private static ToolResult doToggleKey(ToolCall call, InputState input, KeyBinding kb, String name) {
        String action = call.argString("action", "toggle");
        switch (action) {
            case "toggle":
                if (input.isHeld(kb)) { input.release(kb); return ToolResult.ok(name, "released"); }
                input.hold(kb, 0);
                return ToolResult.ok(name, "held");
            case "press":
                input.hold(kb, 0);
                return ToolResult.ok(name, "held");
            case "release":
                input.release(kb);
                return ToolResult.ok(name, "released");
            default:
                return ToolResult.fail(name, "bad action: " + action);
        }
    }

    private static ToolResult doStop(InputState input) {
        input.releaseAll();
        com.voiceannounce.handler.MineBlocksHandler.abort(input);
        return ToolResult.ok("stop", "all inputs released");
    }

    private static ToolResult doSelectSlot(ToolCall call) {
        int slot = call.argInt("slot", 1);
        if (slot < 1 || slot > 9) return ToolResult.fail("select_slot", "slot must be 1-9");
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) return ToolResult.fail("select_slot", "no player");
        p.inventory.currentItem = slot - 1;
        return ToolResult.ok("select_slot", "selected slot " + slot);
    }

    private static ToolResult doDrop(ToolCall call, InputState input) {
        boolean whole = call.argBool("whole_stack", false);
        if (whole) {
            // Ctrl+Q in vanilla — simplest path is just fire drop multiple times or use player.dropItem()
            EntityPlayerSP p = Minecraft.getMinecraft().player;
            if (p == null) return ToolResult.fail("drop", "no player");
            p.dropItem(true);
            return ToolResult.ok("drop", "dropped stack");
        }
        input.press(InputState.drop());
        return ToolResult.ok("drop", "dropped one");
    }

    private static ToolResult doCloseContainer() {
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) return ToolResult.fail("close_container", "no player");
        Minecraft.getMinecraft().addScheduledTask(p::closeScreen);
        return ToolResult.ok("close_container", "closed");
    }

    private static ToolResult doRespawn() {
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) return ToolResult.fail("respawn", "no player");
        if (!p.isDead) return ToolResult.fail("respawn", "player is not dead");
        p.respawnPlayer();
        Minecraft.getMinecraft().displayGuiScreen(null);
        return ToolResult.ok("respawn", "respawned");
    }

    // Unused suppressor — keeps MathHelper from being flagged; harmless. (removed)
    @SuppressWarnings("unused")
    private static int _unused(float f) { return MathHelper.floor(f); }
}
