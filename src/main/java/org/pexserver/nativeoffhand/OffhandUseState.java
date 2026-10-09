package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks native timed consumption without adding client animations. */
final class OffhandUseState {
    private static final Map<GeyserSession, Set<Integer>> ACTIVE = new ConcurrentHashMap<>();
    static boolean consuming(GeyserSession session) {
        return ACTIVE.getOrDefault(session, Set.of()).contains(session.getPlayerEntity().getEntityId());
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
        boolean consuming = false;
        if ((handState & 3) == 3) {
            GeyserItemStack off = player == session.getPlayerEntity()
                ? session.getPlayerInventory().getOffhand() : player.getOffHandItem();
            var consumable = off.getComponent(DataComponentTypes.CONSUMABLE);
            if (consumable != null) consuming = switch (consumable.animation()) {
                case EAT, DRINK -> true; default -> false;
            };
        }
        Set<Integer> active = ACTIVE.computeIfAbsent(session, key -> ConcurrentHashMap.newKeySet());
        if (consuming) {
            active.add(packet.getEntityId());
            // Bedrock's generic use flag targets the main hand. Suppress that
            // wrong-hand visual while Java completes the offhand consumption.
            player.setFlag(EntityFlag.USING_ITEM, false);
            player.updateBedrockMetadata();
        } else active.remove(packet.getEntityId());
    }
}
