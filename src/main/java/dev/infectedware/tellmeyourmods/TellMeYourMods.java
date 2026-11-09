package dev.infectedware.tellmeyourmods;

import java.io.File;
import org.bukkit.plugin.java.JavaPlugin;

public final class TellMeYourMods extends JavaPlugin {

    private volatile Settings settings;
    private volatile ModDatabase mods;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "mods.yml").exists()) {
            saveResource("mods.yml", false);
        }
        reload();
    }

    // Safe to call off the main thread: only reads files and swaps a volatile snapshot.
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
