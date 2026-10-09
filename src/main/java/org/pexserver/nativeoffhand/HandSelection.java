package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.item.type.BlockItem;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import java.util.Set;

final class HandSelection {
    private static final ThreadLocal<Hand> ACTIVE = new ThreadLocal<>();
    private static final Set<String> ACTIVE_ITEMS = Set.of(
        "bow", "crossbow", "trident", "shield", "fishing_rod", "flint_and_steel",
        "fire_charge", "shears", "brush", "spyglass", "goat_horn",
        "snowball", "egg", "ender_pearl", "ender_eye", "experience_bottle",
        "wind_charge", "firework_rocket", "splash_potion", "lingering_potion",
        "glass_bottle", "potion", "bundle", "lead", "name_tag",
        "writable_book", "written_book", "armor_stand", "saddle"
    );
    static Hand hand() { return ACTIVE.get() == null ? Hand.MAIN_HAND : ACTIVE.get(); }
    static GeyserItemStack item(GeyserSession session) {
        return session.getPlayerInventory().getItemInHand(hand());
    }
    static void enter() { ACTIVE.set(Hand.OFF_HAND); }
    static void exit() { ACTIVE.remove(); }

    static boolean shouldUseOffhand(GeyserSession session, InventoryTransactionPacket packet) {
        GeyserItemStack off = session.getPlayerInventory().getOffhand();
        if (off.isEmpty() || packet.getHotbarSlot() < 0 || packet.getHotbarSlot() > 8) return false;
        GeyserItemStack main = session.getPlayerInventory().getItemInHand();
        if (packet.getItemInHand() != null && !main.isEmpty()) {
            var incoming = packet.getItemInHand().getDefinition();
            var offDefinition = ItemTranslator.getBedrockItemDefinition(session, off);
            var mainDefinition = ItemTranslator.getBedrockItemDefinition(session, main);
            if (incoming.equals(offDefinition) && !incoming.equals(mainDefinition)) return true;
        }
        if (main.isEmpty()) return true;
        // Bedrock does not identify a hand in ITEM_USE. Keep useful main-hand
        // actions first; infer an offhand fallback only when main is passive.
        if (main.getComponent(DataComponentTypes.CONSUMABLE) != null
                || main.getComponent(DataComponentTypes.EQUIPPABLE) != null
                || main.getComponent(DataComponentTypes.BLOCKS_ATTACKS) != null
                || main.getComponent(DataComponentTypes.PIERCING_WEAPON) != null
                || main.getComponent(DataComponentTypes.KINETIC_WEAPON) != null) return false;
        String id = main.asItem().javaKey().value();
        if (main.asItem() instanceof BlockItem) return packet.getActionType() == 1;
        if (id.endsWith("_axe") || id.endsWith("_hoe") || id.endsWith("_shovel"))
            return packet.getActionType() == 1;
        if (id.endsWith("_bucket") || id.equals("bucket") || id.endsWith("_spawn_egg")
                || id.endsWith("_boat") || id.endsWith("_raft") || ACTIVE_ITEMS.contains(id)) return false;
        return true;
    }
}
