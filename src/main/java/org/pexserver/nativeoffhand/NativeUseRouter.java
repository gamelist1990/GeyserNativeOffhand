package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.type.BlockItem;
import org.geysermc.geyser.level.block.type.*;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.bedrock.BedrockInventoryTransactionTranslator;
import org.geysermc.geyser.util.BlockUtils;
import org.geysermc.geyser.level.physics.Direction;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Routes only offhand actions; every other action uses Geyser's translator. */
final class NativeUseRouter {
    private final Map<GeyserSession, Integer> consuming = new ConcurrentHashMap<>();
    private static final Set<String> ACTIVE = Set.of("bow", "crossbow", "trident", "shield", "fishing_rod",
        "flint_and_steel", "fire_charge", "shears", "brush", "spyglass", "goat_horn", "snowball", "egg",
        "ender_pearl", "ender_eye", "experience_bottle", "wind_charge", "firework_rocket", "potion",
        "splash_potion", "lingering_potion", "glass_bottle", "bundle", "lead", "name_tag",
        "writable_book", "written_book", "armor_stand", "saddle");

    void forget(GeyserSession session) { consuming.remove(session); }
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

    private boolean selectOffhand(GeyserSession session, InventoryTransactionPacket packet) {
        var inventory = session.getPlayerInventory();
        var off = inventory.getOffhand();
        if (off.isEmpty()) return false;
        var main = inventory.getItem(inventory.getOffsetForHotbar(packet.getHotbarSlot()));
        var incoming = packet.getItemInHand();
        if (incoming != null && !main.isEmpty()
                && incoming.getDefinition().equals(ItemTranslator.getBedrockItemDefinition(session, off))
                && !incoming.getDefinition().equals(ItemTranslator.getBedrockItemDefinition(session, main))) return true;
        if (main.isEmpty()) return true;
        if (main.getComponent(DataComponentTypes.CONSUMABLE) != null
                || main.getComponent(DataComponentTypes.EQUIPPABLE) != null
                || main.getComponent(DataComponentTypes.BLOCKS_ATTACKS) != null
                || main.getComponent(DataComponentTypes.PIERCING_WEAPON) != null
                || main.getComponent(DataComponentTypes.KINETIC_WEAPON) != null) return false;
        String name = main.asItem().javaKey().value();
        if (main.asItem() instanceof BlockItem || name.endsWith("_axe") || name.endsWith("_hoe")
                || name.endsWith("_shovel")) return packet.getActionType() == 1;
        return !(name.equals("bucket") || name.endsWith("_bucket") || name.endsWith("_spawn_egg")
            || name.endsWith("_boat") || name.endsWith("_raft") || ACTIVE.contains(name));
    }

    void translate(GeyserSession session, InventoryTransactionPacket packet,
            PacketTranslator<InventoryTransactionPacket> original) {
        if (packet.getTransactionType() != InventoryTransactionType.ITEM_USE || packet.getActionType() < 0
                || packet.getActionType() > 1) { original.translate(session, packet); return; }
        if (packet.getHotbarSlot() < 0 || packet.getHotbarSlot() > 8) return;
        // Opening a chest/door belongs to the clicked block, not to either
        // item's use ability. Keep Geyser's native main-hand interaction path.
        if (packet.getActionType() == 0 && !session.isSneaking() && packet.getBlockPosition() != null) {
            var clicked = session.getGeyser().getWorldManager().blockAt(session, packet.getBlockPosition());
            if (BlockRegistries.INTERACTIVE.get().get(clicked.javaId())
                    || BlockRegistries.INTERACTIVE_MAY_BUILD.get().get(clicked.javaId())) {
                original.translate(session, packet);
                return;
            }
        }
        if (!selectOffhand(session, packet)) { original.translate(session, packet); return; }
        if (consuming.containsKey(session)) return;
        var inventory = session.getPlayerInventory();
        int javaSlot = inventory.getOffsetForHotbar(packet.getHotbarSlot());
        var main = inventory.getItem(javaSlot);
        var off = inventory.getOffhand();
        var incoming = packet.getItemInHand() == null ? ItemData.AIR.getDefinition() : packet.getItemInHand().getDefinition();
        if (!incoming.equals(ItemTranslator.getBedrockItemDefinition(session, main))
                && !incoming.equals(ItemTranslator.getBedrockItemDefinition(session, off))) {
            session.getPlayerInventoryHolder().updateSlot(javaSlot);
            session.getPlayerInventoryHolder().updateSlot(45);
            return;
        }
        if (packet.getHotbarSlot() != inventory.getHeldItemSlot()) session.switchHeldSlot(packet.getHotbarSlot());
        session.getBookEditCache().checkForSend();
        if (packet.getActionType() == 1) {
            session.useItem(Hand.OFF_HAND);
            return;
        }
        if (packet.getBlockFace() < 0 || packet.getBlockFace() > 5 || packet.getBlockPosition() == null
                || packet.getClickPosition() == null) return;
        var position = packet.getBlockPosition();
        var direction = Direction.getUntrusted(packet, InventoryTransactionPacket::getBlockFace);
        var adjacent = BlockUtils.getBlockPosition(position, direction);
        if (!session.getWorldBorder().isInsideBorderBoundaries()
                || !BedrockInventoryTransactionTranslator.canInteractWithBlock(session,
                    session.getPlayerEntity().position().up(session.getEyeHeight()), position)) {
            BlockUtils.restoreCorrectBlock(session, adjacent, packet.getHotbarSlot());
            return;
        }
        var click = packet.getClickPosition();
        float x = cursor(click.getX()), y = cursor(click.getY()), z = cursor(click.getZ());
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || Math.abs(x - .5) >= 1.0000001 || Math.abs(y - .5) >= 1.0000001 || Math.abs(z - .5) >= 1.0000001) {
            BlockUtils.restoreCorrectBlock(session, adjacent, packet.getHotbarSlot());
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.getLastInteractionTime() < 110
                && position.distanceSquared(session.getLastInteractionBlockPosition()) < .00001
                && packet.getBlockFace() == session.getLastInteractionBlockFace()) {
            session.getPlayerInventoryHolder().updateSlot(45);
            return;
        }
        session.setLastInteractionTime(now);
        session.setLastInteractionBlockPosition(position);
        session.setLastInteractionPlayerPosition(session.getPlayerEntity().position());
        session.setLastInteractionBlockFace(packet.getBlockFace());
        var block = session.getGeyser().getWorldManager().blockAt(session, position);
        if (session.getBlockMappings().isItemFrame(packet.getBlockDefinition())) {
            original.translate(session, packet);
            return;
        }
        int sequence = session.getWorldCache().nextPredictionSequence();
        session.getWorldCache().markPositionInSequence(adjacent);
        session.sendDownstreamGamePacket(new ServerboundUseItemOnPacket(position, direction.mcpl(),
            Hand.OFF_HAND, x, y, z, false, false, sequence));
        if (off.asItem() instanceof BlockItem placed) {
            session.setLastBlockPlacePosition(adjacent);
            session.setLastBlockPlaced(placed);
        }
        boolean interactive = !session.isSneaking() && (BlockRegistries.INTERACTIVE.get().get(block.javaId())
            || BlockRegistries.INTERACTIVE_MAY_BUILD.get().get(block.javaId()));
        String name = off.asItem().javaKey().value();
        if (!interactive && (name.equals("bucket") || name.endsWith("_bucket") || name.endsWith("_boat")
                || name.endsWith("_raft") || off.is(Items.GLASS_BOTTLE) || off.is(Items.LILY_PAD)
                || off.is(Items.FROGSPAWN))) session.useItem(Hand.OFF_HAND, true);
        session.setInteracting(true);
        session.getPlayerInventoryHolder().updateSlot(45);
    }

    private static float cursor(float value) {
        if (value <= -.5f) return -.25f;
        if (value >= 2f) return 1.25f;
        return value;
    }
}
