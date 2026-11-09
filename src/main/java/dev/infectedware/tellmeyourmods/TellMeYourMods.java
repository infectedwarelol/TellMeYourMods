package dev.infectedware.tellmeyourmods;

import org.bukkit.plugin.java.JavaPlugin;

public final class TellMeYourMods extends JavaPlugin {

    private volatile Settings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
    }

    // Safe to call off the main thread: only reads files and swaps a volatile snapshot.
    public void reload() {
        reloadConfig();
        settings = Settings.load(getConfig());
    }

    public Settings settings() {
        return settings;
    }
}
