package team.cynet.cynetcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

public class CynetCorePlugin extends JavaPlugin implements Listener {
    private CynetSidebarManager sidebarManager;
    private final Map<UUID, ShopSession> shopSessions = new HashMap<>();
    private final Map<UUID, Integer> balances = new HashMap<>();
    private final Map<Material, Integer> priceOverrides = new HashMap<>();
    private final Map<UUID, PlayerStats> playerStats = new HashMap<>();
    private List<Material> shopMaterials = new ArrayList<>();
    private List<ShopCategory> shopCategories = new ArrayList<>();
    private String shopTitle;
    private String shopCurrency;
    private int shopStartBalance;
    private double shopBuyMultiplier;
    private double shopSellMultiplier;
    private int shopItemsPerPage;
    private boolean shopEnabled;
    private boolean shopCategoriesAuto;
    private boolean broadcastEnabled;
    private int broadcastIntervalTicks;
    private List<String> broadcastMessages = new ArrayList<>();
    private int broadcastTaskId = -1;
    private int broadcastIndex = 0;
    private boolean playtimeEnabled;
    private int playtimeTrackingIntervalTicks;
    private int playtimeRewardIntervalSeconds;
    private int playtimeRewardAmount;
    private String playtimeRewardMessage;
    private boolean dailyEnabled;
    private int dailyPlaytimeTargetSeconds;
    private int dailyRewardAmount;
    private String dailyCompleteMessage;
    private String dailyAllCompleteMessage;
    private List<String> dailyCommandLines = new ArrayList<>();
    private List<String> dailyCommandHeaderLines = new ArrayList<>();
    private List<String> dailyCommandFooterLines = new ArrayList<>();
    private String dailyCommandMissionLine;
    private List<DailyMission> dailyMissions = new ArrayList<>();
    private int dailyMissionsPerDayMin;
    private int dailyMissionsPerDayMax;
    private int menuSize;
    private String menuTitle;
    private String loginCommand;
    private int loginOpenDelay;
    private int loginCheckInterval;
    private int loginMaxAttempts;
    private final Map<UUID, Integer> pendingOpenTasks = new HashMap<>();
    private final Map<UUID, Integer> pendingLobbyTasks = new HashMap<>();
    private boolean precreateWorlds;
    private int precreateDelay;
    private int lobbyTeleportInterval;
    private int lobbyTeleportMaxAttempts;
    private int teleportInvulnerableTicks;
    private int joinProtectionTicks;
    private boolean welcomeEnabled;
    private String welcomeType;
    private String welcomeMessage;
    private String welcomeSubtitle;
    private String welcomeSound;
    private float welcomeVolume;
    private float welcomePitch;
    private int welcomeDelay;
    private boolean keepInventoryOnDeath;
    private int deathRestoreDelay;
    private final Map<UUID, StoredInventory> pendingDeathInventories = new HashMap<>();
    private boolean gamemodeEnabled;
    private int playtimeTaskId = -1;
    private int dataSaveTaskId = -1;
    private int dataAutosaveTicks;
    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadShopSettings();
        loadBroadcastSettings();
        loadPlaytimeSettings();
        loadDailySettings();
        loadMenuSettings();
        initDataFile();
        loadData();
        sidebarManager = new CynetSidebarManager(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getOnlinePlayers().forEach(sidebarManager::addPlayer);
        sidebarManager.start();
        startBroadcasts();
        startPlaytimeTracking();
        startDataAutosave();
        if (!gamemodeEnabled) {
            Bukkit.getOnlinePlayers().forEach(player -> player.setGameMode(GameMode.SURVIVAL));
        }
        if (precreateWorlds) {
            Bukkit.getScheduler().runTaskLater(this, this::precreateAllWorlds, precreateDelay);
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        if (sidebarManager != null) {
            sidebarManager.stop();
            sidebarManager.clear();
        }
        shopSessions.clear();
        balances.clear();
        priceOverrides.clear();
        pendingOpenTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        pendingLobbyTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        pendingOpenTasks.clear();
        pendingLobbyTasks.clear();
        pendingDeathInventories.clear();
        stopBroadcasts();
        stopPlaytimeTracking();
        stopDataAutosave();
        saveData();
    }

    @org.bukkit.event.EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sidebarManager.addPlayer(event.getPlayer());
        ensureBalance(event.getPlayer());
        PlayerStats stats = ensureStats(event.getPlayer());
        resetDailyIfNeeded(event.getPlayer(), stats, currentDate());
        Player player = event.getPlayer();
        applyJoinProtection(player);
        scheduleWelcome(player);
        if (!gamemodeEnabled) {
            player.setGameMode(GameMode.SURVIVAL);
            return;
        }
        scheduleLobbyTeleport(player);
    }

    @org.bukkit.event.EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sidebarManager.removePlayer(event.getPlayer());
        shopSessions.remove(event.getPlayer().getUniqueId());
        UUID playerId = event.getPlayer().getUniqueId();
        cancelPending(playerId);
        cancelLobbyPending(playerId);
    }

    @org.bukkit.event.EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dailyEnabled) {
            return;
        }
        Player player = event.getPlayer();
        PlayerStats stats = ensureStats(player);
        resetDailyIfNeeded(player, stats, currentDate());
        addDailyProgressForType(player, stats, DailyMissionType.BREAK_BLOCKS, 1);
    }

    @org.bukkit.event.EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!dailyEnabled) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        PlayerStats stats = ensureStats(killer);
        resetDailyIfNeeded(killer, stats, currentDate());
        addDailyProgressForType(killer, stats, DailyMissionType.KILL_MOBS, 1);
    }

    @org.bukkit.event.EventHandler
    public void onLoginCommand(PlayerCommandPreprocessEvent event) {
        if (!gamemodeEnabled) {
            return;
        }
        String message = event.getMessage().toLowerCase(Locale.ENGLISH).trim();
        String command = loginCommand.startsWith("/") ? loginCommand : "/" + loginCommand;
        if (!message.equals(command) && !message.startsWith(command + " ")) {
            return;
        }
        Player player = event.getPlayer();
        scheduleMenuOpen(player);
    }

    @org.bukkit.event.EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!gamemodeEnabled) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!event.getView().getTitle().equals(colorize(menuTitle))) {
            return;
        }
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        if (event.getRawSlot() == getConfig().getInt("menu.survival.slot")) {
            handleChoice(player, "survival", GameMode.SURVIVAL);
        } else if (event.getRawSlot() == getConfig().getInt("menu.creative.slot")) {
            handleChoice(player, "creative", GameMode.CREATIVE);
        }
    }

    @org.bukkit.event.EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!keepInventoryOnDeath) {
            return;
        }
        Player player = event.getEntity();
        pendingDeathInventories.put(player.getUniqueId(), new StoredInventory(
                player.getInventory().getContents(),
                player.getInventory().getArmorContents(),
                player.getInventory().getExtraContents()
        ));
        event.setKeepInventory(true);
        event.getDrops().clear();
    }

    @org.bukkit.event.EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!keepInventoryOnDeath) {
            return;
        }
        Player player = event.getPlayer();
        StoredInventory stored = pendingDeathInventories.remove(player.getUniqueId());
        if (stored == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.getInventory().setContents(stored.contents());
            player.getInventory().setArmorContents(stored.armor());
            player.getInventory().setExtraContents(stored.extra());
            player.updateInventory();
        }, deathRestoreDelay);
    }

    @org.bukkit.event.EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!isShopInventory(event.getView().getTitle()) && !isCategoryInventory(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);
        ShopSession session = shopSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.view() == ShopView.CATEGORIES) {
            handleCategoryClick(player, event.getRawSlot());
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45) {
            openShop(player, session.category(), Math.max(0, session.page() - 1));
            return;
        }
        if (slot == 53) {
            openShop(player, session.category(), Math.min(getMaxPage(session.category()), session.page() + 1));
            return;
        }
        if (slot == 49) {
            openCategoryMenu(player);
            return;
        }
        if (slot == 48) {
            return;
        }
        if (slot < 0 || slot >= shopItemsPerPage) {
            return;
        }
        List<Material> materials = getMaterialsForCategory(session.category());
        int index = session.page() * shopItemsPerPage + slot;
        if (index >= materials.size()) {
            return;
        }
        Material material = materials.get(index);
        if (material == null || material == Material.AIR) {
            return;
        }
        ClickType click = event.getClick();
        int amount = click.isShiftClick() ? Math.max(1, material.getMaxStackSize()) : 1;
        if (click.isLeftClick()) {
            handleBuy(player, material, amount);
        } else if (click.isRightClick()) {
            handleSell(player, material, amount);
        }
        openShop(player, session.category(), session.page());
    }

    @org.bukkit.event.EventHandler
    public void onShopDrag(InventoryDragEvent event) {
        if (isShopInventory(event.getView().getTitle()) || isCategoryInventory(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ENGLISH);
        if (name.equals("sp")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("messages.only-players", "&cOnly players can use this command."));
                return true;
            }
            if (!shopEnabled) {
                player.sendMessage(message("messages.shop.disabled", "&cShop is disabled."));
                return true;
            }
            openCategoryMenu(player);
            return true;
        }
        if (name.equals("discord")) {
            String link = getConfig().getString("links.discord", "https://discord.gg/JTpfchCFDV");
            if (!(sender instanceof Player player)) {
                sender.sendMessage(link);
                return true;
            }
            player.sendMessage(message("messages.discord.copy", "&bDiscord Link &7(Click to Copy)"));
            player.sendMessage(Component.text(link).clickEvent(ClickEvent.copyToClipboard(link)));
            return true;
        }
        if (name.equals("ts")) {
            String address = getConfig().getString("links.teamspeak", "5.42.217.200:12861");
            if (!(sender instanceof Player player)) {
                sender.sendMessage(address);
                return true;
            }
            player.sendMessage(message("messages.teamspeak.copy", "&aTeamSpeak IP &7(Click to Copy)"));
            player.sendMessage(Component.text(address).clickEvent(ClickEvent.copyToClipboard(address)));
            return true;
        }
        if (name.equals("daily")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("messages.only-players", "&cOnly players can use this command."));
                return true;
            }
            PlayerStats stats = ensureStats(player);
            String today = currentDate();
            resetDailyIfNeeded(player, stats, today);
            boolean completed = areAllMissionsCompleted(stats);
            String status = completed
                    ? message("daily.status.completed", "&aAnjam Shode")
                    : message("daily.status.in-progress", "&eDar Hal Anjam");
            List<String> header = dailyCommandHeaderLines.isEmpty() ? dailyCommandLines : dailyCommandHeaderLines;
            if (header.isEmpty()) {
                header = List.of("&b&lDaily Missions");
            }
            String missionLine = dailyCommandMissionLine == null || dailyCommandMissionLine.isBlank()
                    ? "&7- &f%mission% &7(%progress%/%target%) &7| &e%reward% %currency%"
                    : dailyCommandMissionLine;
            for (String line : header) {
                player.sendMessage(colorize(line
                        .replace("%streak%", String.valueOf(stats.dailyStreak))
                        .replace("%status%", status)));
            }
            for (int index : stats.dailyAssigned) {
                DailyMission mission = getMissionByIndex(index);
                if (mission == null) {
                    continue;
                }
                long progress = getProgress(stats, index);
                String progressText = formatDailyProgress(progress, mission);
                String targetText = formatDailyTarget(mission);
                player.sendMessage(colorize(missionLine
                        .replace("%mission%", mission.display)
                        .replace("%progress%", progressText)
                        .replace("%target%", targetText)
                        .replace("%reward%", String.valueOf(mission.reward))
                        .replace("%currency%", shopCurrency)));
            }
            for (String line : dailyCommandFooterLines) {
                player.sendMessage(colorize(line
                        .replace("%streak%", String.valueOf(stats.dailyStreak))
                        .replace("%status%", status)));
            }
            return true;
        }
        return false;
    }

    private void loadShopSettings() {
        shopEnabled = getConfig().getBoolean("shop.enabled", true);
        shopTitle = getConfig().getString("shop.title", "&aServer Shop");
        shopCurrency = getConfig().getString("shop.currency", "Ccoin");
        shopStartBalance = Math.max(0, getConfig().getInt("shop.start-balance", 0));
        shopBuyMultiplier = Math.max(0.1, getConfig().getDouble("shop.buy-multiplier", 2.0));
        shopSellMultiplier = Math.max(0.1, getConfig().getDouble("shop.sell-multiplier", 1.0));
        shopItemsPerPage = Math.max(9, Math.min(45, getConfig().getInt("shop.items-per-page", 45)));
        shopCategoriesAuto = getConfig().getBoolean("shop.categories-auto", true);
        loadPriceOverrides();
        buildMaterialList();
        loadCategories();
        loadBalances();
    }

    private void loadPriceOverrides() {
        priceOverrides.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("shop.price-overrides");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isItem() || material.isLegacy()) {
                continue;
            }
            int value = Math.max(1, section.getInt(key, 1));
            priceOverrides.put(material, value);
        }
    }

    private void buildMaterialList() {
        shopMaterials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isLegacy() || material == Material.AIR) {
                continue;
            }
            shopMaterials.add(material);
        }
        shopMaterials.sort(Comparator.comparing(Material::name));
    }

    private void loadCategories() {
        if (shopCategoriesAuto) {
            shopCategories = buildAutoCategories();
            return;
        }
        ConfigurationSection section = getConfig().getConfigurationSection("shop.categories");
        if (section != null) {
            List<ShopCategory> categories = new ArrayList<>();
            for (String key : section.getKeys(false)) {
                ConfigurationSection categorySection = section.getConfigurationSection(key);
                if (categorySection == null) {
                    continue;
                }
                String name = categorySection.getString("name", key);
                String iconName = categorySection.getString("icon", "CHEST");
                Material icon = Material.matchMaterial(iconName);
                if (icon == null || !icon.isItem() || icon.isLegacy()) {
                    icon = Material.CHEST;
                }
                List<Material> materials = categorySection.getStringList("items").stream()
                        .map(Material::matchMaterial)
                        .filter(material -> material != null && material.isItem() && !material.isLegacy() && material != Material.AIR)
                        .distinct()
                        .sorted(Comparator.comparing(Material::name))
                        .collect(Collectors.toList());
                if (!materials.isEmpty()) {
                    categories.add(new ShopCategory(name, icon, materials));
                }
            }
            if (!categories.isEmpty()) {
                shopCategories = categories;
                return;
            }
        }
        shopCategories = List.of(
                new ShopCategory("All Items", Material.CHEST, buildCategoryMaterials(material -> true)),
                new ShopCategory("Blocks", Material.GRASS_BLOCK, buildCategoryMaterials(material -> material.isBlock() && material.isItem())),
                new ShopCategory("Food", Material.APPLE, buildCategoryMaterials(Material::isEdible)),
                new ShopCategory("Redstone", Material.REDSTONE, buildCategoryMaterials(material -> material.name().contains("REDSTONE")
                        || material.name().contains("REPEATER")
                        || material.name().contains("COMPARATOR")
                        || material.name().contains("PISTON")
                        || material.name().contains("OBSERVER")
                        || material.name().contains("DISPENSER")
                        || material.name().contains("DROPPER")
                        || material.name().contains("HOPPER")
                        || material.name().contains("TARGET")
                        || material.name().contains("LECTERN")
                        || material.name().contains("NOTE_BLOCK")
                        || material.name().contains("SCULK_SENSOR"))),
                new ShopCategory("Tools", Material.IRON_PICKAXE, buildCategoryMaterials(material -> material.name().contains("PICKAXE")
                        || material.name().contains("SHOVEL")
                        || material.name().contains("HOE")
                        || material.name().contains("AXE")
                        || material.name().contains("COMPASS")
                        || material.name().contains("CLOCK"))),
                new ShopCategory("Combat", Material.DIAMOND_SWORD, buildCategoryMaterials(material -> material.name().contains("SWORD")
                        || material.name().contains("BOW")
                        || material.name().contains("CROSSBOW")
                        || material.name().contains("TRIDENT")
                        || material.name().contains("SHIELD")
                        || material.name().contains("HELMET")
                        || material.name().contains("CHESTPLATE")
                        || material.name().contains("LEGGINGS")
                        || material.name().contains("BOOTS"))),
                new ShopCategory("Decor", Material.PAINTING, buildCategoryMaterials(material -> material.name().contains("BANNER")
                        || material.name().contains("CARPET")
                        || material.name().contains("PAINTING")
                        || material.name().contains("FRAME")
                        || material.name().contains("LANTERN")
                        || material.name().contains("TORCH")
                        || material.name().contains("CANDLE")
                        || material.name().contains("POTTED")
                        || material.name().contains("FLOWER")))
        );
    }

    private List<ShopCategory> buildAutoCategories() {
        List<ShopCategory> categories = new ArrayList<>();
        categories.add(new ShopCategory("All Items", Material.CHEST, new ArrayList<>(shopMaterials)));

        List<Material> redstone = new ArrayList<>();
        List<Material> combat = new ArrayList<>();
        List<Material> tools = new ArrayList<>();
        List<Material> food = new ArrayList<>();
        List<Material> farming = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        List<Material> utility = new ArrayList<>();
        List<Material> decor = new ArrayList<>();
        List<Material> blocks = new ArrayList<>();
        List<Material> misc = new ArrayList<>();

        for (Material material : shopMaterials) {
            String name = material.name();
            if (isRedstone(material, name)) {
                redstone.add(material);
            } else if (isCombat(material, name)) {
                combat.add(material);
            } else if (isTool(material, name)) {
                tools.add(material);
            } else if (material.isEdible()) {
                food.add(material);
            } else if (isFarming(material, name)) {
                farming.add(material);
            } else if (isMaterialResource(name)) {
                materials.add(material);
            } else if (isUtility(material, name)) {
                utility.add(material);
            } else if (isDecor(name)) {
                decor.add(material);
            } else if (isBuildingBlock(material, name)) {
                blocks.add(material);
            } else {
                misc.add(material);
            }
        }

        addCategory(categories, "Blocks", Material.GRASS_BLOCK, blocks);
        addCategory(categories, "Redstone", Material.REDSTONE, redstone);
        addCategory(categories, "Combat", Material.DIAMOND_SWORD, combat);
        addCategory(categories, "Tools", Material.IRON_PICKAXE, tools);
        addCategory(categories, "Food", Material.BREAD, food);
        addCategory(categories, "Farming", Material.WHEAT, farming);
        addCategory(categories, "Materials", Material.IRON_INGOT, materials);
        addCategory(categories, "Utility", Material.CHEST, utility);
        addCategory(categories, "Decor", Material.PAINTING, decor);
        addCategory(categories, "Misc", Material.BARREL, misc);

        return categories;
    }

    private void addCategory(List<ShopCategory> categories, String name, Material icon, List<Material> materials) {
        if (materials.isEmpty()) {
            return;
        }
        materials.sort(Comparator.comparing(Material::name));
        categories.add(new ShopCategory(name, icon, materials));
    }

    private boolean isRedstone(Material material, String name) {
        return name.contains("REDSTONE")
                || name.contains("REPEATER")
                || name.contains("COMPARATOR")
                || name.contains("PISTON")
                || name.contains("OBSERVER")
                || name.contains("DISPENSER")
                || name.contains("DROPPER")
                || name.contains("HOPPER")
                || name.contains("TARGET")
                || name.contains("LECTERN")
                || name.contains("NOTE_BLOCK")
                || name.contains("SCULK_SENSOR")
                || name.contains("TRIPWIRE")
                || name.contains("DAYLIGHT_DETECTOR")
                || name.contains("REDSTONE_TORCH")
                || name.contains("LEVER")
                || name.contains("BUTTON")
                || name.contains("PRESSURE_PLATE");
    }

    private boolean isCombat(Material material, String name) {
        return name.contains("SWORD")
                || name.contains("BOW")
                || name.contains("CROSSBOW")
                || name.contains("TRIDENT")
                || name.contains("SHIELD")
                || name.contains("HELMET")
                || name.contains("CHESTPLATE")
                || name.contains("LEGGINGS")
                || name.contains("BOOTS")
                || name.contains("ARROW")
                || name.contains("TIPPED_ARROW")
                || name.contains("SPECTRAL_ARROW");
    }

    private boolean isTool(Material material, String name) {
        return name.contains("PICKAXE")
                || name.contains("SHOVEL")
                || name.contains("HOE")
                || name.contains("AXE")
                || name.contains("SHEARS")
                || name.contains("FISHING_ROD")
                || name.contains("FLINT_AND_STEEL")
                || name.contains("COMPASS")
                || name.contains("CLOCK");
    }

    private boolean isFarming(Material material, String name) {
        return name.contains("SEEDS")
                || name.contains("SAPLING")
                || name.contains("WHEAT")
                || name.contains("CARROT")
                || name.contains("POTATO")
                || name.contains("BEETROOT")
                || name.contains("SUGAR_CANE")
                || name.contains("BAMBOO")
                || name.contains("KELP")
                || name.contains("CACTUS")
                || name.contains("MELON")
                || name.contains("PUMPKIN");
    }

    private boolean isMaterialResource(String name) {
        return name.contains("INGOT")
                || name.contains("NUGGET")
                || name.contains("GEM")
                || name.contains("DIAMOND")
                || name.contains("EMERALD")
                || name.contains("LAPIS")
                || name.contains("QUARTZ")
                || name.contains("COAL")
                || name.contains("AMETHYST")
                || name.contains("COPPER")
                || name.contains("RAW_");
    }

    private boolean isUtility(Material material, String name) {
        return name.contains("CHEST")
                || name.contains("BARREL")
                || name.contains("CRAFTING_TABLE")
                || name.contains("FURNACE")
                || name.contains("SMOKER")
                || name.contains("BLAST_FURNACE")
                || name.contains("ENCHANTING_TABLE")
                || name.contains("ANVIL")
                || name.contains("BREWING_STAND")
                || name.contains("GRINDSTONE")
                || name.contains("LOOM")
                || name.contains("SMITHING_TABLE")
                || name.contains("STONECUTTER")
                || name.contains("CARTOGRAPHY_TABLE")
                || name.contains("FLETCHING_TABLE")
                || name.contains("ENDER_CHEST")
                || name.contains("SHULKER_BOX")
                || name.contains("BUCKET")
                || name.contains("TORCH")
                || name.contains("LANTERN")
                || name.contains("BED");
    }

    private boolean isDecor(String name) {
        return name.contains("BANNER")
                || name.contains("CARPET")
                || name.contains("PAINTING")
                || name.contains("FRAME")
                || name.contains("CANDLE")
                || name.contains("FLOWER")
                || name.contains("POTTED")
                || name.contains("SIGN")
                || name.contains("HEAD")
                || name.contains("POT");
    }

    private boolean isBuildingBlock(Material material, String name) {
        if (!material.isBlock() || !material.isItem()) {
            return false;
        }
        if (isUtility(material, name) || isDecor(name) || isRedstone(material, name) || isFarming(material, name) || isTool(material, name) || isCombat(material, name)) {
            return false;
        }
        if (Tag.DOORS.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.BUTTONS.isTagged(material)
                || Tag.PRESSURE_PLATES.isTagged(material)
                || Tag.SAPLINGS.isTagged(material)
                || Tag.FLOWERS.isTagged(material)
                || Tag.BEDS.isTagged(material)
                || Tag.BANNERS.isTagged(material)
                || Tag.SIGNS.isTagged(material)) {
            return false;
        }
        return true;
    }

    private void openCategoryMenu(Player player) {
        ensureBalance(player);
        Inventory inventory = Bukkit.createInventory(player, 27, buildCategoryTitle());
        int slot = 10;
        for (ShopCategory category : shopCategories) {
            inventory.setItem(slot, buildCategoryItem(category));
            slot = slot == 16 ? 19 : slot + 1;
            if (slot >= 26) {
                break;
            }
        }
        inventory.setItem(26, balanceItem(player));
        player.openInventory(inventory);
        shopSessions.put(player.getUniqueId(), new ShopSession(ShopView.CATEGORIES, null, 0));
    }

    private void handleCategoryClick(Player player, int slot) {
        if (slot == 26) {
            return;
        }
        List<Integer> slots = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
        int index = slots.indexOf(slot);
        if (index < 0 || index >= shopCategories.size()) {
            return;
        }
        ShopCategory category = shopCategories.get(index);
        openShop(player, category, 0);
    }

    private ItemStack buildCategoryItem(ShopCategory category) {
        ItemStack item = new ItemStack(category.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize("&e" + category.name()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openShop(Player player, ShopCategory category, int page) {
        ensureBalance(player);
        int maxPage = getMaxPage(category);
        int safePage = Math.max(0, Math.min(maxPage, page));
        Inventory inventory = Bukkit.createInventory(player, 54, buildShopTitle(category, safePage, maxPage));
        List<Material> materials = getMaterialsForCategory(category);
        int start = safePage * shopItemsPerPage;
        int end = Math.min(materials.size(), start + shopItemsPerPage);
        int slot = 0;
        for (int i = start; i < end; i++) {
            Material material = materials.get(i);
            inventory.setItem(slot++, buildShopItem(material, player));
        }
        inventory.setItem(45, navItem(Material.ARROW, "&aPrev"));
        inventory.setItem(48, balanceItem(player));
        inventory.setItem(49, navItem(Material.BARRIER, "&cCategories"));
        inventory.setItem(53, navItem(Material.ARROW, "&aNext"));
        player.openInventory(inventory);
        shopSessions.put(player.getUniqueId(), new ShopSession(ShopView.ITEMS, category, safePage));
    }

    private ItemStack buildShopItem(Material material, Player player) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize("&f" + material.name().toLowerCase(Locale.ENGLISH).replace("_", " ")));
            int buy = getBuyPrice(material);
            int sell = getSellPrice(material);
            List<String> lore = new ArrayList<>();
            lore.add(colorize("&7Buy: &a" + buy + " " + shopCurrency));
            lore.add(colorize("&7Sell: &e" + sell + " " + shopCurrency));
            lore.add(colorize("&7Left: buy 1 | Right: sell 1"));
            lore.add(colorize("&7Shift: buy/sell stack"));
            lore.add(colorize("&7Balance: &b" + getBalance(player) + " " + shopCurrency));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack balanceItem(Player player) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize("&bBalance"));
            List<String> lore = List.of(colorize("&f" + getBalance(player) + " " + shopCurrency));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String buildShopTitle(ShopCategory category, int page, int maxPage) {
        String name = category == null ? "All Items" : category.name();
        return colorize(shopTitle) + " &7[" + name + "] &7(" + (page + 1) + "/" + (maxPage + 1) + ")";
    }

    private String buildCategoryTitle() {
        return colorize(shopTitle) + " &7[Categories]";
    }

    private boolean isShopInventory(String title) {
        return title != null && title.startsWith(colorize(shopTitle));
    }

    private boolean isCategoryInventory(String title) {
        return title != null && title.startsWith(colorize(shopTitle)) && title.contains("[Categories]");
    }

    private int getMaxPage(ShopCategory category) {
        List<Material> materials = getMaterialsForCategory(category);
        if (materials.isEmpty()) {
            return 0;
        }
        return Math.max(0, (materials.size() - 1) / shopItemsPerPage);
    }

    private List<Material> getMaterialsForCategory(ShopCategory category) {
        if (category == null) {
            return shopMaterials;
        }
        return category.materials();
    }

    private List<Material> buildCategoryMaterials(java.util.function.Predicate<Material> filter) {
        return shopMaterials.stream()
                .filter(filter)
                .collect(Collectors.toList());
    }

    private void handleBuy(Player player, Material material, int amount) {
        int price = getBuyPrice(material) * amount;
        if (price <= 0) {
            return;
        }
        int balance = getBalance(player);
        if (balance < price) {
            player.sendMessage(formatMessage("messages.shop.not-enough", "&cNot enough %currency%.", Map.of(
                    "%currency%", shopCurrency
            )));
            return;
        }
        ItemStack item = new ItemStack(material, amount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            player.sendMessage(message("messages.shop.inventory-full", "&cInventory full."));
            return;
        }
        setBalance(player, balance - price);
        player.sendMessage(formatMessage("messages.shop.bought", "&aBought %amount% %item% for %price% %currency%.", Map.of(
                "%amount%", String.valueOf(amount),
                "%item%", formatMaterial(material),
                "%price%", String.valueOf(price),
                "%currency%", shopCurrency
        )));
        if (dailyEnabled) {
            PlayerStats stats = ensureStats(player);
            resetDailyIfNeeded(player, stats, currentDate());
            addDailyProgressForType(player, stats, DailyMissionType.BUY_ITEMS, amount);
        }
    }

    private void handleSell(Player player, Material material, int amount) {
        int available = countItem(player, material);
        if (available <= 0) {
            player.sendMessage(message("messages.shop.no-item", "&cYou don't have this item."));
            return;
        }
        int sellAmount = Math.min(available, amount);
        int value = getSellPrice(material) * sellAmount;
        if (value <= 0) {
            return;
        }
        removeItem(player, material, sellAmount);
        setBalance(player, getBalance(player) + value);
        player.sendMessage(formatMessage("messages.shop.sold", "&aSold %amount% %item% for %price% %currency%.", Map.of(
                "%amount%", String.valueOf(sellAmount),
                "%item%", formatMaterial(material),
                "%price%", String.valueOf(value),
                "%currency%", shopCurrency
        )));
        if (dailyEnabled) {
            PlayerStats stats = ensureStats(player);
            resetDailyIfNeeded(player, stats, currentDate());
            addDailyProgressForType(player, stats, DailyMissionType.SELL_ITEMS, sellAmount);
        }
    }

    private void handleChoice(Player player, String modeKey, GameMode gameMode) {
        teleportToWorld(player, modeKey, gameMode);
        player.closeInventory();
        player.sendMessage(colorize(getConfig().getString("messages.choose-" + modeKey)));
        cancelPending(player.getUniqueId());
    }

    private void openMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(player, menuSize, colorize(menuTitle));
        inventory.setItem(getConfig().getInt("menu.survival.slot"), buildMenuItem("menu.survival", Material.IRON_SWORD));
        inventory.setItem(getConfig().getInt("menu.creative.slot"), buildMenuItem("menu.creative", Material.BRICKS));
        if (getConfig().contains("menu.info")) {
            int slot = getConfig().getInt("menu.info.slot", 22);
            String materialName = getConfig().getString("menu.info.material", "NETHER_STAR");
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                material = Material.NETHER_STAR;
            }
            inventory.setItem(slot, buildMenuItem("menu.info", material));
        }
        player.openInventory(inventory);
    }

    private ItemStack buildMenuItem(String path, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(getConfig().getString(path + ".name")));
            List<String> lore = getConfig().getStringList(path + ".lore");
            meta.setLore(lore.stream().map(this::colorize).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void precreateAllWorlds() {
        String lobby = getConfig().getString("worlds.lobby");
        String survival = getConfig().getString("worlds.survival");
        String creative = getConfig().getString("worlds.creative");
        ensureWorld(lobby);
        ensureWorld(survival);
        ensureWorld(creative);
    }

    private void ensureWorld(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        World world = Bukkit.getWorld(name);
        if (world == null) {
            Bukkit.broadcastMessage(colorize(getConfig().getString("messages.world-creating")));
            Bukkit.createWorld(new WorldCreator(name));
        }
    }

    private void teleportToWorld(Player player, String worldKey, GameMode gameMode) {
        String worldName = getConfig().getString("worlds." + worldKey);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }
        Location target = resolveSpawn(world, worldKey);
        teleportSafely(player, world, target, gameMode);
    }

    private void scheduleMenuOpen(Player player) {
        UUID playerId = player.getUniqueId();
        cancelPending(playerId);
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            private int attempts = 0;

            @Override
            public void run() {
                attempts++;
                if (!player.isOnline()) {
                    cancelPending(playerId);
                    return;
                }
                if (player.getOpenInventory().getTitle().equals(colorize(menuTitle))) {
                    cancelPending(playerId);
                    return;
                }
                openMenu(player);
                if (player.getOpenInventory().getTitle().equals(colorize(menuTitle))) {
                    cancelPending(playerId);
                    return;
                }
                if (attempts >= loginMaxAttempts) {
                    cancelPending(playerId);
                }
            }
        }, loginOpenDelay, loginCheckInterval);
        pendingOpenTasks.put(playerId, taskId);
    }

    private void cancelPending(UUID playerId) {
        Integer taskId = pendingOpenTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    private void scheduleLobbyTeleport(Player player) {
        UUID playerId = player.getUniqueId();
        cancelLobbyPending(playerId);
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            private int attempts = 0;

            @Override
            public void run() {
                attempts++;
                if (!player.isOnline()) {
                    cancelLobbyPending(playerId);
                    return;
                }
                String lobbyWorld = getConfig().getString("worlds.lobby");
                World world = Bukkit.getWorld(lobbyWorld);
                if (world != null) {
                    teleportToWorld(player, "lobby", GameMode.ADVENTURE);
                    cancelLobbyPending(playerId);
                    return;
                }
                if (attempts == 1) {
                    player.sendMessage(colorize(getConfig().getString("messages.lobby-wait")));
                }
                if (attempts >= lobbyTeleportMaxAttempts) {
                    cancelLobbyPending(playerId);
                }
            }
        }, 1L, lobbyTeleportInterval);
        pendingLobbyTasks.put(playerId, taskId);
    }

    private void cancelLobbyPending(UUID playerId) {
        Integer taskId = pendingLobbyTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    private void scheduleWelcome(Player player) {
        if (!welcomeEnabled) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            sendWelcome(player);
        }, welcomeDelay);
    }

    private void sendWelcome(Player player) {
        String message = colorize(welcomeMessage);
        String subtitle = colorize(welcomeSubtitle);
        if ("TITLE".equalsIgnoreCase(welcomeType)) {
            player.sendTitle(message, subtitle, 10, 40, 10);
        } else if ("ACTION_BAR".equalsIgnoreCase(welcomeType)) {
            player.sendActionBar(message);
        } else {
            player.sendActionBar(message);
        }
        if (welcomeSound != null && !welcomeSound.isBlank()) {
            try {
                Sound sound = Sound.valueOf(welcomeSound.toUpperCase(Locale.ENGLISH));
                player.playSound(player.getLocation(), sound, welcomeVolume, welcomePitch);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private Location resolveSpawn(World world, String worldKey) {
        String base = "worlds." + worldKey + "-spawn";
        if (getConfig().contains(base + ".x") && getConfig().contains(base + ".z")) {
            double x = getConfig().getDouble(base + ".x");
            double y = getConfig().getDouble(base + ".y", world.getSpawnLocation().getY());
            double z = getConfig().getDouble(base + ".z");
            float yaw = (float) getConfig().getDouble(base + ".yaw", world.getSpawnLocation().getYaw());
            float pitch = (float) getConfig().getDouble(base + ".pitch", world.getSpawnLocation().getPitch());
            return new Location(world, x, y, z, yaw, pitch);
        }
        Location spawn = world.getSpawnLocation();
        int highest = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ());
        int safeY = Math.max(world.getMinHeight() + 2, highest + 1);
        return new Location(world, spawn.getX(), safeY, spawn.getZ(), spawn.getYaw(), spawn.getPitch());
    }

    private void teleportSafely(Player player, World world, Location target, GameMode gameMode) {
        Runnable apply = () -> {
            if (!player.isOnline()) {
                return;
            }
            player.teleport(target);
            player.setGameMode(gameMode);
            player.setFallDistance(0);
            if (teleportInvulnerableTicks > 0) {
                player.setInvulnerable(true);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) {
                        player.setInvulnerable(false);
                    }
                }, teleportInvulnerableTicks);
            }
        };
        try {
            CompletableFuture<Chunk> future = world.getChunkAtAsync(target);
            future.thenRun(() -> Bukkit.getScheduler().runTask(this, apply));
        } catch (Throwable ignored) {
            world.getChunkAt(target).load();
            Bukkit.getScheduler().runTask(this, apply);
        }
    }

    private void applyJoinProtection(Player player) {
        if (joinProtectionTicks <= 0) {
            return;
        }
        player.setFallDistance(0);
        if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        player.setFoodLevel(20);
        player.setInvulnerable(true);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                player.setInvulnerable(false);
            }
        }, joinProtectionTicks);
    }

    private int getBuyPrice(Material material) {
        int base = getBasePrice(material);
        return Math.max(1, (int) Math.round(base * shopBuyMultiplier));
    }

    private int getSellPrice(Material material) {
        int base = getBasePrice(material);
        return Math.max(1, (int) Math.round(base * shopSellMultiplier));
    }

    private int getBasePrice(Material material) {
        Integer override = priceOverrides.get(material);
        if (override != null) {
            return override;
        }
        String name = material.name();
        if (isFlower(material)) {
            return 5;
        }
        if (name.contains("NETHERITE")) {
            return 2000;
        }
        if (name.contains("DIAMOND")) {
            return 500;
        }
        if (name.contains("EMERALD")) {
            return 400;
        }
        if (name.contains("GOLD")) {
            return 200;
        }
        if (name.contains("IRON")) {
            return 120;
        }
        if (name.contains("COPPER")) {
            return 60;
        }
        if (name.contains("REDSTONE")) {
            return 40;
        }
        if (name.contains("LAPIS")) {
            return 35;
        }
        if (name.contains("COAL")) {
            return 30;
        }
        if (name.contains("QUARTZ")) {
            return 50;
        }
        if (name.contains("SHULKER") || name.contains("ELYTRA")) {
            return 1500;
        }
        int maxStack = material.getMaxStackSize();
        if (maxStack <= 1) {
            return 120;
        }
        if (maxStack <= 16) {
            return 35;
        }
        return 12;
    }

    private boolean isFlower(Material material) {
        Set<Material> flowers = new HashSet<>(List.of(
                Material.DANDELION,
                Material.POPPY,
                Material.BLUE_ORCHID,
                Material.ALLIUM,
                Material.AZURE_BLUET,
                Material.RED_TULIP,
                Material.ORANGE_TULIP,
                Material.WHITE_TULIP,
                Material.PINK_TULIP,
                Material.OXEYE_DAISY,
                Material.CORNFLOWER,
                Material.LILY_OF_THE_VALLEY,
                Material.TORCHFLOWER,
                Material.PINK_PETALS
        ));
        return flowers.contains(material) || material.name().contains("FLOWER");
    }

    private int countItem(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == material) {
            count += offHand.getAmount();
        }
        return count;
    }

    private void removeItem(Player player, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != material) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) {
                player.getInventory().setItem(i, null);
            }
            remaining -= take;
            if (remaining <= 0) {
                break;
            }
        }
        if (remaining > 0) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand != null && offHand.getType() == material) {
                int take = Math.min(remaining, offHand.getAmount());
                offHand.setAmount(offHand.getAmount() - take);
                if (offHand.getAmount() <= 0) {
                    player.getInventory().setItemInOffHand(null);
                }
            }
        }
        player.updateInventory();
    }

    private void ensureBalance(Player player) {
        balances.computeIfAbsent(player.getUniqueId(), key -> shopStartBalance);
    }

    public int getBalance(Player player) {
        return balances.getOrDefault(player.getUniqueId(), shopStartBalance);
    }

    private void setBalance(Player player, int value) {
        balances.put(player.getUniqueId(), Math.max(0, value));
        getConfig().set("balances." + player.getUniqueId(), Math.max(0, value));
        saveConfig();
    }

    private void loadBalances() {
        ConfigurationSection section = getConfig().getConfigurationSection("balances");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                balances.put(uuid, Math.max(0, section.getInt(key, shopStartBalance)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private String formatMaterial(Material material) {
        return material.name().toLowerCase(Locale.ENGLISH).replace("_", " ");
    }

    private String message(String path, String fallback) {
        String raw = getConfig().getString(path, fallback);
        return colorize(raw == null ? "" : raw);
    }

    private String formatMessage(String path, String fallback, Map<String, String> values) {
        String raw = getConfig().getString(path, fallback);
        String result = raw == null ? "" : raw;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return colorize(result);
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private record ShopSession(ShopView view, ShopCategory category, int page) {
    }

    private enum ShopView {
        CATEGORIES,
        ITEMS
    }

    private record ShopCategory(String name, Material icon, List<Material> materials) {
    }

    private void loadBroadcastSettings() {
        broadcastEnabled = getConfig().getBoolean("broadcast.enabled", false);
        broadcastIntervalTicks = Math.max(20, getConfig().getInt("broadcast.interval-ticks", 400));
        broadcastMessages = getConfig().getStringList("broadcast.messages");
    }

    private void loadPlaytimeSettings() {
        playtimeEnabled = getConfig().getBoolean("playtime.enabled", true);
        playtimeTrackingIntervalTicks = Math.max(20, getConfig().getInt("playtime.tracking-interval-ticks", 1200));
        playtimeRewardIntervalSeconds = Math.max(60, getConfig().getInt("playtime.reward-interval-seconds", 3600));
        playtimeRewardAmount = Math.max(0, getConfig().getInt("playtime.reward-amount", 30));
        playtimeRewardMessage = getConfig().getString("playtime.reward-message", "&aPlaytime Reward: &e+%amount% %currency%");
        dataAutosaveTicks = Math.max(200, getConfig().getInt("data.autosave-ticks", 6000));
    }

    private void loadDailySettings() {
        dailyEnabled = getConfig().getBoolean("daily.enabled", true);
        dailyPlaytimeTargetSeconds = Math.max(300, getConfig().getInt("daily.playtime-target-minutes", 60) * 60);
        dailyRewardAmount = Math.max(0, getConfig().getInt("daily.reward-amount", 60));
        dailyCompleteMessage = getConfig().getString("daily.complete-message", "&bDaily Mission Complete! &f%mission% &7| &a+%amount% %currency% &7| Streak: &d%streak%");
        dailyAllCompleteMessage = getConfig().getString("daily.all-complete-message", "&aAll Daily Missions Complete! &7Streak: &d%streak%");
        dailyCommandLines = getConfig().getStringList("daily.command-lines");
        dailyCommandHeaderLines = getConfig().getStringList("daily.command-header-lines");
        dailyCommandFooterLines = getConfig().getStringList("daily.command-footer-lines");
        dailyCommandMissionLine = getConfig().getString("daily.command-mission-line", "&7- &f%mission% &7(%progress%/%target%) &7| &e%reward% %currency%");
        dailyMissionsPerDayMin = Math.max(1, getConfig().getInt("daily.missions-per-day-min", 2));
        dailyMissionsPerDayMax = Math.max(dailyMissionsPerDayMin, getConfig().getInt("daily.missions-per-day-max", 4));
        dailyMissions = loadDailyMissions();
    }

    private void loadMenuSettings() {
        gamemodeEnabled = getConfig().getBoolean("gamemode.enabled", true);
        menuTitle = getConfig().getString("menu.title", "&b&lChoose Your Mode");
        menuSize = Math.max(9, Math.min(54, getConfig().getInt("menu.size", 27)));
        loginCommand = getConfig().getString("login.command", "/login");
        loginOpenDelay = Math.max(1, getConfig().getInt("login.open-delay-ticks", 20));
        loginCheckInterval = Math.max(5, getConfig().getInt("login.check-interval-ticks", 10));
        loginMaxAttempts = Math.max(5, getConfig().getInt("login.max-attempts", 24));
        precreateWorlds = getConfig().getBoolean("worlds.precreate-on-enable", true);
        precreateDelay = Math.max(0, getConfig().getInt("worlds.precreate-delay-ticks", 40));
        lobbyTeleportInterval = Math.max(5, getConfig().getInt("worlds.lobby-teleport-interval-ticks", 20));
        lobbyTeleportMaxAttempts = Math.max(5, getConfig().getInt("worlds.lobby-teleport-max-attempts", 60));
        teleportInvulnerableTicks = Math.max(0, getConfig().getInt("teleport.invulnerable-ticks", 40));
        joinProtectionTicks = Math.max(0, getConfig().getInt("teleport.join-protection-ticks", 80));
        welcomeEnabled = getConfig().getBoolean("welcome.enabled", true);
        welcomeType = getConfig().getString("welcome.type", "ACTION_BAR");
        welcomeMessage = getConfig().getString("welcome.message", "&aWelcome! &f/login &7to choose your mode");
        welcomeSubtitle = getConfig().getString("welcome.subtitle", "");
        welcomeSound = getConfig().getString("welcome.sound", "ENTITY_PLAYER_LEVELUP");
        welcomeVolume = (float) getConfig().getDouble("welcome.volume", 1.0);
        welcomePitch = (float) getConfig().getDouble("welcome.pitch", 1.1);
        welcomeDelay = Math.max(0, getConfig().getInt("welcome.delay-ticks", 10));
        keepInventoryOnDeath = getConfig().getBoolean("death.keep-inventory", false);
        deathRestoreDelay = Math.max(0, getConfig().getInt("death.restore-delay-ticks", 1));
    }

    private void initDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException ignored) {
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadData() {
        if (dataConfig == null) {
            return;
        }
        ConfigurationSection section = dataConfig.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection playerSection = section.getConfigurationSection(key);
            if (playerSection == null) {
                continue;
            }
            PlayerStats stats = new PlayerStats();
            stats.totalPlaySeconds = playerSection.getLong("total-play-seconds", 0L);
            stats.playtimeRemainderSeconds = playerSection.getLong("playtime-remainder-seconds", 0L);
            stats.dailyResetDate = playerSection.getString("daily-reset-date", "");
            stats.lastDailyCompleteDate = playerSection.getString("daily-last-complete-date", "");
            stats.dailyStreak = playerSection.getInt("daily-streak", 0);
            List<?> assignedRaw = playerSection.getList("daily-assigned");
            if (assignedRaw != null) {
                for (Object value : assignedRaw) {
                    int index = toInt(value, -1);
                    if (index >= 0) {
                        stats.dailyAssigned.add(index);
                    }
                }
            }
            ConfigurationSection progressSection = playerSection.getConfigurationSection("daily-progress-map");
            if (progressSection != null) {
                for (String progressKey : progressSection.getKeys(false)) {
                    int index = toInt(progressKey, -1);
                    if (index >= 0) {
                        stats.dailyProgressMap.put(index, progressSection.getLong(progressKey, 0L));
                    }
                }
            }
            playerStats.put(uuid, stats);
        }
    }

    private void saveData() {
        if (dataConfig == null || dataFile == null) {
            return;
        }
        dataConfig.set("players", null);
        for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerStats stats = entry.getValue();
            dataConfig.set(path + ".total-play-seconds", stats.totalPlaySeconds);
            dataConfig.set(path + ".playtime-remainder-seconds", stats.playtimeRemainderSeconds);
            dataConfig.set(path + ".daily-reset-date", stats.dailyResetDate);
            dataConfig.set(path + ".daily-last-complete-date", stats.lastDailyCompleteDate);
            dataConfig.set(path + ".daily-streak", stats.dailyStreak);
            dataConfig.set(path + ".daily-assigned", new ArrayList<>(stats.dailyAssigned));
            dataConfig.set(path + ".daily-progress-map", null);
            for (Map.Entry<Integer, Long> progressEntry : stats.dailyProgressMap.entrySet()) {
                dataConfig.set(path + ".daily-progress-map." + progressEntry.getKey(), progressEntry.getValue());
            }
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException ignored) {
        }
    }

    private void startPlaytimeTracking() {
        stopPlaytimeTracking();
        if (!playtimeEnabled && !dailyEnabled) {
            return;
        }
        int intervalSeconds = Math.max(1, playtimeTrackingIntervalTicks / 20);
        playtimeTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            String today = currentDate();
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerStats stats = ensureStats(player);
                resetDailyIfNeeded(player, stats, today);
                stats.totalPlaySeconds += intervalSeconds;
                if (playtimeEnabled && playtimeRewardAmount > 0) {
                    stats.playtimeRemainderSeconds += intervalSeconds;
                    int rewards = (int) (stats.playtimeRemainderSeconds / playtimeRewardIntervalSeconds);
                    if (rewards > 0) {
                        int payout = rewards * playtimeRewardAmount;
                        stats.playtimeRemainderSeconds = stats.playtimeRemainderSeconds % playtimeRewardIntervalSeconds;
                        setBalance(player, getBalance(player) + payout);
                        player.sendMessage(colorize(playtimeRewardMessage
                                .replace("%amount%", String.valueOf(payout))
                                .replace("%currency%", shopCurrency)
                                .replace("%hours%", String.valueOf(playtimeRewardIntervalSeconds / 3600))));
                    }
                }
                if (dailyEnabled) {
                    addDailyProgressForType(player, stats, DailyMissionType.PLAYTIME_MINUTES, intervalSeconds);
                }
            }
        }, playtimeTrackingIntervalTicks, playtimeTrackingIntervalTicks);
    }

    private void stopPlaytimeTracking() {
        if (playtimeTaskId != -1) {
            Bukkit.getScheduler().cancelTask(playtimeTaskId);
            playtimeTaskId = -1;
        }
    }

    private void startDataAutosave() {
        stopDataAutosave();
        dataSaveTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::saveData, dataAutosaveTicks, dataAutosaveTicks);
    }

    private void stopDataAutosave() {
        if (dataSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(dataSaveTaskId);
            dataSaveTaskId = -1;
        }
    }

    private PlayerStats ensureStats(Player player) {
        return playerStats.computeIfAbsent(player.getUniqueId(), key -> {
            PlayerStats stats = new PlayerStats();
            stats.dailyResetDate = currentDate();
            stats.lastDailyCompleteDate = "";
            return stats;
        });
    }

    private void resetDailyIfNeeded(Player player, PlayerStats stats, String today) {
        boolean needsAssign = stats.dailyAssigned.isEmpty() || !areAssignedIndicesValid(stats);
        if (!today.equals(stats.dailyResetDate)) {
            stats.dailyResetDate = today;
            stats.dailyAssigned.clear();
            stats.dailyProgressMap.clear();
            needsAssign = true;
        }
        if (needsAssign) {
            assignDailyMissions(player.getUniqueId(), stats, today);
        }
    }

    private int calculateNextStreak(PlayerStats stats) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (stats.lastDailyCompleteDate == null || stats.lastDailyCompleteDate.isBlank()) {
            return 1;
        }
        try {
            LocalDate last = LocalDate.parse(stats.lastDailyCompleteDate);
            if (last.plusDays(1).equals(today)) {
                return Math.max(1, stats.dailyStreak + 1);
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private String currentDate() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    private List<DailyMission> loadDailyMissions() {
        List<DailyMission> missions = new ArrayList<>();
        List<Map<?, ?>> raw = getConfig().getMapList("daily.missions");
        for (Map<?, ?> entry : raw) {
            if (entry == null) {
                continue;
            }
            Object rawType = entry.get("type");
            String typeRaw = String.valueOf(rawType == null ? "" : rawType).toUpperCase(Locale.ENGLISH);
            DailyMissionType type = DailyMissionType.from(typeRaw);
            if (type == null) {
                continue;
            }
            int target = Math.max(1, toInt(entry.get("target"), 1));
            int reward = Math.max(0, toInt(entry.get("reward"), dailyRewardAmount));
            Object rawDisplay = entry.get("display");
            String display = String.valueOf(rawDisplay == null ? type.displayName : rawDisplay);
            missions.add(new DailyMission(type, target, reward, display));
        }
        return missions;
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<DailyMission> getMissionPool() {
        if (!dailyMissions.isEmpty()) {
            return dailyMissions;
        }
        return List.of(new DailyMission(DailyMissionType.PLAYTIME_MINUTES, Math.max(1, dailyPlaytimeTargetSeconds / 60), dailyRewardAmount, "Playtime"));
    }

    private DailyMission getMissionByIndex(int index) {
        List<DailyMission> pool = getMissionPool();
        if (index < 0 || index >= pool.size()) {
            return null;
        }
        return pool.get(index);
    }

    private boolean areAssignedIndicesValid(PlayerStats stats) {
        List<DailyMission> pool = getMissionPool();
        if (pool.isEmpty()) {
            return false;
        }
        for (int index : stats.dailyAssigned) {
            if (index < 0 || index >= pool.size()) {
                return false;
            }
        }
        return true;
    }

    private void assignDailyMissions(UUID uuid, PlayerStats stats, String today) {
        List<DailyMission> pool = getMissionPool();
        if (pool.isEmpty()) {
            stats.dailyAssigned.clear();
            stats.dailyProgressMap.clear();
            return;
        }
        int min = Math.max(1, dailyMissionsPerDayMin);
        int max = Math.max(min, dailyMissionsPerDayMax);
        int count = Math.min(pool.size(), min + new Random((uuid.toString() + today).hashCode()).nextInt(max - min + 1));
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            indices.add(i);
        }
        Collections.shuffle(indices, new Random((today + uuid).hashCode()));
        stats.dailyAssigned.clear();
        stats.dailyProgressMap.clear();
        stats.dailyAssigned.addAll(indices.subList(0, count));
    }

    private void addDailyProgressForType(Player player, PlayerStats stats, DailyMissionType type, long amount) {
        if (stats.dailyAssigned.isEmpty()) {
            assignDailyMissions(player.getUniqueId(), stats, currentDate());
        }
        boolean updated = false;
        for (int index : stats.dailyAssigned) {
            DailyMission mission = getMissionByIndex(index);
            if (mission == null || mission.type != type) {
                continue;
            }
            long target = getMissionTargetValue(mission);
            long current = getProgress(stats, index);
            if (current >= target) {
                continue;
            }
            long next = Math.min(target, current + amount);
            stats.dailyProgressMap.put(index, next);
            updated = true;
            if (next >= target) {
                if (mission.reward > 0) {
                    setBalance(player, getBalance(player) + mission.reward);
                }
                player.sendMessage(colorize(dailyCompleteMessage
                        .replace("%amount%", String.valueOf(mission.reward))
                        .replace("%currency%", shopCurrency)
                        .replace("%streak%", String.valueOf(stats.dailyStreak))
                        .replace("%mission%", mission.display)));
            }
        }
        if (updated && areAllMissionsCompleted(stats)) {
            String today = currentDate();
            if (!today.equals(stats.lastDailyCompleteDate)) {
                stats.dailyStreak = calculateNextStreak(stats);
                stats.lastDailyCompleteDate = today;
                if (dailyAllCompleteMessage != null && !dailyAllCompleteMessage.isBlank()) {
                    player.sendMessage(colorize(dailyAllCompleteMessage
                            .replace("%streak%", String.valueOf(stats.dailyStreak))));
                }
            }
        }
    }

    private boolean areAllMissionsCompleted(PlayerStats stats) {
        if (stats.dailyAssigned.isEmpty()) {
            return false;
        }
        for (int index : stats.dailyAssigned) {
            DailyMission mission = getMissionByIndex(index);
            if (mission == null) {
                return false;
            }
            long target = getMissionTargetValue(mission);
            if (getProgress(stats, index) < target) {
                return false;
            }
        }
        return true;
    }

    private long getProgress(PlayerStats stats, int index) {
        return stats.dailyProgressMap.getOrDefault(index, 0L);
    }

    private long getMissionTargetValue(DailyMission mission) {
        if (mission.type == DailyMissionType.PLAYTIME_MINUTES) {
            return Math.max(60, mission.target * 60L);
        }
        return Math.max(1, mission.target);
    }

    private String formatDailyProgress(long progress, DailyMission mission) {
        if (mission.type == DailyMissionType.PLAYTIME_MINUTES) {
            long minutes = Math.max(0, progress / 60);
            return String.valueOf(minutes);
        }
        return String.valueOf(progress);
    }

    private String formatDailyTarget(DailyMission mission) {
        return String.valueOf(Math.max(1, mission.target));
    }

    private enum DailyMissionType {
        PLAYTIME_MINUTES("Playtime"),
        BREAK_BLOCKS("Block Break"),
        KILL_MOBS("Mob Hunt"),
        SELL_ITEMS("Sell Items"),
        BUY_ITEMS("Buy Items");

        private final String displayName;

        DailyMissionType(String displayName) {
            this.displayName = displayName;
        }

        static DailyMissionType from(String raw) {
            for (DailyMissionType type : values()) {
                if (type.name().equalsIgnoreCase(raw)) {
                    return type;
                }
            }
            return null;
        }
    }

    private record DailyMission(DailyMissionType type, int target, int reward, String display) {
    }

    private record StoredInventory(ItemStack[] contents, ItemStack[] armor, ItemStack[] extra) {
    }

    private static class PlayerStats {
        private long totalPlaySeconds;
        private long playtimeRemainderSeconds;
        private final List<Integer> dailyAssigned = new ArrayList<>();
        private final Map<Integer, Long> dailyProgressMap = new HashMap<>();
        private String dailyResetDate = "";
        private String lastDailyCompleteDate = "";
        private int dailyStreak;
    }

    private void startBroadcasts() {
        stopBroadcasts();
        if (!broadcastEnabled || broadcastMessages.isEmpty()) {
            return;
        }
        broadcastTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (broadcastMessages.isEmpty()) {
                return;
            }
            String message = broadcastMessages.get(broadcastIndex % broadcastMessages.size());
            broadcastIndex++;
            if (message == null || message.isBlank()) {
                return;
            }
            String colored = colorize(message);
            Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(colored));
        }, broadcastIntervalTicks, broadcastIntervalTicks);
    }

    private void stopBroadcasts() {
        if (broadcastTaskId != -1) {
            Bukkit.getScheduler().cancelTask(broadcastTaskId);
            broadcastTaskId = -1;
        }
    }
}
