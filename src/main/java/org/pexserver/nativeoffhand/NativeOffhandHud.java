package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.geyser.session.GeyserSession;
import java.util.concurrent.*;

/** Sends native item AUX/count to the DoubleHands-derived slot layout. */
final class NativeOffhandHud {
    private record Snapshot(int itemAux, int count) {}
    private static final class State {
        final int modernItemShift;
        State(int modernItemShift) { this.modernItemShift = modernItemShift; }
        Snapshot last;
        long lastSent;
        ScheduledFuture<?> poll;
    }
    private final ConcurrentMap<GeyserSession, State> states = new ConcurrentHashMap<>();

    void start(GeyserSession session) {
        session.executeInEventLoop(() -> {
            var state = new State(modernItemShift(session));
            if (states.putIfAbsent(session, state) == null) poll(session, state);
        });
    }

    // JSON UI's local IDs differ from network runtime IDs for 26.50's
    // data-driven vanilla items. Custom items occupy the preceding slots.
    // Only enable this version-specific rule when the native registry matches
    // the verified 852..883 layout; other layouts keep their network IDs.
    private static int modernItemShift(GeyserSession session) {
        var mappings = session.getItemMappings();
        var apple = mappings.getDefinition("minecraft:apple");
        var shrub = mappings.getDefinition("minecraft:red_shrub");
        if (apple == null || apple.getRuntimeId() != 882 || apple.getVersion() != ItemVersion.DATA_DRIVEN
            || shrub == null || shrub.getRuntimeId() != 852) return 0;
        int customCount = 0;
        for (var definition : mappings.getItemDefinitions().values()) {
            if (definition.getRuntimeId() > 0 && definition.getVersion() == ItemVersion.DATA_DRIVEN
                && !definition.getIdentifier().startsWith("minecraft:")) customCount++;
        }
        // Later custom items are inserted after the vanilla range.
        return Math.min(customCount, 1569);
    }

    private void poll(GeyserSession session, State state) {
        if (states.get(session) != state || session.isClosed()) return;
        update(session);
        state.poll = session.scheduleInEventLoop(() -> poll(session, state), 250, TimeUnit.MILLISECONDS);
    }

    void update(GeyserSession session) {
        var state = states.get(session);
        if (state == null || !session.isSpawned() || session.isClosed()) return;
        var stack = session.getPlayerInventory().getOffhand();
        Snapshot snapshot;
        if (stack.isEmpty()) snapshot = new Snapshot(0, 0);
        else {
            // Resolve outside NativeInventoryView's scoped inventory aliases.
            // The HUD must use the client's native definition, even while the
            // inventory is showing an offhand-permitted move alias.
            var item = stack.getItemData(session);
            var definition = item.getDefinition();
            int hudId = definition.getRuntimeId();
            if (hudId >= 852 && hudId <= 883 && definition.getVersion() == ItemVersion.DATA_DRIVEN
                && definition.getIdentifier().startsWith("minecraft:")) hudId += state.modernItemShift;
            int aux = (hudId << 16) | (item.getDamage() & 0xffff);
            snapshot = new Snapshot(aux, stack.getAmount());
        }
        long now = System.nanoTime();
        // The slot binds live title values rather than keeping local memory.
        // Refresh before the two-second title expires, even if nothing changed.
        if (snapshot.equals(state.last) && now - state.lastSent < TimeUnit.SECONDS.toNanos(1)) return;
        state.last = snapshot;
        state.lastSent = now;
        var times = packet(SetTitlePacket.Type.TIMES, "");
        times.setFadeInTime(0);
        times.setStayTime(40);
        times.setFadeOutTime(0);
        session.sendUpstreamPacket(times);
        session.getWorldCache().markTitleTimesAsIncorrect();
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.SUBTITLE,
            snapshot.count() > 1 ? Integer.toString(snapshot.count()) : ""));
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.TITLE, Integer.toString(snapshot.itemAux())));
    }

    // The copied UI hides normal titles. Reserve their data channels for the
    // offhand icon/count so server titles cannot overwrite the numeric item data.
    // Actionbar packets continue through Geyser's original translator.
    void refresh(GeyserSession session) {
        var state = states.get(session);
        if (state != null) { state.last = null; update(session); }
    }

    private static SetTitlePacket packet(SetTitlePacket.Type type, String text) {
        var packet = new SetTitlePacket();
        packet.setType(type);
        packet.setText(text);
        packet.setXuid("");
        packet.setPlatformOnlineId("");
        return packet;
    }

    void forget(GeyserSession session) {
        var state = states.remove(session);
        if (state != null && state.poll != null) state.poll.cancel(false);
    }

    void clear() { for (var session : states.keySet()) forget(session); }
}
