package dev.infectedware.tellmeyourmods;

import org.bukkit.configuration.file.FileConfiguration;

// Immutable snapshot of config.yml, swapped as a whole on reload.
public record Settings(
        boolean checkOnJoin,
        long joinDelayTicks,
        long probeDelayTicks,
        long timeoutTicks,
        long resourcePackRetryTicks,
        int minMatches,
        boolean patchCheck,
        int failuresToDisable,
        String colorBlacklisted,
        String colorAllowed,
        FileConfiguration messages
) {

    public static Settings load(FileConfiguration config) {
        return new Settings(
                config.getBoolean("detection.check-on-join", true),
                ticks(config, "detection.join-delay-ticks", 100),
                ticks(config, "detection.probe-delay-ticks", 10),
                ticks(config, "detection.timeout-ticks", 60),
                ticks(config, "detection.resource-pack-retry-ticks", 40),
                Math.max(1, config.getInt("detection.min-matches", 1)),
                config.getBoolean("patch-check.enabled", true),
                Math.max(1, config.getInt("patch-check.failures-to-disable", 3)),
                config.getString("colors.blacklisted", "&#FF5555"),
                config.getString("colors.allowed", "&#55FF55"),
                config
        );
    }

    // Raw message string, empty when missing so callers never see null.
    public String message(String key) {
        return messages.getString("messages." + key, "");
    }

    // Schedulers reject delays below one tick.
    private static long ticks(FileConfiguration config, String path, long fallback) {
        return Math.max(1L, config.getLong(path, fallback));
    }
}
