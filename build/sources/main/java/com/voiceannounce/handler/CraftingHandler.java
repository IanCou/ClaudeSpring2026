package com.voiceannounce.handler;

import com.voiceannounce.ToolCall;
import com.voiceannounce.ToolResult;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public final class CraftingHandler {

    private CraftingHandler() {}

    public static ToolResult craft(ToolCall call) {
        String itemName = call.argString("item", "").toLowerCase().trim();
        if (itemName.isEmpty()) return ToolResult.fail("craft", "missing item");
        int quantity = Math.max(1, call.argInt("quantity", 1));

        ResourceLocation rl = itemName.contains(":")
            ? new ResourceLocation(itemName)
            : new ResourceLocation("minecraft", itemName);
        Item target = Item.REGISTRY.getObject(rl);
        if (target == null) return ToolResult.fail("craft", "unknown item: " + rl);

        IRecipe recipe = findRecipeFor(target);
        if (recipe == null) return ToolResult.fail("craft", "no recipe for " + rl);

        boolean needs3x3 = !recipe.canFit(2, 2);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return ToolResult.fail("craft", "no player");

        if (needs3x3 && !hasCraftingTableNear(mc.player)) {
            return ToolResult.fail("craft", "need a crafting table within 4.5 blocks for " + rl);
        }

        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return ToolResult.fail("craft", "multiplayer crafting not yet supported");
        }

        final ToolResult[] holder = new ToolResult[1];
        final IRecipe fRecipe = recipe;
        final int fQuantity = quantity;
        final String fItemName = rl.toString();
        try {
            server.addScheduledTask(() ->
                holder[0] = doCraftOnServer(server, mc.player.getUniqueID(), fRecipe, fQuantity, fItemName)
            ).get();
        } catch (InterruptedException | ExecutionException e) {
            return ToolResult.fail("craft", "server task failed: " + e.getMessage());
        }
        return holder[0] != null ? holder[0] : ToolResult.fail("craft", "no result returned");
    }

    /** Runs on the server thread. */
    private static ToolResult doCraftOnServer(IntegratedServer server, java.util.UUID playerId,
                                              IRecipe recipe, int quantity, String itemName) {
        EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(playerId);
        if (sp == null) return ToolResult.fail("craft", "server player not found");

        InventoryPlayer inv = sp.inventory;
        int perCraft = Math.max(1, recipe.getRecipeOutput().getCount());
        int craftsNeeded = (int) Math.ceil((double) quantity / (double) perCraft);
        int performed = 0;

        for (int i = 0; i < craftsNeeded; i++) {
            Map<Integer, Integer> plan = planConsumption(inv, recipe.getIngredients());
            if (plan == null) break;

            for (Map.Entry<Integer, Integer> e : plan.entrySet()) {
                inv.decrStackSize(e.getKey(), e.getValue());
            }
            ItemStack out = recipe.getRecipeOutput().copy();
            if (!inv.addItemStackToInventory(out)) {
                sp.dropItem(out, false);
            }
            performed++;
        }

        sp.inventoryContainer.detectAndSendChanges();

        if (performed == 0) {
            return ToolResult.fail("craft", "missing ingredients for " + itemName);
        }
        int produced = performed * perCraft;
        return ToolResult.ok("craft", "crafted " + produced + "x " + itemName);
    }

    private static IRecipe findRecipeFor(Item targetItem) {
        for (IRecipe r : CraftingManager.REGISTRY) {
            ItemStack out = r.getRecipeOutput();
            if (!out.isEmpty() && out.getItem() == targetItem) return r;
        }
        return null;
    }

    /** Returns a map of inventory slot → count to consume, or null if ingredients are missing. */
    private static Map<Integer, Integer> planConsumption(InventoryPlayer inv,
                                                         NonNullList<Ingredient> ingredients) {
        Map<Integer, Integer> consumed = new HashMap<>();
        for (Ingredient ing : ingredients) {
            if (ing == Ingredient.EMPTY) continue;
            boolean satisfied = false;
            for (int slot = 0; slot < inv.mainInventory.size(); slot++) {
                ItemStack stack = inv.mainInventory.get(slot);
                if (stack.isEmpty()) continue;
                int alreadyUsed = consumed.getOrDefault(slot, 0);
                if (stack.getCount() - alreadyUsed <= 0) continue;
                if (ing.apply(stack)) {
                    consumed.merge(slot, 1, Integer::sum);
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) return null;
        }
        return consumed;
    }

    private static boolean hasCraftingTableNear(EntityPlayer p) {
        BlockPos pp = p.getPosition();
        double r = 4.5;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = pp.add(dx, dy, dz);
                    if (pp.distanceSq(pos) > r * r) continue;
                    IBlockState bs = p.world.getBlockState(pos);
                    if (bs.getBlock() == Blocks.CRAFTING_TABLE) return true;
                }
            }
        }
        return false;
    }
}
