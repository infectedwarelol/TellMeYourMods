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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

// Runs sign translation-key probes. Per-player work runs on the player's EntityScheduler,
// which is the region thread on Folia and the main thread on Paper.
public final class ProbeService implements Listener, PacketListener {

    public static final String BYPASS_PERMISSION = "tellmeyourmods.bypass";
    private static final int LINES_PER_SIGN = 4;
    // Offsets from the player's feet tried for the fake sign, closest first.
    private static final int[] PROBE_Y_OFFSETS = {-2, 3, -3, 4};

    public enum StartResult { STARTED, BYPASSED, ALREADY_RUNNING, NOTHING_TO_PROBE }

    private final TellMeYourMods plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> resourcePackLoading = ConcurrentHashMap.newKeySet();
    private final Set<UUID> resourcePackLoaded = ConcurrentHashMap.newKeySet();

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
        Session session = new Session(player, requester, partition(keys));
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

    // Region thread: sends the next sign, or reports when every batch is answered.
    private void sendNext(Session session) {
        Player player = session.player;
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            end(session);
            return;
        }
        Settings settings = plugin.settings();
        // Resource packs can define translation keys, so wait until loading finished.
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
            // Unique fallback per probe line, returned untouched when the client lacks the key.
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
        // Removing the sign right away makes the client close the editor and send the resolved text.
        player.sendBlockChange(location, probe.realBlock);

        session.timeoutTask = player.getScheduler().runDelayed(plugin, task -> onTimeout(session, probe),
                null, settings.timeoutTicks());
    }

    private void schedule(Session session, long delayTicks) {
        session.player.getScheduler().runDelayed(plugin, task -> sendNext(session), () -> end(session), delayTicks);
    }

    // Picks a block near the player that is not a tile entity, so restoring it needs only a block change.
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

    // Netty thread: claims our own sign replies and hands them to the player's region thread.
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN || event.getUser() == null) {
            return;
        }
        UUID id = event.getUser().getUUID();
        Session session = id == null ? null : sessions.get(id);
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
            String response = index < responses.length ? responses[index] : null;
            session.responses.put(probe.keys.get(index), response == null ? "" : response);
        }
        schedule(session, plugin.settings().probeDelayTicks());
    }

    private void onTimeout(Session session, Probe probe) {
        if (!session.pending.compareAndSet(probe, null)) {
            return;
        }
        restore(session.player, probe);
        end(session);
        String message = plugin.settings().message("no-reply").replace("%player%", session.player.getName());
        plugin.getLogger().info(Text.plain(Text.color(message)));
        if (session.requester != null) {
            session.requester.sendMessage(Text.color(message));
        }
    }

    private void finish(Session session) {
        end(session);
        plugin.getLogger().info(session.player.getName() + " answered " + session.responses.size() + " probe keys.");
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
        // DOWNLOADED only exists on 1.20.3+, so compare by name to stay loadable on 1.20.1.
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
        // Touched only on the player's region thread.
        private final Deque<List<String>> batches;
        private final Map<String, String> responses = new HashMap<>();
        // Shared with the netty thread; whoever clears it owns the reply or the timeout.
        private final AtomicReference<Probe> pending = new AtomicReference<>();
        private volatile ScheduledTask timeoutTask;

        private Session(Player player, CommandSender requester, Deque<List<String>> batches) {
            this.player = player;
            this.requester = requester;
            this.batches = batches;
        }
    }
}
