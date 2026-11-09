package dev.infectedware.tellmeyourmods;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public record ModDatabase(List<Mod> mods, List<String> keys) {

    public record Mod(String name, String color, boolean blacklisted, int minMatches, Map<String, String> keys) {

        public String expected(String key) {
            return keys.get(key);
        }
    }

    public static ModDatabase load(File file, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Mod> mods = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>();
        for (String name : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(name);
            if (section == null) {
                logger.warning("mods.yml: '" + name + "' is not a section, skipped.");
                continue;
            }
            Map<String, String> keys = new HashMap<>();
            List<String> entries = new ArrayList<>(section.getStringList("keys"));
            entries.addAll(section.getStringList("uncertain-keys"));
            for (String entry : entries) {
                int split = entry.indexOf('=');
                String key = (split < 0 ? entry : entry.substring(0, split)).trim();
                if (key.isEmpty()) {
                    continue;
                }
                keys.put(key, split < 0 ? null : entry.substring(split + 1));
            }
            if (keys.isEmpty()) {
                logger.warning("mods.yml: '" + name + "' has no keys, skipped.");
                continue;
            }
            String color = section.getString("color");
            mods.add(new Mod(
                    name,
                    color == null || color.isBlank() ? null : color,
                    section.getBoolean("blacklisted", false),
                    Math.max(0, section.getInt("min-matches", 0)),
                    Collections.unmodifiableMap(keys)));
            allKeys.addAll(keys.keySet());
        }
        return new ModDatabase(List.copyOf(mods), List.copyOf(allKeys));
    }
}
