package com.voiceannounce;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.voiceannounce.handler.MacroHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public final class PlayerState {

    private PlayerState() {}

    /** Must be called on the main thread. */
    public static JsonObject snapshot() {
        JsonObject out = new JsonObject();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            out.addProperty("error", "no_player");
            return out;
        }
        EntityPlayer p = mc.player;

        JsonObject pos = new JsonObject();
        pos.addProperty("x", Math.round(p.posX * 100.0) / 100.0);
        pos.addProperty("y", Math.round(p.posY * 100.0) / 100.0);
        pos.addProperty("z", Math.round(p.posZ * 100.0) / 100.0);
        out.add("position", pos);

        out.addProperty("facing", normalize(p.rotationYaw));
        out.addProperty("pitch", Math.round(p.rotationPitch));
        out.addProperty("heldItem", itemName(p.getHeldItemMainhand()));
        out.addProperty("offhandItem", itemName(p.getHeldItemOffhand()));
        out.addProperty("health", Math.round(p.getHealth()));
        out.addProperty("hunger", p.getFoodStats().getFoodLevel());
        out.addProperty("isDead", p.isDead);
        out.addProperty("isInWater", p.isInWater());
        out.addProperty("isOnGround", p.onGround);

        JsonObject inv = new JsonObject();
        inv.add("hotbar", serializeRange(p.inventory.mainInventory, 0, 9));
        inv.add("main", serializeRange(p.inventory.mainInventory, 9, 36));
        out.add("inventory", inv);

        JsonObject container = nearbyContainer(p);
        if (container != null) out.add("nearbyContainer", container);

        out.add("macros", MacroHandler.listForPrompt());
        return out;
    }

    private static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getItem().getRegistryName() != null
            ? stack.getItem().getRegistryName().toString()
            : stack.getDisplayName();
    }

    private static JsonArray serializeRange(java.util.List<ItemStack> items, int from, int to) {
        JsonArray arr = new JsonArray();
        for (int i = from; i < to && i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s == null || s.isEmpty()) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("slot", i);
            obj.addProperty("item", itemName(s));
            obj.addProperty("count", s.getCount());
            arr.add(obj);
        }
        return arr;
    }

    private static JsonObject nearbyContainer(EntityPlayer p) {
        BlockPos playerPos = p.getPosition();
        double radius = 4.5;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (playerPos.distanceSq(pos) > radius * radius) continue;
                    TileEntity te = p.world.getTileEntity(pos);
                    if (te instanceof TileEntityChest) {
                        return serializeChest((TileEntityChest) te, pos);
                    }
                }
            }
        }
        return null;
    }

    private static JsonObject serializeChest(TileEntityChest chest, BlockPos pos) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "chest");
        JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX());
        p.addProperty("y", pos.getY());
        p.addProperty("z", pos.getZ());
        obj.add("position", p);

        JsonArray contents = new JsonArray();
        int free = 0;
        int size = chest.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack s = chest.getStackInSlot(i);
            if (s == null || s.isEmpty()) { free++; continue; }
            JsonObject item = new JsonObject();
            item.addProperty("slot", i);
            item.addProperty("item", itemName(s));
            item.addProperty("count", s.getCount());
            contents.add(item);
        }
        obj.add("contents", contents);
        obj.addProperty("freeSlots", free);
        return obj;
    }

    private static int normalize(float yaw) {
        int y = MathHelper.floor(yaw) % 360;
        if (y < 0) y += 360;
        return y;
    }
}
