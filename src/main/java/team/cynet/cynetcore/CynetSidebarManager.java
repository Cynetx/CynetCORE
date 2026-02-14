package team.cynet.cynetcore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class CynetSidebarManager {
    private final CynetCorePlugin plugin;
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private int taskId = -1;
    private int animationIndex = 0;
    private static final List<String> BAR_FRAMES = List.of(
            "&b&l▂▄▆█▆▄▂&3&l▂▄▆█▆▄▂",
            "&3&l▂▄▆█▆▄▂&b&l▂▄▆█▆▄▂",
            "&9&l▂▄▆█▆▄▂&b&l▂▄▆█▆▄▂",
            "&b&l▂▄▆█▆▄▂&9&l▂▄▆█▆▄▂"
    );
    private static final List<String> TAB_FRAMES = List.of(
            "&8· &7· &8· &7· &8·",
            "&7· &8· &7· &8· &7·",
            "&8· &7· &8· &7· &8·",
            "&7· &8· &7· &8· &7·"
    );

    public CynetSidebarManager(CynetCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int interval = Math.max(10, plugin.getConfig().getInt("update-interval-ticks", 40));
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::updateAll, 20L, interval);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public void addPlayer(Player player) {
        Sidebar sidebar = new Sidebar(player, plugin);
        sidebars.put(player.getUniqueId(), sidebar);
        sidebar.apply();
    }

    public void removePlayer(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar != null) {
            sidebar.clear();
        }
    }

    public void clear() {
        sidebars.values().forEach(Sidebar::clear);
        sidebars.clear();
    }

    private void updateAll() {
        animationIndex = (animationIndex + 1) % BAR_FRAMES.size();
        sidebars.values().forEach(sidebar -> sidebar.update(animationIndex));
        updateTabListNames(animationIndex);
    }

    private class Sidebar {
        private final Player player;
        private final CynetCorePlugin plugin;
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<String> entries;
        private final Map<Integer, Team> teams = new HashMap<>();

        Sidebar(Player player, CynetCorePlugin plugin) {
            this.player = player;
            this.plugin = plugin;
            this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            this.objective = scoreboard.registerNewObjective("simpleScore", "dummy", "");
            this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            this.entries = createEntries();
        }

        void apply() {
            player.setScoreboard(scoreboard);
            update(animationIndex);
        }

        void update(int frameIndex) {
            objective.setDisplayName(colorize(plugin.getConfig().getString("title", "&b&lSimpleScore")));
            List<String> lines = buildLines(frameIndex);
            int max = Math.min(lines.size(), entries.size());
            for (int i = 0; i < max; i++) {
                String entry = entries.get(i);
                int score = max - i;
                if (!objective.getScore(entry).isScoreSet()) {
                    objective.getScore(entry).setScore(score);
                } else if (objective.getScore(entry).getScore() != score) {
                    objective.getScore(entry).setScore(score);
                }
                Team team = teams.computeIfAbsent(i, key -> {
                    Team created = scoreboard.registerNewTeam("line_" + key);
                    created.addEntry(entry);
                    return created;
                });
                team.setPrefix(truncate(colorize(lines.get(i)), 64));
            }

            for (int i = max; i < entries.size(); i++) {
                String entry = entries.get(i);
                scoreboard.resetScores(entry);
                Team team = teams.get(i);
                if (team != null) {
                    team.setPrefix("");
                }
            }
            updateTab(frameIndex);
        }

        void clear() {
            scoreboard.getObjectives().forEach(Objective::unregister);
            scoreboard.getTeams().forEach(Team::unregister);
        }

        private List<String> buildLines(int frameIndex) {
            List<String> rawLines = plugin.getConfig().getStringList("lines");
            if (rawLines.isEmpty()) {
                rawLines = defaultLines();
            }
            boolean logoEnabled = plugin.getConfig().getBoolean("logo.enabled", false);
            List<String> logoLines = plugin.getConfig().getStringList("logo.lines");
            String serverName = plugin.getConfig().getString("server-name", "MyServer");
            String serverIp = plugin.getConfig().getString("server-ip", "play.example.ir");
            String developer = plugin.getConfig().getString("developer-name", "Norah");
            String currency = plugin.getConfig().getString("shop.currency", "Ccoin");
            String bar = BAR_FRAMES.get(frameIndex % BAR_FRAMES.size());
            String ping = String.valueOf(player.getPing());
            String online = String.valueOf(Bukkit.getOnlinePlayers().size());
            String maxPlayers = String.valueOf(Bukkit.getMaxPlayers());
            String tps = formatTps();
            String[] ram = formatRam();
            String balance = String.valueOf(plugin.getBalance(player));
            List<String> merged = new ArrayList<>(rawLines);
            if (logoEnabled && !logoLines.isEmpty()) {
                merged.add("%spacer%");
                merged.addAll(logoLines);
            }
            int maxLines = entries.size();
            if (merged.size() > maxLines) {
                if (logoEnabled && !logoLines.isEmpty()) {
                    int logoBlockSize = 1 + logoLines.size();
                    int spaceForMain = Math.max(0, maxLines - logoBlockSize);
                    List<String> trimmed = new ArrayList<>(merged.size());
                    trimmed.addAll(merged.subList(0, Math.min(spaceForMain, rawLines.size())));
                    trimmed.addAll(merged.subList(Math.min(rawLines.size(), merged.size()), merged.size()));
                    merged = trimmed.size() > maxLines ? trimmed.subList(trimmed.size() - maxLines, trimmed.size()) : trimmed;
                } else {
                    merged = merged.subList(merged.size() - maxLines, merged.size());
                }
            }
            return merged.stream()
                    .map(line -> line
                            .replace("%bar%", bar)
                            .replace("%spacer%", " ")
                            .replace("%tab_bar%", TAB_FRAMES.get(frameIndex % TAB_FRAMES.size()))
                            .replace("%player%", player.getName())
                            .replace("%server%", serverName)
                            .replace("%ip%", serverIp)
                            .replace("%developer%", developer)
                            .replace("%balance%", balance)
                            .replace("%currency%", currency)
                            .replace("%ping%", ping)
                            .replace("%online%", online)
                            .replace("%max%", maxPlayers)
                            .replace("%tps%", tps)
                            .replace("%ram_used%", ram[0])
                            .replace("%ram_max%", ram[1]))
                    .collect(Collectors.toList());
        }

        private List<String> createEntries() {
            List<String> list = new ArrayList<>();
            EnumSet<ChatColor> colors = EnumSet.allOf(ChatColor.class).stream()
                    .filter(ChatColor::isColor)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(ChatColor.class)));
            for (ChatColor color : colors) {
                list.add(color.toString());
                if (list.size() >= 15) {
                    break;
                }
            }
            while (list.size() < 15) {
                list.add(ChatColor.RESET.toString() + list.size());
            }
            return list;
        }

        private List<String> defaultLines() {
            List<String> lines = new ArrayList<>();
            lines.add("&7&m----------------");
            lines.add("&fنام: &b%player%");
            lines.add("&fسرور: &a%server%");
            lines.add("&fIP: &e%ip%");
            lines.add("&fبرنامه‌نویس: &d%developer%");
            lines.add("&7&m----------------");
            return lines;
        }

        private void updateTab(int frameIndex) {
            List<String> headerLines = plugin.getConfig().getStringList("tab.header");
            List<String> footerLines = plugin.getConfig().getStringList("tab.footer");
            if (headerLines.isEmpty() && footerLines.isEmpty()) {
                return;
            }
            String header = joinLines(headerLines, frameIndex);
            String footer = joinLines(footerLines, frameIndex);
            player.setPlayerListHeaderFooter(header, footer);
        }

        private String joinLines(List<String> lines, int frameIndex) {
            if (lines.isEmpty()) {
                return "";
            }
            String serverName = plugin.getConfig().getString("server-name", "MyServer");
            String serverIp = plugin.getConfig().getString("server-ip", "play.example.ir");
            String developer = plugin.getConfig().getString("developer-name", "Norah");
            String currency = plugin.getConfig().getString("shop.currency", "Ccoin");
            String bar = BAR_FRAMES.get(frameIndex % BAR_FRAMES.size());
            String tabBar = TAB_FRAMES.get(frameIndex % TAB_FRAMES.size());
            String ping = String.valueOf(player.getPing());
            String online = String.valueOf(Bukkit.getOnlinePlayers().size());
            String maxPlayers = String.valueOf(Bukkit.getMaxPlayers());
            String tps = formatTps();
            String[] ram = formatRam();
            String balance = String.valueOf(plugin.getBalance(player));
            String combined = String.join("\n", lines);
            return colorize(combined
                    .replace("%bar%", bar)
                    .replace("%spacer%", " ")
                    .replace("%tab_bar%", tabBar)
                    .replace("%player%", player.getName())
                    .replace("%server%", serverName)
                    .replace("%ip%", serverIp)
                    .replace("%developer%", developer)
                    .replace("%balance%", balance)
                    .replace("%currency%", currency)
                    .replace("%ping%", ping)
                    .replace("%online%", online)
                    .replace("%max%", maxPlayers)
                    .replace("%tps%", tps)
                    .replace("%ram_used%", ram[0])
                    .replace("%ram_max%", ram[1]));
        }

        private String colorize(String input) {
            return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
        }

        private String truncate(String text, int max) {
            if (text == null) {
                return "";
            }
            return text.length() <= max ? text : text.substring(0, max);
        }
    }

    private void updateTabListNames(int frameIndex) {
        for (Player target : Bukkit.getOnlinePlayers()) {
            target.setPlayerListName(formatTabName(target, frameIndex));
        }
    }

    private String formatTabName(Player target, int frameIndex) {
        String ownerPermission = plugin.getConfig().getString("tab.owner-permission", "simplescore.owner");
        String ownerFormat = plugin.getConfig().getString("tab.name-format-owner", "&d★ &f%player%");
        String defaultFormat = plugin.getConfig().getString("tab.name-format-default", "&7• &f%player%");
        String template = target.hasPermission(ownerPermission) ? ownerFormat : defaultFormat;
        String serverName = plugin.getConfig().getString("server-name", "MyServer");
        String serverIp = plugin.getConfig().getString("server-ip", "play.example.ir");
        String developer = plugin.getConfig().getString("developer-name", "Norah");
        String bar = BAR_FRAMES.get(frameIndex % BAR_FRAMES.size());
        String tabBar = TAB_FRAMES.get(frameIndex % TAB_FRAMES.size());
        String ping = String.valueOf(target.getPing());
        String online = String.valueOf(Bukkit.getOnlinePlayers().size());
        String maxPlayers = String.valueOf(Bukkit.getMaxPlayers());
        String tps = formatTps();
        String[] ram = formatRam();
        return ChatColor.translateAlternateColorCodes('&', template
                .replace("%bar%", bar)
                .replace("%tab_bar%", tabBar)
                .replace("%player%", target.getName())
                .replace("%server%", serverName)
                .replace("%ip%", serverIp)
                .replace("%developer%", developer)
                .replace("%ping%", ping)
                .replace("%online%", online)
                .replace("%max%", maxPlayers)
                .replace("%tps%", tps)
                .replace("%ram_used%", ram[0])
                .replace("%ram_max%", ram[1]));
    }

    private String formatTps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps.length > 0) {
                return String.format(Locale.US, "%.2f", tps[0]);
            }
        } catch (Throwable ignored) {
        }
        return "20.00";
    }

    private String[] formatRam() {
        Runtime runtime = Runtime.getRuntime();
        double used = (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0;
        double max = runtime.maxMemory() / 1048576.0;
        return new String[]{
                String.format(Locale.US, "%.0f", used),
                String.format(Locale.US, "%.0f", max)
        };
    }
}
