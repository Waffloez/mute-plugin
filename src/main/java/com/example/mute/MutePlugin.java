package com.example.mute;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MutePlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private final Map<String, MuteReason> reasons = new LinkedHashMap<>();

    private boolean voicechatEnabled;
    private String voicechatNode;
    private boolean useLiteBans;

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhdwy])");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        getCommand("punish").setExecutor(this);
        getCommand("punish").setTabCompleter(this);
        getCommand("unpunish").setExecutor(this);
        getCommand("unpunish").setTabCompleter(this);

        if (voicechatEnabled && getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().warning("LuckPerms not found - voice chat muting will be skipped.");
        }
        if (useLiteBans && getServer().getPluginManager().getPlugin("LiteBans") == null) {
            getLogger().warning("LiteBans not found - chat will not actually be muted, only the voice chat permission will be revoked.");
        }

        getLogger().info("Mute plugin enabled with " + reasons.size() + " reason(s).");
    }

    private void loadConfigValues() {
        reloadConfig();
        FileConfiguration cfg = getConfig();
        reasons.clear();

        if (cfg.isConfigurationSection("reasons")) {
            for (String key : cfg.getConfigurationSection("reasons").getKeys(false)) {
                String path = "reasons." + key;
                String display = cfg.getString(path + ".display", key);
                String duration = cfg.getString(path + ".duration", "1d");
                reasons.put(key.toLowerCase(), new MuteReason(display, duration));
            }
        }

        voicechatEnabled = cfg.getBoolean("voicechat.enabled", true);
        voicechatNode = cfg.getString("voicechat.permission-node", "voicechat.speak");
        useLiteBans = cfg.getBoolean("use-litebans", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("punish")) {
            return handleMute(sender, args);
        } else if (name.equals("unpunish")) {
            return handleUnmute(sender, args);
        }
        return false;
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mute.reload")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            loadConfigValues();
            sender.sendMessage(ChatColor.GREEN + "Mute config reloaded.");
            return true;
        }

        if (!sender.hasPermission("mute.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /punish <player> <reason>");
            sender.sendMessage(ChatColor.GRAY + "Reasons: " + String.join(", ", reasons.keySet()));
            return true;
        }

        String targetName = args[0];
        String reasonKey = args[1].toLowerCase();

        MuteReason reason = reasons.get(reasonKey);
        if (reason == null) {
            sender.sendMessage(ChatColor.RED + "Unknown reason '" + args[1] + "'.");
            sender.sendMessage(ChatColor.GRAY + "Reasons: " + String.join(", ", reasons.keySet()));
            return true;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetId;
        String resolvedName;

        if (target != null) {
            targetId = target.getUniqueId();
            resolvedName = target.getName();
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            if (!offline.hasPlayedBefore() && offline.getName() == null) {
                sender.sendMessage(ChatColor.RED + "That player has never joined this server.");
                return true;
            }
            targetId = offline.getUniqueId();
            resolvedName = offline.getName() != null ? offline.getName() : targetName;
        }

        long seconds = parseDurationToSeconds(reason.duration());

        if (useLiteBans && getServer().getPluginManager().getPlugin("LiteBans") != null) {
            String liteBansDuration = isPermanent(reason.duration()) ? "permanent" : reason.duration();
            String cmd = "litebans mute " + resolvedName + " " + liteBansDuration + " " + reason.display();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else if (target != null) {
            target.sendMessage(ChatColor.RED + "You have been muted: " + reason.display());
        }

        if (voicechatEnabled && getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            revokeVoiceChat(targetId, seconds);
        }

        String durationText = isPermanent(reason.duration()) ? "permanently" : "for " + reason.duration();
        sender.sendMessage(ChatColor.GREEN + "Muted " + resolvedName + " " + durationText
                + " (" + reason.display() + ")"
                + (voicechatEnabled ? ChatColor.GRAY + " [voice chat revoked]" : ""));

        if (target != null) {
            target.sendMessage(ChatColor.RED + "You have been muted " + durationText + ": " + reason.display());
        }

        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mute.unmute")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /unpunish <player>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);

        UUID targetId;
        if (target != null) {
            targetId = target.getUniqueId();
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            targetId = offline.getUniqueId();
        }

        if (useLiteBans && getServer().getPluginManager().getPlugin("LiteBans") != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "litebans unmute " + targetName);
        }

        if (voicechatEnabled && getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            restoreVoiceChat(targetId);
        }

        sender.sendMessage(ChatColor.GREEN + "Unmuted " + targetName + ".");
        if (target != null) {
            target.sendMessage(ChatColor.GREEN + "You have been unmuted.");
        }
        return true;
    }

    private void revokeVoiceChat(UUID uuid, long seconds) {
        LuckPerms api = LuckPermsProvider.get();
        api.getUserManager().modifyUser(uuid, user -> {
            PermissionNode.Builder builder = PermissionNode.builder(voicechatNode).value(false);
            if (seconds > 0) {
                builder.expiry(Instant.now().plusSeconds(seconds));
            }
            user.data().add(builder.build());
        });
    }

    private void restoreVoiceChat(UUID uuid) {
        LuckPerms api = LuckPermsProvider.get();
        api.getUserManager().modifyUser(uuid, user ->
                user.data().clear(node -> node instanceof PermissionNode
                        && ((PermissionNode) node).getPermission().equalsIgnoreCase(voicechatNode)));
    }

    private boolean isPermanent(String duration) {
        return duration.equalsIgnoreCase("perm") || duration.equalsIgnoreCase("permanent");
    }

    private long parseDurationToSeconds(String duration) {
        if (duration == null || isPermanent(duration)) {
            return 0;
        }

        Matcher m = DURATION_PATTERN.matcher(duration.toLowerCase());
        long totalSeconds = 0;
        boolean matched = false;
        while (m.find()) {
            matched = true;
            long value = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s" -> totalSeconds += value;
                case "m" -> totalSeconds += value * 60;
                case "h" -> totalSeconds += value * 3600;
                case "d" -> totalSeconds += value * 86400;
                case "w" -> totalSeconds += value * 604800;
                case "y" -> totalSeconds += value * 31536000;
            }
        }
        return matched ? totalSeconds : 0;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && name.equals("punish")) {
            String partial = args[1].toLowerCase();
            for (String key : reasons.keySet()) {
                if (key.startsWith(partial)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    private record MuteReason(String display, String duration) {
    }
}
