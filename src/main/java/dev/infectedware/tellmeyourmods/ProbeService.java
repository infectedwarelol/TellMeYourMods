package dev.infectedware.tellmeyourmods;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class ProbeService implements Listener, PacketListener {

    public static final String BYPASS_PERMISSION = "tellmeyourmods.bypass";
    public static final String NOTIFY_PERMISSION = "tellmeyourmods.notify";
    private static final int LINES_PER_SIGN = 4;
    private static final String CANARY_KEY = "language.code";
    private static final int[] PROBE_Y_OFFSETS = {-2, 3, -3, 4};

    public enum StartResult { STARTED, BYPASSED, ALREADY_RUNNING, NOTHING_TO_PROBE, DISABLED }

    public record Detection(ModDatabase.Mod mod, int matches) {
    }

    private final TellMeYourMods plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> resourcePackLoading = ConcurrentHashMap.newKeySet();
    private final Set<UUID> resourcePackLoaded = ConcurrentHashMap.newKeySet();
    private final Set<Integer> passedProtocols = ConcurrentHashMap.newKeySet();
    private final Set<Integer> disabledProtocols = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Set<UUID>> failedPlayers = new ConcurrentHashMap<>();

    public ProbeService(TellMeYourMods plugin) {
        this.plugin = plugin;
    }

    public StartResult start(Player player, CommandSender requester) {
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return StartResult.BYPASSED;
        }
        List<String> keys = plugin.mods().keys();
        if (keys.isEmpty()) {
            return StartResult.NOTHING_TO_PROBE;
        }
        Settings settings = plugin.settings();
        int protocol = PacketEvents.getAPI().getPlayerManager().getClientVersion(player).getProtocolVersion();
        if (settings.patchCheck() && disabledProtocols.contains(protocol)) {
            return StartResult.DISABLED;
        }
        if (settings.patchCheck()) {
            List<String> withCanary = new ArrayList<>(keys.size() + 1);
            withCanary.add(CANARY_KEY);
            withCanary.addAll(keys);
            keys = withCanary;
        }
        Session session = new Session(player, requester, protocol, partition(keys));
        if (sessions.putIfAbsent(player.getUniqueId(), session) != null) {
            return StartResult.ALREADY_RUNNING;
        }
        player.getScheduler().run(plugin, task -> sendNext(session), () -> end(session));
        return StartResult.STARTED;
    }

    private static Deque<List<String>> partition(List<String> keys) {
        Deque<List<String>> batches = new ArrayDeque<>();
        for (int index = 0; index < keys.size(); index += LINES_PER_SIGN) {
            batches.add(List.copyOf(keys.subList(index, Math.min(keys.size(), index + LINES_PER_SIGN))));
        }
        return batches;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void sendNext(Session session) {
        Player player = session.player;
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            end(session);
            return;
        }
        Settings settings = plugin.settings();
        if (resourcePackLoading.contains(player.getUniqueId())) {
            schedule(session, settings.resourcePackRetryTicks());
            return;
        }
        List<String> batch = session.batches.poll();
        if (batch == null) {
            finish(session);
            return;
        }
        Location location = probeLocation(player);
        if (location == null) {
            plugin.getLogger().warning("No free block near " + player.getName() + " for a sign probe, check stopped.");
            end(session);
            return;
        }
        List<String> sentinels = new ArrayList<>(batch.size());
        BlockData signData = Material.OAK_SIGN.createBlockData();
        Sign sign = (Sign) signData.createBlockState();
        SignSide front = sign.getSide(Side.FRONT);
        for (int line = 0; line < batch.size(); line++) {
            String key = batch.get(line);
            String sentinel = "tmym" + Long.toHexString(ThreadLocalRandom.current().nextLong());
            sentinels.add(sentinel);
            front.line(line, Component.translatable().key(key).fallback(sentinel).build());
        }
        Probe probe = new Probe(location, new Vector3i(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                batch, sentinels, location.getBlock().getBlockData());
        session.pending.set(probe);

        player.sendBlockChange(location, signData);
        player.sendBlockUpdate(location, sign);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerOpenSignEditor(probe.position, true));
        player.sendBlockChange(location, probe.realBlock);

        session.timeoutTask = player.getScheduler().runDelayed(plugin, task -> onTimeout(session, probe),
                null, settings.timeoutTicks());
    }

    private void schedule(Session session, long delayTicks) {
        session.player.getScheduler().runDelayed(plugin, task -> sendNext(session), () -> end(session), delayTicks);
    }

    private static Location probeLocation(Player player) {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        for (int offset : PROBE_Y_OFFSETS) {
            int y = feet.getBlockY() + offset;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Block block = world.getBlockAt(feet.getBlockX(), y, feet.getBlockZ());
            if (!(block.getState(false) instanceof TileState)) {
                return block.getLocation();
            }
        }
        return null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) {
            return;
        }
        Session session = sessions.get(event.getUser().getUUID());
        if (session == null) {
            return;
        }
        Probe probe = session.pending.get();
        if (probe == null) {
            return;
        }
        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        if (!probe.position.equals(packet.getBlockPosition())) {
            return;
        }
        event.setCancelled(true);
        if (!session.pending.compareAndSet(probe, null)) {
            return;
        }
        String[] responses = packet.getTextLines().clone();
        session.player.getScheduler().run(plugin, task -> onResponse(session, probe, responses), () -> end(session));
    }

    private void onResponse(Session session, Probe probe, String[] responses) {
        cancelTimeout(session);
        restore(session.player, probe);
        for (int index = 0; index < probe.keys.size(); index++) {
            String key = probe.keys.get(index);
            String response = index < responses.length ? responses[index] : null;
            boolean resolved = isResolved(response, key, probe.sentinels.get(index));
            if (key.equals(CANARY_KEY)) {
                if (!resolved) {
                    onCanaryFailed(session);
                    return;
                }
                passedProtocols.add(session.protocol);
                continue;
            }
            if (resolved) {
                session.responses.put(key, response);
            }
        }
        schedule(session, plugin.settings().probeDelayTicks());
    }

    private static boolean isResolved(String response, String key, String sentinel) {
        if (response == null) {
            return false;
        }
        String text = response.strip();
        return !text.isEmpty() && !text.equals(sentinel) && !text.equals(key);
    }

    private void onCanaryFailed(Session session) {
        end(session);
        Settings settings = plugin.settings();
        Set<UUID> failed = failedPlayers.computeIfAbsent(session.protocol, protocol -> ConcurrentHashMap.newKeySet());
        failed.add(session.player.getUniqueId());
        if (!passedProtocols.contains(session.protocol) && failed.size() >= settings.failuresToDisable()
                && disabledProtocols.add(session.protocol)) {
            plugin.getLogger().warning("Clients on protocol " + session.protocol + " do not resolve translation keys on signs."
                    + " The technique looks patched for that version, detection is disabled for it.");
        }
        String message = settings.message("unresolved").replace("%player%", session.player.getName());
        broadcast(session, Text.color(message));
    }

    private void onTimeout(Session session, Probe probe) {
        if (!session.pending.compareAndSet(probe, null)) {
            return;
        }
        restore(session.player, probe);
        end(session);
        String message = plugin.settings().message("no-reply").replace("%player%", session.player.getName());
        broadcast(session, Text.color(message));
    }

    private void finish(Session session) {
        end(session);
        Settings settings = plugin.settings();
        List<Detection> detections = evaluate(session.responses);
        boolean packLoaded = resourcePackLoaded.contains(session.player.getUniqueId());
        Component mods = detections.isEmpty()
                ? Text.color(settings.message("no-mods"))
                : formatMods(settings, detections, packLoaded);
        Component message = Text.color(settings.message("notify").replace("%player%", session.player.getName()));
        broadcast(session, Text.replace(message, "%mods%", mods));
    }

    private static Component formatMods(Settings settings, List<Detection> detections, boolean packLoaded) {
        List<Component> names = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            ModDatabase.Mod mod = detection.mod();
            String color = mod.blacklisted()
                    ? settings.colorBlacklisted()
                    : mod.color() != null ? mod.color() : settings.colorAllowed();
            Component hover = Text.color(settings.message("hover-possible").replace("%mod%", mod.name()))
                    .append(Component.newline())
                    .append(Text.color(settings.message("hover-matches").replace("%count%", Integer.toString(detection.matches()))));
            if (mod.blacklisted()) {
                hover = hover.append(Component.newline()).append(Text.color(settings.message("hover-blacklisted")));
            }
            if (packLoaded) {
                hover = hover.append(Component.newline()).append(Text.color(settings.message("hover-resource-pack")));
            }
            names.add(Text.color(color + mod.name()).hoverEvent(HoverEvent.showText(hover)));
        }
        return Component.join(JoinConfiguration.separator(Text.color(settings.message("separator"))), names);
    }

    private void broadcast(Session session, Component message) {
        plugin.getLogger().info(Text.plain(message));
        boolean requesterNotified = false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(NOTIFY_PERMISSION)) {
                online.sendMessage(message);
                requesterNotified |= online.equals(session.requester);
            }
        }
        if (session.requester != null && !requesterNotified && !(session.requester instanceof ConsoleCommandSender)) {
            session.requester.sendMessage(message);
        }
    }

    private List<Detection> evaluate(Map<String, String> responses) {
        List<Detection> detections = new ArrayList<>();
        if (responses.isEmpty()) {
            return detections;
        }
        int defaultMinMatches = plugin.settings().minMatches();
        for (ModDatabase.Mod mod : plugin.mods().mods()) {
            int matches = 0;
            for (String key : mod.keys().keySet()) {
                String response = responses.get(key);
                String expected = mod.expected(key);
                if (response != null && (expected == null || expected.equals(response.strip()))) {
                    matches++;
                }
            }
            int required = mod.minMatches() > 0 ? mod.minMatches() : defaultMinMatches;
            if (matches >= required) {
                detections.add(new Detection(mod, matches));
            }
        }
        return detections;
    }

    private static void restore(Player player, Probe probe) {
        if (player.isOnline()) {
            player.sendBlockChange(probe.location, probe.realBlock);
        }
    }

    private void end(Session session) {
        sessions.remove(session.player.getUniqueId(), session);
        cancelTimeout(session);
    }

    private static void cancelTimeout(Session session) {
        ScheduledTask task = session.timeoutTask;
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Settings settings = plugin.settings();
        if (!settings.checkOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> start(player, null), null, settings.joinDelayTicks());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Session session = sessions.remove(id);
        if (session != null) {
            cancelTimeout(session);
        }
        resourcePackLoading.remove(id);
        resourcePackLoaded.remove(id);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String status = event.getStatus().name();
        if (status.equals("ACCEPTED") || status.equals("DOWNLOADED")) {
            resourcePackLoading.add(id);
            return;
        }
        resourcePackLoading.remove(id);
        if (status.equals("SUCCESSFULLY_LOADED")) {
            resourcePackLoaded.add(id);
        }
    }

    public void shutdown() {
        failedPlayers.clear();
        for (Session session : sessions.values()) {
            cancelTimeout(session);
        }
        sessions.clear();
        resourcePackLoading.clear();
        resourcePackLoaded.clear();
    }

    private record Probe(Location location, Vector3i position, List<String> keys, List<String> sentinels,
                         BlockData realBlock) {
    }

    private static final class Session {

        private final Player player;
        private final CommandSender requester;
        private final int protocol;
        private final Deque<List<String>> batches;
        private final Map<String, String> responses = new HashMap<>();
        private final AtomicReference<Probe> pending = new AtomicReference<>();
        private volatile ScheduledTask timeoutTask;

        private Session(Player player, CommandSender requester, int protocol, Deque<List<String>> batches) {
            this.player = player;
            this.requester = requester;
            this.protocol = protocol;
            this.batches = batches;
        }
    }
}
