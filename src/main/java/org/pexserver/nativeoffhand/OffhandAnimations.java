package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class OffhandAnimations {
    private static final Map<GeyserSession, Map<Integer, Integer>> ACTIVE = new ConcurrentHashMap<>();
    private final GeyserIntEntityProperty useProperty;
    OffhandAnimations(GeyserIntEntityProperty useProperty) { this.useProperty = useProperty; }
    static boolean consuming(GeyserSession session) {
        return ACTIVE.getOrDefault(session, Map.of()).containsKey(session.getPlayerEntity().getEntityId());
    }
    static void disconnect(GeyserSession session) { ACTIVE.remove(session); }
    static void clear() { ACTIVE.clear(); }

    void accept(GeyserSession session, ClientboundSetEntityDataPacket packet) {
        Integer handState = null;
        for (var metadata : packet.getMetadata()) {
            if (metadata.getId() == 8 && metadata.getValue() instanceof Byte value) handState = value.intValue();
        }
        if (handState == null) return;
        Entity entity = packet.getEntityId() == session.getPlayerEntity().getEntityId()
            ? session.getPlayerEntity() : session.getEntityCache().getEntityByJavaId(packet.getEntityId());
        if (!(entity instanceof PlayerEntity player)) return;
        int mode = 0;
        if ((handState & 3) == 3) {
            GeyserItemStack off = player == session.getPlayerEntity()
                ? session.getPlayerInventory().getOffhand() : player.getOffHandItem();
            var consumable = off.getComponent(DataComponentTypes.CONSUMABLE);
            if (consumable != null) {
                mode = switch (consumable.animation()) { case EAT -> 1; case DRINK -> 2; default -> 0; };
            }
        }
        Map<Integer, Integer> active = ACTIVE.computeIfAbsent(session, key -> new ConcurrentHashMap<>());
        int previous = active.getOrDefault(packet.getEntityId(), 0);
        if (mode != 0) {
            // Bedrock's generic USING_ITEM animation targets the main hand.
            // Keep the Java use state in ACTIVE and render the correct left hand.
            player.setFlag(EntityFlag.USING_ITEM, false);
            player.updateBedrockMetadata();
        }
        if (previous == mode) return;
        if (mode == 0) active.remove(packet.getEntityId()); else active.put(packet.getEntityId(), mode);
        final int value = mode;
        player.updatePropertiesBatched(updater -> updater.update(useProperty, value), false);
        AnimateEntityPacket animation = new AnimateEntityPacket();
        animation.setAnimation(mode == 1 ? "animation.nativeoffhand.eat"
            : mode == 2 ? "animation.nativeoffhand.drink" : "animation.nativeoffhand.idle");
        animation.setController("nativeoffhand_use");
        animation.setNextState("default");
        animation.setStopExpression(mode == 0 ? "query.anim_time > 0.05"
            : "query.property('nativeoffhand:use') != " + mode);
        animation.setStopExpressionVersion(1);
        animation.setBlendOutTime(0.12f);
        animation.getRuntimeEntityIds().add(player.geyserId());
        session.sendUpstreamPacket(animation);
    }
}
