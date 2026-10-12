package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.geysermc.geyser.session.GeyserSession;
import java.util.concurrent.*;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Sends native item AUX/count to the DoubleHands-derived slot layout. */
final class NativeOffhandHud {
    private static final Set<Integer> LITERAL_AUX = literalAux();

    private static Set<Integer> literalAux() {
        try (var stream = NativeOffhandHud.class.getResourceAsStream("/native-hud-literal-aux.txt")) {
            if (stream == null) throw new IOException("Missing generated native HUD AUX list");
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().map(Integer::valueOf).collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String iconTitle(int aux) {
        if (aux == 0 || LITERAL_AUX.contains(aux)) return Integer.toString(aux);
        return "B" + aux;
    }

    private record Snapshot(int itemAux, int count) {}
    private static final class State {
        Snapshot last;
        long lastSent;
        ScheduledFuture<?> poll;
    }
    private final ConcurrentMap<GeyserSession, State> states = new ConcurrentHashMap<>();

    void start(GeyserSession session) {
        session.executeInEventLoop(() -> {
            var state = new State();
            if (states.putIfAbsent(session, state) == null) poll(session, state);
        });
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
            var item = NativeInventoryView.nativeData(session, stack);
            int aux = (item.getDefinition().getRuntimeId() << 16) | (item.getDamage() & 0xffff);
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
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.TITLE, iconTitle(snapshot.itemAux())));
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
