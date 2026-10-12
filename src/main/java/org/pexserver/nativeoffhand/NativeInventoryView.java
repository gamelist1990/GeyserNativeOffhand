package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.inventory.InventoryHolder;
import org.geysermc.geyser.translator.inventory.PlayerInventoryTranslator;
import org.geysermc.geyser.translator.inventory.InventoryTranslator;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Only Bedrock presentation changes; Java stacks and their components do not. */
final class NativeInventoryView {
    private static final ThreadLocal<GeyserSession> SCOPE = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> PREPARING = new ThreadLocal<>();
    private record Installed(InventoryTranslator<?> original, InventoryTranslator<?> replacement) {}
    private static final Map<InventoryHolder<?>, Installed> ORIGINALS = new ConcurrentHashMap<>();
    private static final Field TRANSLATOR = translatorField();

    private static Field translatorField() {
        try {
            var field = InventoryHolder.class.getDeclaredField("translator");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot install inventory pre-open presentation", error);
        }
    }

    private static void install(GeyserSession session) {
        var holder = session.getPlayerInventoryHolder();
        if (ORIGINALS.containsKey(holder)) return;
        try {
            var original = (InventoryTranslator<?>) TRANSLATOR.get(holder);
            // Do not replace another extension's inventory translator.
            if (original.getClass() != PlayerInventoryTranslator.class) return;
            var replacement = new PlayerInventoryTranslator() {
                @Override public void openInventory(GeyserSession owner, PlayerInventory inventory) {
                    // Publish move-capable definitions BEFORE ContainerOpen, rather
                    // than letting the first inventory frame use native definitions.
                    var scope = SCOPE.get();
                    var preparing = PREPARING.get();
                    SCOPE.set(owner);
                    PREPARING.set(true);
                    try { super.updateInventory(owner, inventory); }
                    finally {
                        if (scope == null) SCOPE.remove(); else SCOPE.set(scope);
                        if (preparing == null) PREPARING.remove(); else PREPARING.set(preparing);
                    }
                    super.openInventory(owner, inventory);
                }
            };
            TRANSLATOR.set(holder, replacement);
            ORIGINALS.put(holder, new Installed(original, replacement));
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot prepare inventory before opening", error);
        }
    }

    static void forget(GeyserSession session) { restore(session.getPlayerInventoryHolder()); }
    static void clear() { for (var holder : ORIGINALS.keySet()) restore(holder); }
    private static void restore(InventoryHolder<?> holder) {
        var installed = ORIGINALS.remove(holder);
        if (installed == null) return;
        try {
            if (TRANSLATOR.get(holder) == installed.replacement()) TRANSLATOR.set(holder, installed.original());
        }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot restore inventory translator", error); }
    }

    static boolean open(GeyserSession session) {
        var holder = session.getInventoryHolder();
        return holder != null && holder.inventory() instanceof PlayerInventory && holder.inventory().isDisplayed();
    }

    static boolean aliasesEnabled() {
        var session = SCOPE.get();
        return session != null && (Boolean.TRUE.equals(PREPARING.get()) || open(session));
    }

    static void translate(GeyserSession session, Runnable action) {
        install(session);
        var old = SCOPE.get();
        boolean wasOpen = open(session);
        SCOPE.set(session);
        try { action.run(); }
        finally {
            if (old == null) SCOPE.remove(); else SCOPE.set(old);
        }
        if (wasOpen && !open(session)) restoreNativeView(session);
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
