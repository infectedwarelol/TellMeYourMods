package dev.infectedware.tellmeyourmods;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TmymCommand implements TabExecutor {

    public static final String NAME = "tmym";
    public static final String ADMIN_PERMISSION = "tellmeyourmods.admin";

    private final TellMeYourMods plugin;
    private final ProbeService probes;

    public TmymCommand(TellMeYourMods plugin, ProbeService probes) {
        this.plugin = plugin;
        this.probes = probes;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Settings settings = plugin.settings();
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                plugin.reload();
                sender.sendMessage(Text.color(plugin.settings().message("reloaded")));
            });
            return true;
        }
        if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("mods")) {
            showMods(sender, settings, args);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Text.color(settings.message("player-not-found")));
                return true;
            }
            String key = switch (probes.start(target, sender)) {
                case STARTED -> "check-started";
                case BYPASSED -> "bypassed";
                case ALREADY_RUNNING -> "already-running";
                case DISABLED -> "disabled";
                case NOTHING_TO_PROBE -> "no-mods";
            };
            sender.sendMessage(Text.color(settings.message(key).replace("%player%", target.getName())));
            return true;
        }
        sender.sendMessage(Text.color(settings.message("usage")));
        return true;
    }

    private void showMods(CommandSender sender, Settings settings, String[] args) {
        Player target;
        if (args.length == 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Text.color(settings.message("usage")));
            return;
        }
        if (target == null) {
            sender.sendMessage(Text.color(settings.message("player-not-found")));
            return;
        }
        Component mods = probes.lastMods(target.getUniqueId());
        if (mods == null) {
            sender.sendMessage(Text.color(settings.message("not-checked").replace("%player%", target.getName())));
            return;
        }
        Component message = Text.color(settings.message("mods-list").replace("%player%", target.getName()));
        sender.sendMessage(Text.replace(message, "%mods%", mods));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return completions;
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String option : List.of("check", "mods", "reload")) {
                if (option.startsWith(prefix)) {
                    completions.add(option);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("mods"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(online.getName());
                }
            }
        }
        return completions;
    }
}
