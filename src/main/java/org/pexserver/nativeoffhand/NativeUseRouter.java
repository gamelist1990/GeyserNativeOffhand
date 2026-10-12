package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.util.BlockUtils;
import org.geysermc.geyser.level.physics.Direction;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Routes only offhand actions; every other action uses Geyser's translator. */
final class NativeUseRouter {
    private final Map<GeyserSession, Integer> consuming = new ConcurrentHashMap<>();

    void forget(GeyserSession session) { consuming.remove(session); PaperUseBridge.forget(session); }
    void clear() { consuming.clear(); }

    ClientboundSetEntityDataPacket metadata(GeyserSession session, ClientboundSetEntityDataPacket packet) {
        if (packet.getEntityId() != session.getPlayerEntity().getEntityId()) return packet;
        var translated = packet.getMetadata().clone();
        boolean changed = false;
        for (int i = 0; i < translated.length; i++) {
            var entry = translated[i];
            if (entry.getId() != 8 || !(entry.getValue() instanceof Byte flags)) continue;
            var off = session.getPlayerInventory().getOffhand();
            if ((flags & 3) == 3 && off.getComponent(DataComponentTypes.CONSUMABLE) != null) {
                consuming.putIfAbsent(session, off.asItem().javaId());
                // Bedrock's generic USING_ITEM flag animates the main hand.
                session.getPlayerEntity().setFlag(EntityFlag.USING_ITEM, false);
                // Filter before Geyser translates, so the right hand never
                // receives a transient USING_ITEM=true packet for left-hand food.
                if (entry instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata value) {
                    translated[i] = new org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata(
                        value.getId(), value.getType(), (byte) (flags & ~1));
                    changed = true;
                }
            } else consuming.remove(session);
        }
        return changed ? packet.withMetadata(translated) : packet;
    }

    void refresh(GeyserSession session) {
        var item = consuming.get(session);
        if (item == null) return;
        var off = session.getPlayerInventory().getOffhand();
        if (off.isEmpty() || off.asItem().javaId() != item || off.getComponent(DataComponentTypes.CONSUMABLE) == null) {
            consuming.remove(session);
        }
    }

    void translate(GeyserSession session, InventoryTransactionPacket packet,
            PacketTranslator<InventoryTransactionPacket> original) {
        if (packet.getTransactionType() != InventoryTransactionType.ITEM_USE
                || packet.getActionType() < 0 || packet.getActionType() > 1) {
            original.translate(session, packet); return;
        }
        int slot = packet.getHotbarSlot();
        if (slot < 0 || slot > 8) return;
        var inventory = session.getPlayerInventory();
        var main = inventory.getItem(inventory.getOffsetForHotbar(slot));
        var incoming = packet.getItemInHand();
        // Some native Bedrock offhand items report their own definition.
        // Validate either equipped identity, but NEVER use it to skip main.
        if (incoming == null || (!incoming.getDefinition().equals(ItemTranslator.getBedrockItemDefinition(session, main))
                && !incoming.getDefinition().equals(ItemTranslator.getBedrockItemDefinition(session, inventory.getOffhand())))) {
            session.getPlayerInventoryHolder().updateSlot(inventory.getOffsetForHotbar(slot));
            session.getPlayerInventoryHolder().updateSlot(45);
            return;
        }
        if (packet.getActionType() == 0 && session.getBlockMappings().isItemFrame(packet.getBlockDefinition())) {
            original.translate(session, packet); return;
        }
        net.minecraft.world.phys.BlockHitResult hit = null;
        if (packet.getActionType() == 0) {
            if (packet.getBlockFace() < 0 || packet.getBlockFace() > 5 || packet.getBlockPosition() == null
                    || packet.getClickPosition() == null) return;
            var pos = packet.getBlockPosition();
            var click = packet.getClickPosition();
            float x = click.getX(), y = click.getY(), z = click.getZ();
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return;
            hit = new net.minecraft.world.phys.BlockHitResult(
                new net.minecraft.world.phys.Vec3((double) pos.getX() + x, (double) pos.getY() + y, (double) pos.getZ() + z),
                net.minecraft.core.Direction.from3DDataValue(packet.getBlockFace()),
                new net.minecraft.core.BlockPos(pos.getX(), pos.getY(), pos.getZ()), false);
            session.setLastLowerDoorPosition(null);
        }
        if (slot != inventory.getHeldItemSlot()) session.switchHeldSlot(slot);
        session.getBookEditCache().checkForSend();
        int sequence = session.getWorldCache().nextPredictionSequence();
        if (hit != null) {
            var pos = packet.getBlockPosition();
            session.getWorldCache().markPositionInSequence(pos);
            session.getWorldCache().markPositionInSequence(BlockUtils.getBlockPosition(pos,
                Direction.getUntrusted(packet, InventoryTransactionPacket::getBlockFace)));
        }
        PaperUseBridge.submit(session, slot, sequence, hit, System.currentTimeMillis());
        session.setInteracting(true);
    }

}
