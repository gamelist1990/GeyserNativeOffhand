package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.geysermc.geyser.session.GeyserSession;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;

/** Uses the unmodified DoubleHands image-path/count HUD protocol. */
final class NativeOffhandHud {
    private static final String EMPTY = "textures/ui/empty";
    private static final Properties TEXTURES = new Properties();
    static {
        try (var stream = NativeOffhandHud.class.getResourceAsStream("/offhand-hud-textures.properties")) {
            if (stream == null) throw new IllegalStateException("Missing DoubleHands HUD texture map");
            TEXTURES.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    private record Snapshot(String texture, int count) {}
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
        if (stack.isEmpty()) snapshot = new Snapshot(EMPTY, 0);
        else {
            // Resolve outside the inventory-move translation scope. The map is
            // keyed by vanilla Bedrock item names, just as DoubleHands is.
            String identifier = stack.getItemData(session).getDefinition().getIdentifier();
            String name = identifier.startsWith("minecraft:") ? identifier.substring(10) : identifier;
            String javaName = stack.asItem().javaKey().value();
            String texture = TEXTURES.getProperty(name,
                TEXTURES.getProperty(javaName, "textures/items/" + name));
            snapshot = new Snapshot(texture, stack.getAmount());
        }
        long now = System.nanoTime();
        // Original UI binds live title values rather than keeping local memory.
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
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.TITLE, snapshot.texture()));
    }

    // The copied UI hides normal titles. Reserve their data channels for the
    // offhand icon/count so server titles cannot become invalid image paths.
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
