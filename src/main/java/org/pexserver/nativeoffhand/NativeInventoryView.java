package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.session.GeyserSession;

/** Only Bedrock presentation changes; Java stacks and their components do not. */
final class NativeInventoryView {
    private static final ThreadLocal<GeyserSession> SCOPE = new ThreadLocal<>();

    static boolean open(GeyserSession session) {
        var holder = session.getInventoryHolder();
        return holder != null && holder.inventory() instanceof PlayerInventory && holder.inventory().isDisplayed();
    }

    static boolean aliasesEnabled() {
        var session = SCOPE.get();
        return session != null && open(session);
    }

    static void translate(GeyserSession session, Runnable action) {
        var old = SCOPE.get();
        boolean wasOpen = open(session);
        SCOPE.set(session);
        try { action.run(); }
        finally {
            if (old == null) SCOPE.remove(); else SCOPE.set(old);
        }
        if (wasOpen && !open(session)) restoreNativeView(session);
        else if (open(session)) refreshNativeEquipment(session);
    }

    private static boolean aliased(GeyserItemStack stack) {
        return !stack.isEmpty() && NativeItemCatalog.hasAlias(stack.asItem().javaKey().asString());
    }

    private static void clearSlot(GeyserSession session, int container, int slot) {
        var packet = new InventorySlotPacket();
        packet.setContainerId(container);
        packet.setSlot(slot);
        packet.setItem(ItemData.AIR);
        session.sendUpstreamPacket(packet);
    }

    private static void equip(GeyserSession session, ItemData item, int container, int slot) {
        var packet = new MobEquipmentPacket();
        packet.setRuntimeEntityId(session.getPlayerEntity().geyserId());
        packet.setItem(item);
        packet.setContainerId(container);
        packet.setInventorySlot(slot);
        packet.setHotbarSlot(container == ContainerId.OFFHAND ? -1 : slot);
        session.sendUpstreamPacket(packet);
    }

    /** Equipment is an entity presentation, not a movable UI stack. */
    private static void refreshNativeEquipment(GeyserSession session) {
        var old = SCOPE.get();
        SCOPE.remove();
        try {
            var inventory = session.getPlayerInventory();
            int hotbar = inventory.getHeldItemSlot();
            // Translate outside the UI alias scope, as Geyser does for living
            // entity equipment. Do not associate the render-only item with the
            // UI stack's network identity.
            var main = inventory.getItemInHand().getItemData(session).toBuilder()
                .usingNetId(false).netId(0).build();
            var off = inventory.getOffhand().getItemData(session).toBuilder()
                .usingNetId(false).netId(0).build();
            equip(session, main, ContainerId.INVENTORY, hotbar);
            equip(session, off, ContainerId.OFFHAND, 0);
        } finally {
            if (old != null) SCOPE.set(old);
        }
    }

    static void restoreNativeView(GeyserSession session) {
        var inventory = session.getPlayerInventory();
        int hotbar = inventory.getHeldItemSlot();
        boolean main = aliased(inventory.getItemInHand());
        boolean off = aliased(inventory.getOffhand());
        if (main) clearSlot(session, ContainerId.INVENTORY, hotbar);
        if (off) {
            clearSlot(session, ContainerId.OFFHAND, 0);
            equip(session, ItemData.AIR, ContainerId.OFFHAND, 0);
        }
        // An alias and its native representation must not reuse a cached
        // Bedrock render identity. Assign fresh protocol-only stack IDs.
        // This does not replace, split, rename or otherwise mutate Java items.
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (aliased(stack)) stack.setNetId(session.getNextItemNetId());
        }
        session.getPlayerInventoryHolder().updateInventory();
        if (main) equip(session, inventory.getItemInHand().getItemData(session), ContainerId.INVENTORY, hotbar);
        if (off) equip(session, inventory.getOffhand().getItemData(session), ContainerId.OFFHAND, 0);
    }

    private NativeInventoryView() {}
}
