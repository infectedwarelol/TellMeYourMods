package dev.infectedware.tellmeyourmods;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import java.io.File;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class TellMeYourMods extends JavaPlugin {

    private volatile Settings settings;
    private volatile ModDatabase mods;
    private ProbeService probes;
    private PacketListenerCommon packetListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "mods.yml").exists()) {
            saveResource("mods.yml", false);
        }
        reload();
        probes = new ProbeService(this);
        getServer().getPluginManager().registerEvents(probes, this);
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(probes, PacketListenerPriority.NORMAL);
        PluginCommand command = getCommand(TmymCommand.NAME);
        if (command != null) {
            TmymCommand executor = new TmymCommand(this, probes);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener = null;
        }
        HandlerList.unregisterAll(this);
        if (probes != null) {
            probes.shutdown();
            probes = null;
        }
    }

    public void reload() {
        reloadConfig();
        settings = Settings.load(getConfig());
        mods = ModDatabase.load(new File(getDataFolder(), "mods.yml"), getLogger());
    }

    public Settings settings() {
        return settings;
    }

    public ModDatabase mods() {
        return mods;
    }
}
