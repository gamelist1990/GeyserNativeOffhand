package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.*;
import java.util.concurrent.*;

/** DoubleHands-style HUD transport; actual inventory stacks are never changed. */
final class NativeOffhandHud {
    static final String ITEM_PREFIX = "nativeoffhandhud:item:";
    static final String COUNT_PREFIX = "nativeoffhandhud:count:";
    private final ConcurrentMap<GeyserSession, State> states = new ConcurrentHashMap<>();

    private record Snapshot(int itemAux, int count) {}
    private static final class State {
        Snapshot last;
        int fadeIn = 10, stay = 70, fadeOut = 20;
        long blockedUntil;
        String subtitle = "";
        ScheduledFuture<?> poll;
    }

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
        if (state == null || !session.isSpawned() || session.isClosed()
                || System.nanoTime() < state.blockedUntil) return;
        var stack = session.getPlayerInventory().getOffhand();
        Snapshot snapshot;
        if (stack.isEmpty()) snapshot = new Snapshot(0, 0);
        else {
            // Outside NativeInventoryView's translation scope, this selects the
            // actual held definition, not our temporary inventory-move alias.
            var item = stack.getItemData(session);
            int aux = (item.getDefinition().getRuntimeId() << 16) | (item.getDamage() & 0xffff);
            snapshot = new Snapshot(aux, stack.getAmount());
        }
        if (snapshot.equals(state.last)) return;
        state.last = snapshot;
        var times = packet(SetTitlePacket.Type.TIMES, "");
        times.setFadeInTime(0);
        times.setStayTime(20);
        times.setFadeOutTime(0);
        session.sendUpstreamPacket(times);
        // The next real server title must regain its own fade/stay timings.
        session.getWorldCache().markTitleTimesAsIncorrect();
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.SUBTITLE, COUNT_PREFIX + snapshot.count()));
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.TITLE, ITEM_PREFIX + snapshot.itemAux()));
    }

    void beforeTitle(GeyserSession session, ClientboundSetTitleTextPacket packet) {
        var state = states.get(session);
        if (state == null) return;
        long ticks = (long) state.fadeIn + state.stay + state.fadeOut;
        long millis = Math.max(0, (long) (ticks * session.getMillisecondsPerTick()));
        state.blockedUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis + 100);
        // Do not let our count payload become the subtitle of a server title.
        session.sendUpstreamPacket(packet(SetTitlePacket.Type.SUBTITLE, state.subtitle));
    }

    void subtitle(GeyserSession session, ClientboundSetSubtitleTextPacket packet) {
        var state = states.get(session);
        if (state == null) return;
        state.subtitle = packet.getText() == null ? " " : MessageTranslator.convertMessage(packet.getText(), session.locale());
        // Subtitle commonly arrives immediately before its title. Keep HUD
        // updates out of that gap, even when inventory packets are interleaved.
        state.blockedUntil = Math.max(state.blockedUntil,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500));
    }

    void times(GeyserSession session, ClientboundSetTitlesAnimationPacket packet) {
        var state = states.get(session);
        if (state == null) return;
        if (packet.getFadeIn() >= 0) state.fadeIn = packet.getFadeIn();
        if (packet.getStay() >= 0) state.stay = packet.getStay();
        if (packet.getFadeOut() >= 0) state.fadeOut = packet.getFadeOut();
    }

    void clearTitles(GeyserSession session, ClientboundClearTitlesPacket packet) {
        var state = states.get(session);
        if (state == null) return;
        state.subtitle = "";
        state.blockedUntil = 0;
        state.last = null;
        if (packet.isResetTimes()) { state.fadeIn = 10; state.stay = 70; state.fadeOut = 20; }
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
