package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.session.GeyserSession;

/** A player item-bone animation; no food replacement or texture stages. */
final class NativeEatingAnimation {
    static final String PROPERTY = "nativeoffhand:eating";
    static final String ANIMATION = "animation.nativeoffhand.eating.left";
    private GeyserIntEntityProperty property;

    void property(GeyserIntEntityProperty property) { this.property = property; }

    void start(GeyserSession session) {
        if (property == null) return;
        state(session, 1);
        var packet = new AnimateEntityPacket();
        packet.setAnimation(ANIMATION);
        packet.setController("nativeoffhand_eating");
        packet.setNextState("default");
        packet.setStopExpression("query.property('" + PROPERTY + "') == 0");
        packet.setStopExpressionVersion(1);
        packet.setBlendOutTime(0.1f);
        packet.getRuntimeEntityIds().add(session.getPlayerEntity().geyserId());
        session.sendUpstreamPacket(packet);
    }

    void stop(GeyserSession session) {
        if (property != null) state(session, 0);
    }

    private void state(GeyserSession session, int value) {
        session.getPlayerEntity().updatePropertiesBatched(updater -> updater.update(property, value), true);
    }
}
