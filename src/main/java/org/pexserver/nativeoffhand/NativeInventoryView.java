package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.session.GeyserSession;

/** Ordinary inventory stays vanilla; only the picked cursor uses move definitions. */
final class NativeInventoryView {
    private static final ThreadLocal<Boolean> MOVING = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> NATIVE = new ThreadLocal<>();

    private static final java.util.Set<GeyserSession> PICKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void beforeRequest(GeyserSession session, org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket packet) {
        for (var request : packet.getRequests()) for (var action : request.getActions()) {
            var endpoints = endpoints(action);
            if (endpoints.isEmpty()) continue;
            var source = endpoints.get(0);
            var destination = endpoints.get(1);
            var cursor = org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.CURSOR;
            if (destination.getContainer() == cursor) pickedFrom(session, source);
            else if (action instanceof org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
                    && source.getContainer() == cursor) pickedFrom(session, destination);
        }
    }

    private static void pickedFrom(GeyserSession session,
            org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData source) {
        if (source.getContainer() == org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.OFFHAND) PICKED.remove(session);
        else PICKED.add(session);
    }

    private static java.util.List<org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData>
            endpoints(org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction action) {
        if (action instanceof org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction move)
            return java.util.List.of(move.getSource(), move.getDestination());
        if (action instanceof org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction swap)
            return java.util.List.of(swap.getSource(), swap.getDestination());
        return java.util.List.of();
    }

    static void afterRequest(GeyserSession session, org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket packet) {
        var holder = session.getInventoryHolder();
        if (holder != null) {
            var slots = new java.util.HashSet<Integer>();
            for (var request : packet.getRequests()) for (var action : request.getActions()) {
                for (var slot : endpoints(action)) {
                    if (slot.getContainer() != org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.CURSOR) {
                        int javaSlot = holder.translator().bedrockSlotToJava(slot);
                        if (javaSlot >= 0) slots.add(javaSlot);
                    }
                }
            }
            NativeHeldView.invalidateOffhand(session);
            for (int slot : slots) holder.updateSlot(slot);
        }
        if (session.getPlayerInventory().getCursor().isEmpty()) PICKED.remove(session);
        org.geysermc.geyser.util.InventoryUtils.updateCursor(session);
    }

    static ItemData cursorData(GeyserSession session) {
        var stack = session.getPlayerInventory().getCursor();
        return PICKED.contains(session) ? movingData(session, stack) : nativeData(session, stack);
    }

    private static ItemData movingData(GeyserSession session, GeyserItemStack stack) {
        var previous = MOVING.get();
        MOVING.set(true);
        try { return stack.getItemData(session); }
        finally { if (previous == null) MOVING.remove(); else MOVING.set(previous); }
    }

    static ItemData nativeData(GeyserSession session, GeyserItemStack stack) {
        var previous = NATIVE.get();
        NATIVE.set(true);
        try { return stack.getItemData(session); }
        finally { if (previous == null) NATIVE.remove(); else NATIVE.set(previous); }
    }

    static boolean aliasesEnabled() {
        return Boolean.TRUE.equals(MOVING.get()) && !Boolean.TRUE.equals(NATIVE.get());
    }

    static void translate(GeyserSession session, Runnable action) {
        NativeHeldView.install(session);
        action.run();
    }

    static void forget(GeyserSession session) { PICKED.remove(session); NativeHeldView.forget(session); }
    static void clear() { PICKED.clear(); NativeHeldView.clear(); }
    private NativeInventoryView() {}
}
