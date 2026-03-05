package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.VersionUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.*;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrderSniper extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private enum Stage {
        NONE, REFRESH, OPEN_ORDERS, WAIT_ORDERS_GUI, SELECT_ORDER,
        WAIT_DEPOSIT_GUI, TRANSFER_ITEMS, WAIT_CONFIRM_GUI, CONFIRM_SALE,
        FINAL_EXIT, CYCLE_PAUSE, SEARCH_CHESTS, OPEN_CHEST, LOOT_CHEST, CLOSE_CHEST
    }

    private Stage stage = Stage.NONE;
    private long stageStart = 0;
    private int transferIndex = 0;
    private long lastTransferTime = 0;
    private int ticksSinceStageStart = 0;
    private int savedSyncId = -1;
    private List<BlockPos> nearbyChests = new ArrayList<>();
    private int currentChestIndex = 0;
    private BlockPos currentChestPos = null;

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlacklist = settings.createGroup("Blacklist");

    public enum SnipeMode {
        HIGHEST_PRICE("Highest Price"),
        SPECIFIC_PLAYER("Specific Player");
        
        private final String title;
        SnipeMode(String title) { this.title = title; }
        @Override
        public String toString() { return title; }
    }

    private final Setting<SnipeMode> snipeMode = sgGeneral.add(new EnumSetting.Builder<SnipeMode>()
        .name("snipe-mode")
        .description("Choose between sniping highest price orders or specific player orders.")
        .defaultValue(SnipeMode.HIGHEST_PRICE)
        .build());

    private final Setting<String> itemName = sgGeneral.add(new StringSetting.Builder()
        .name("item-name")
        .description("The item name to search for in /orders command.")
        .defaultValue("diamond")
        .visible(() -> snipeMode.get() == SnipeMode.HIGHEST_PRICE)
        .build());

    private final Setting<String> playerName = sgGeneral.add(new StringSetting.Builder()
        .name("player-name")
        .description("The player name to open orders for (/orders PlayerName).")
        .defaultValue("")
        .visible(() -> snipeMode.get() == SnipeMode.SPECIFIC_PLAYER)
        .build());

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("sniping-item")
        .description("The actual item to snipe and sell.")
        .defaultValue(Items.DIAMOND)
        .build());

    private final Setting<String> minPrice = sgGeneral.add(new StringSetting.Builder()
        .name("min-price")
        .description("Minimum acceptable price.")
        .defaultValue("1")
        .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Notify on important actions.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> shulkerSupport = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-support")
        .description("Enable slower but safer shulker box support.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay in ticks for slower connections (0 = fastest)")
        .defaultValue(0)
        .min(0)
        .max(10)
        .sliderMax(10)
        .build());

    private final Setting<List<String>> blacklistedPlayers = sgBlacklist.add(new StringListSetting.Builder()
        .name("blacklisted-players")
        .description("Players whose orders will be ignored.")
        .defaultValue(List.of())
        .build());

    private final Setting<Boolean> autoLootChests = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-loot-chests")
        .description("Automatically search and loot nearby chests when inventory is empty.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> chestSearchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chest-search-radius")
        .description("Radius to search for chests (in blocks).")
        .defaultValue(10)
        .min(3)
        .max(20)
        .sliderMax(20)
        .visible(() -> autoLootChests.get())
        .build());

    public OrderSniper() {
        super(GlazedAddon.CATEGORY, "order-sniper", "Sniping Orders and sell for your price.");
    }

    @Override
    public void onActivate() {
        if (parsePrice(minPrice.get()) == -1.0) {
            ChatUtils.error("Invalid price format.");
            toggle();
            return;
        }
        
        // Validate settings based on mode
        if (snipeMode.get() == SnipeMode.HIGHEST_PRICE) {
            if (itemName.get().trim().isEmpty()) {
                ChatUtils.error("Item name cannot be empty in Highest Price mode.");
                toggle();
                return;
            }
        } else {
            if (playerName.get().trim().isEmpty()) {
                ChatUtils.error("Player name cannot be empty in Specific Player mode.");
                toggle();
                return;
            }
        }
        
        stage = Stage.REFRESH;
        stageStart = System.currentTimeMillis();
        transferIndex = 0;
        lastTransferTime = 0;
        ticksSinceStageStart = 0;
    }

    @Override
    public void onDeactivate() {
        stage = Stage.NONE;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        ticksSinceStageStart++;

        switch (stage) {
            case REFRESH -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    ScreenHandler handler = screen.getScreenHandler();
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);

                    if (now - stageStart > 50) {
                        stage = Stage.OPEN_ORDERS;
                        stageStart = now;
                        ticksSinceStageStart = 0;
                    }
                } else {
                    stage = Stage.OPEN_ORDERS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case OPEN_ORDERS -> {
                String command;
                if (snipeMode.get() == SnipeMode.HIGHEST_PRICE) {
                    command = "/orders " + itemName.get();
                } else {
                    command = "/orders " + playerName.get();
                }
                ChatUtils.sendPlayerMsg(command);
                stage = Stage.WAIT_ORDERS_GUI;
                stageStart = now;
                ticksSinceStageStart = 0;
            }

            case WAIT_ORDERS_GUI -> {
                if (ticksSinceStageStart < Math.max(8, delayTicks.get())) return;
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.SELECT_ORDER;
                    ticksSinceStageStart = 0;
                } else if (now - stageStart > 2000) {
                    stage = Stage.OPEN_ORDERS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case SELECT_ORDER -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
                ScreenHandler handler = screen.getScreenHandler();
                
                if (snipeMode.get() == SnipeMode.HIGHEST_PRICE) {
                    // Original logic: find highest price order
                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && isMatchingOrder(stack)) {
                            if (isBlacklisted(getOrderPlayerName(stack))) continue;
                            savedSyncId = handler.syncId;
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                            stage = Stage.WAIT_DEPOSIT_GUI;
                            stageStart = now;
                            ticksSinceStageStart = 0;
                            return;
                        }
                    }
                } else {
                    // New logic: find any valid order from specific player (we're already in their shop)
                    boolean foundValidOrder = false;
                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && isPlayerOrderMatching(stack)) {
                            foundValidOrder = true;
                            savedSyncId = handler.syncId;
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                            stage = Stage.WAIT_DEPOSIT_GUI;
                            stageStart = now;
                            ticksSinceStageStart = 0;
                            return;
                        }
                    }
                    
                    // If no valid orders found after reasonable time, auto-disable
                    if (!foundValidOrder && now - stageStart > 1000) {
                        ChatUtils.error("No more orders from " + playerName.get() + " - auto-disabling");
                        if (mc.currentScreen != null) mc.player.closeHandledScreen();
                        toggle(); // Auto-disable the module
                        return;
                    }
                }
                
                if (now - stageStart > 2000) {
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    mc.interactionManager.clickSlot(handler.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
                    stage = Stage.OPEN_ORDERS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case WAIT_DEPOSIT_GUI -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    if (screen.getScreenHandler().syncId != savedSyncId) {
                        stage = Stage.TRANSFER_ITEMS;
                        transferIndex = 0;
                        lastTransferTime = now;
                        ticksSinceStageStart = 0;
                        return;
                    }
                }
                if (now - stageStart > 3000) {
                    if (mc.currentScreen != null) mc.player.closeHandledScreen();
                    stage = Stage.OPEN_ORDERS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case TRANSFER_ITEMS -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
                ScreenHandler handler = screen.getScreenHandler();

                boolean chestHasSpace = false;
                for (Slot slot : handler.slots) {
                    if (slot.inventory != mc.player.getInventory() && slot.getStack().isEmpty()) {
                        chestHasSpace = true;
                        break;
                    }
                }

                if (!chestHasSpace) {
                    mc.player.closeHandledScreen();
                    stage = Stage.WAIT_CONFIRM_GUI;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                    return;
                }

                boolean hasNormalItems = false;
                boolean hasShulkers = false;
                for (ItemStack stack : mc.player.getInventory().main) {
                    if (!stack.isEmpty()) {
                        if (stack.isOf(targetItem.get())) hasNormalItems = true;
                        else if (shulkerSupport.get() && isShulker(stack) && shulkerContainsTarget(stack)) hasShulkers = true;
                    }
                }

                // Transfer items from player inventory to order chest
                for (Slot slot : handler.slots) {
                    if (slot.inventory == mc.player.getInventory()) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty()) {
                            boolean shouldTransfer = false;
                            
                            // Transfer target items
                            if (stack.isOf(targetItem.get())) {
                                shouldTransfer = true;
                            }
                            // Transfer shulkers with target items (only if no normal items available)
                            else if (!hasNormalItems && shulkerSupport.get() && isShulker(stack) && shulkerContainsTarget(stack)) {
                                shouldTransfer = true;
                            }
                            
                            if (shouldTransfer) {
                                mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                                // Add small delay for better reliability
                                lastTransferTime = now;
                            }
                        }
                    }
                }

                boolean stillHasItems = false;
                for (ItemStack stack : mc.player.getInventory().main) {
                    if (!stack.isEmpty() && (stack.isOf(targetItem.get()) || (shulkerSupport.get() && isShulker(stack) && shulkerContainsTarget(stack)))) {
                        stillHasItems = true;
                        break;
                    }
                }

                if (!stillHasItems) {
                    mc.player.closeHandledScreen();
                    stage = Stage.WAIT_CONFIRM_GUI;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case WAIT_CONFIRM_GUI -> {
                if (ticksSinceStageStart < Math.max(8, delayTicks.get())) return;
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.CONFIRM_SALE;
                    ticksSinceStageStart = 0;
                } else if (now - stageStart > 2000) {
                    toggle();
                }
            }

            case CONFIRM_SALE -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
                ScreenHandler handler = screen.getScreenHandler();
                for (Slot slot : handler.slots) {
                    ItemStack stack = slot.getStack();
                    if (isGreenGlass(stack)) {
                        for (int i = 0; i < 3; i++) {
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                        }
                        stage = Stage.FINAL_EXIT;
                        stageStart = now;
                        ticksSinceStageStart = 0;
                        return;
                    }
                }
            }

            case FINAL_EXIT -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    // Aspetta un po' prima di continuare per assicurarsi che la GUI sia chiusa (PIZZZZZZAAAA!!!!!!!!)
                    if (ticksSinceStageStart < Math.max(5, delayTicks.get())) return;
                }

                if (!hasItemsToSell()) {
                    // Check if auto-loot is enabled and we should search for chests
                    if (autoLootChests.get()) {
                        nearbyChests = findNearbyChests();
                        if (!nearbyChests.isEmpty()) {
                            currentChestIndex = 0;
                            if (notifications.get()) {
                                ChatUtils.info("Found " + nearbyChests.size() + " nearby chest(s), searching for " + 
                                    targetItem.get().getName().getString() + "...");
                            }
                            stage = Stage.SEARCH_CHESTS;
                            stageStart = now;
                            ticksSinceStageStart = 0;
                            return;
                        }
                    }
                    
                    if (notifications.get()) {
                        ChatUtils.info("Completed selling all items!");
                    }
                    toggle();
                } else {
                    stage = Stage.CYCLE_PAUSE;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case CYCLE_PAUSE -> {
                // Changed from 25 ticks to 5 ticks (0.25 seconds at 20 TPS)
                if (ticksSinceStageStart >= Math.max(5, delayTicks.get())) {
                    stage = Stage.REFRESH;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }

            case NONE -> {}
            
            case SEARCH_CHESTS -> {
                if (currentChestIndex >= nearbyChests.size()) {
                    // No more chests to search or no items found
                    if (notifications.get()) {
                        ChatUtils.info("No " + targetItem.get().getName().getString() + 
                            " found in nearby chests - stopping");
                    }
                    toggle();
                    return;
                }
                
                currentChestPos = nearbyChests.get(currentChestIndex);
                // Try to open the chest
                if (mc.interactionManager != null && mc.world != null) {
                    BlockHitResult hitResult = new BlockHitResult(
                        Vec3d.ofCenter(currentChestPos), 
                        Direction.UP, 
                        currentChestPos, 
                        false
                    );
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                    stage = Stage.OPEN_CHEST;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }
            
            case OPEN_CHEST -> {
                if (ticksSinceStageStart < Math.max(10, delayTicks.get())) return;
                
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.LOOT_CHEST;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                } else if (now - stageStart > 2000) {
                    // Failed to open this chest, try next one
                    currentChestIndex++;
                    stage = Stage.SEARCH_CHESTS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }
            
            case LOOT_CHEST -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    // Chest was closed, move to next one
                    currentChestIndex++;
                    stage = Stage.SEARCH_CHESTS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                    return;
                }
                
                ScreenHandler handler = screen.getScreenHandler();
                boolean foundItems = false;
                
                // Only look for the specific target item or shulkers containing target items
                for (Slot slot : handler.slots) {
                    if (slot.inventory != mc.player.getInventory()) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty()) {
                            // Only take items that match our target item exactly
                            if (stack.isOf(targetItem.get())) {
                                mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                                foundItems = true;
                            }
                            // Or shulkers that contain our target item (if shulker support enabled)
                            else if (shulkerSupport.get() && isShulker(stack) && shulkerContainsTarget(stack)) {
                                mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                                foundItems = true;
                            }
                        }
                    }
                }
                
                if (foundItems && notifications.get()) {
                    ChatUtils.info("Found " + targetItem.get().getName().getString() + 
                        " in chest at " + currentChestPos.toShortString());
                }
                
                stage = Stage.CLOSE_CHEST;
                stageStart = now;
                ticksSinceStageStart = 0;
            }
            
            case CLOSE_CHEST -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                
                if (ticksSinceStageStart < Math.max(5, delayTicks.get())) return;
                
                // Check if we now have items to sell
                if (hasItemsToSell()) {
                    if (notifications.get()) {
                        ChatUtils.info("Found " + targetItem.get().getName().getString() + "! Resuming order sniping...");
                    }
                    stage = Stage.REFRESH;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                } else {
                    // Try next chest
                    currentChestIndex++;
                    stage = Stage.SEARCH_CHESTS;
                    stageStart = now;
                    ticksSinceStageStart = 0;
                }
            }
        }
    }



    private boolean isBlacklisted(String playerName) {
        if (playerName == null || blacklistedPlayers.get().isEmpty()) return false;
        return blacklistedPlayers.get().stream().anyMatch(p -> p.equalsIgnoreCase(playerName));
    }

    private String getOrderPlayerName(ItemStack stack) {
        if (stack.isEmpty()) return null;
        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
        Pattern[] patterns = {
            Pattern.compile("(?i)player\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)from\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)by\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)seller\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)owner\\s*:\\s*([a-zA-Z0-9_]+)")
        };
        for (Text line : tooltip) {
            String text = line.getString();
            for (Pattern p : patterns) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    String name = m.group(1);
                    if (name.length() >= 3 && name.length() <= 16) return name;
                }
            }
        }
        return null;
    }

    private boolean isMatchingOrder(ItemStack stack) {
        if (!stack.isOf(targetItem.get())) return false;
        double price = getOrderPrice(stack);
        double min = parsePrice(minPrice.get());
        return price >= min;
    }

    // New method for player-specific order matching
    private boolean isPlayerOrderMatching(ItemStack stack) {
        if (!stack.isOf(targetItem.get())) return false;
        double price = getOrderPrice(stack);
        double min = parsePrice(minPrice.get());
        return price >= min;
    }

    private double getOrderPrice(ItemStack stack) {
        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
        return parseTooltipPrice(tooltip);
    }

    private double parseTooltipPrice(List<Text> tooltip) {
        Pattern pattern = Pattern.compile("\\$([\\d,.]+)([kmb])?", Pattern.CASE_INSENSITIVE);
        for (Text line : tooltip) {
            String text = line.getString().toLowerCase().replace(",", "").trim();
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    double base = Double.parseDouble(matcher.group(1));
                    String suffix = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "";
                    return switch (suffix) {
                        case "k" -> base * 1_000;
                        case "m" -> base * 1_000_000;
                        case "b" -> base * 1_000_000_000;
                        default -> base;
                    };
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1.0;
    }

    private boolean hasItemsToSell() {
        for (ItemStack stack : VersionUtil.getMainInventory(mc.player)) {
            if (!stack.isEmpty()) {
                if (stack.isOf(targetItem.get())) {
                    return true;
                } else if (shulkerSupport.get() && isShulker(stack) && shulkerContainsTarget(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isGreenGlass(ItemStack stack) {
        return stack.getItem() == Items.LIME_STAINED_GLASS_PANE || stack.getItem() == Items.GREEN_STAINED_GLASS_PANE;
    }

    private boolean isShulker(ItemStack stack) {

        Item item = stack.getItem();
        String itemName = item.getName().getString().toLowerCase();
        return itemName.contains("shulker") ||
            item == Items.SHULKER_BOX ||
            item == Items.WHITE_SHULKER_BOX ||
            item == Items.ORANGE_SHULKER_BOX ||
            item == Items.MAGENTA_SHULKER_BOX ||
            item == Items.LIGHT_BLUE_SHULKER_BOX ||
            item == Items.YELLOW_SHULKER_BOX ||
            item == Items.LIME_SHULKER_BOX ||
            item == Items.PINK_SHULKER_BOX ||
            item == Items.GRAY_SHULKER_BOX ||
            item == Items.LIGHT_GRAY_SHULKER_BOX ||
            item == Items.CYAN_SHULKER_BOX ||
            item == Items.PURPLE_SHULKER_BOX ||
            item == Items.BLUE_SHULKER_BOX ||
            item == Items.BROWN_SHULKER_BOX ||
            item == Items.GREEN_SHULKER_BOX ||
            item == Items.RED_SHULKER_BOX ||
            item == Items.BLACK_SHULKER_BOX;
    }

    private boolean shulkerContainsTarget(ItemStack shulker) {
        if (!isShulker(shulker)) return false;


        List<Text> tooltip = shulker.getTooltip(Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
        String targetName = targetItem.get().getName().getString().toLowerCase();

        for (Text line : tooltip) {
            String lineText = line.getString().toLowerCase();

            if (lineText.contains(targetName) ||
                lineText.contains(targetName.replace(" ", "_")) ||
                lineText.contains(targetName.replace("_", " "))) {
                return true;
            }
        }
        return false;
    }

    private double parsePrice(String priceStr) {
        try {
            String cleaned = priceStr.toLowerCase().replace(",", "").trim();
            double multiplier = 1;
            if (cleaned.endsWith("b")) {
                multiplier = 1_000_000_000; cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else if (cleaned.endsWith("m")) {
                multiplier = 1_000_000; cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else if (cleaned.endsWith("k")) {
                multiplier = 1_000; cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            return Double.parseDouble(cleaned) * multiplier;
        } catch (Exception e) {
            return -1.0;
        }
    }

    private String getFormattedItemName(Item item) {
        String[] parts = item.getTranslationKey().split("\\.");
        String name = parts[parts.length - 1].replace("_", " ");
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private long getTransferDelay() {
        if (!shulkerSupport.get()) {

            return 30;
        }


        ItemStack stack = mc.player.getInventory().getStack(transferIndex);
        if (isShulker(stack)) return 200;
        return 50;
    }

    private int getTransferDelayTicks() {
        if (!shulkerSupport.get()) {

            return Math.max(1, delayTicks.get());
        }


        ItemStack stack = mc.player.getInventory().getStack(transferIndex);
        if (isShulker(stack)) return Math.max(10, delayTicks.get() * 2);
        return Math.max(3, delayTicks.get());
    }

    @Override
    public String getInfoString() {
        if (!isActive()) return null;
        String searchTerm = snipeMode.get() == SnipeMode.HIGHEST_PRICE ? itemName.get() : playerName.get();
        String mode = snipeMode.get() == SnipeMode.HIGHEST_PRICE ? "Price" : "Player";
        return String.format("%s: %s -> %s (%s)",
            mode,
            searchTerm,
            targetItem.get().getName().getString(),
            stage.name());
    }

    // Find nearby chests within the search radius
    private List<BlockPos> findNearbyChests() {
        List<BlockPos> chests = new ArrayList<>();
        if (mc.world == null || mc.player == null) return chests;
        
        BlockPos playerPos = mc.player.getBlockPos();
        int radius = chestSearchRadius.get();
        
        // Search in a cubic area around the player
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    
                    // Check if it's a chest or shulker box
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CHEST ||
                        mc.world.getBlockState(pos).getBlock() == Blocks.TRAPPED_CHEST ||
                        isShulkerBlock(pos)) {
                        
                        // Check if we can reach it (simple distance check)
                        double distance = Math.sqrt(pos.getSquaredDistance(playerPos));
                        if (distance <= radius && distance <= 6.0) { // Within reach distance
                            chests.add(pos);
                        }
                    }
                }
            }
        }
        
        // Sort by distance (closest first)
        chests.sort((pos1, pos2) -> {
            double dist1 = pos1.getSquaredDistance(playerPos);
            double dist2 = pos2.getSquaredDistance(playerPos);
            return Double.compare(dist1, dist2);
        });
        
        return chests;
    }
    
    private boolean isShulkerBlock(BlockPos pos) {
        if (mc.world == null) return false;
        return mc.world.getBlockState(pos).getBlock().getName().getString().toLowerCase().contains("shulker");
    }
}
