package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks native timed consumption and exposes state to offhand food attachables. */
final class OffhandUseState {
    private static final Map<GeyserSession, Map<Integer, Integer>> ACTIVE = new ConcurrentHashMap<>();
    private final GeyserIntEntityProperty consumeMode;
    OffhandUseState(GeyserIntEntityProperty consumeMode) { this.consumeMode = consumeMode; }
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
            if (consumable != null) mode = switch (consumable.animation()) {
                case EAT -> 1; case DRINK -> 2; default -> 0;
            };
        }
        Map<Integer, Integer> active = ACTIVE.computeIfAbsent(session, key -> new ConcurrentHashMap<>());
        int previous = active.getOrDefault(packet.getEntityId(), 0);
        if (mode != 0) {
            active.put(packet.getEntityId(), mode);
            // Bedrock's generic use flag targets the main hand. Suppress that
            // wrong-hand visual while Java completes the offhand consumption.
            player.setFlag(EntityFlag.USING_ITEM, false);
            player.updateBedrockMetadata();
        } else active.remove(packet.getEntityId());
        if (previous != mode) {
            final int value = mode;
            // The attachable controller observes the owning player's property.
            player.updatePropertiesBatched(updater -> updater.update(consumeMode, value), true);
        }
    }
}
