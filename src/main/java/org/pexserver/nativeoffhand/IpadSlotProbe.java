package org.pexserver.nativeoffhand;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.geysermc.floodgate.util.DeviceOs;
import org.geysermc.geyser.session.GeyserSession;

/** Observes actual iPad inventory requests; does not infer UI selection from held slots. */
final class IpadSlotProbe {
    private static final Map<GeyserSession, Integer> OPEN = new ConcurrentHashMap<>();
    private static Consumer<String> logger = ignored -> {};
    static void logger(Consumer<String> value) { logger = value; }

    static boolean isIpad(GeyserSession session) {
        var client = session.getClientData();
        return client != null && client.getDeviceOs() == DeviceOs.IOS
            && client.getDeviceModel() != null
            && client.getDeviceModel().toLowerCase(Locale.ROOT).startsWith("ipad");
    }

    static void screen(GeyserSession session, boolean playerInventory) {
        if (!playerInventory) {
            if (OPEN.remove(session) != null) {
                log(session, "close");
                NativeHeldView.invalidateOffhand(session);
                session.getPlayerInventoryHolder().updateInventory();
            }
            return;
        }
        if (!isIpad(session)) return;
        if (OPEN.putIfAbsent(session, 64) != null) return;
        log(session, "open; iPad inventory move slots enabled; active held slot stays native");
        NativeHeldView.invalidateOffhand(session);
        session.getPlayerInventoryHolder().updateInventory();
    }

    static void received(GeyserSession session, BedrockPacket packet) {
        var remaining = OPEN.get(session);
        if (remaining == null || remaining <= 0) return;
        String detail;
        if (packet instanceof ItemStackRequestPacket requests) {
            var slots = new StringBuilder("stack-request");
            for (var request : requests.getRequests()) for (var action : request.getActions()) {
                if (slots.length() > 800) break;
                if (action instanceof TransferItemStackRequestAction move) {
                    slots.append(' ').append(move.getSource().getContainer()).append(':').append(move.getSource().getSlot())
                        .append(" -> ").append(move.getDestination().getContainer()).append(':').append(move.getDestination().getSlot());
                } else if (action instanceof SwapAction swap) {
                    slots.append(" swap ").append(swap.getSource().getContainer()).append(':').append(swap.getSource().getSlot())
                        .append(" <-> ").append(swap.getDestination().getContainer()).append(':').append(swap.getDestination().getSlot());
                } else slots.append(' ').append(action.getType());
            }
            detail = slots.toString();
        } else if (packet instanceof MobEquipmentPacket equipment) {
            detail = "held-slot inventory=" + equipment.getInventorySlot() + " hotbar=" + equipment.getHotbarSlot()
                + " container=" + equipment.getContainerId() + "; not an inventory-selection signal";
        } else if (packet instanceof InventoryTransactionPacket transaction) {
            var slots = new StringBuilder("transaction " + transaction.getTransactionType());
            for (var action : transaction.getActions()) {
                if (slots.length() > 800) break;
                slots.append(" container=").append(action.getSource().getContainerId()).append(" slot=").append(action.getSlot());
            }
            detail = slots.toString();
        } else return;
        OPEN.put(session, remaining - 1);
        log(session, detail);
    }

    static boolean inventoryOpen(GeyserSession session) { return OPEN.containsKey(session); }

    private static void log(GeyserSession session, String message) {
        logger.accept("[iPad slots " + session.getPlayerEntity().geyserId() + "] " + message);
    }
    static void forget(GeyserSession session) { OPEN.remove(session); }
    static void clear() { OPEN.clear(); logger = ignored -> {}; }
    private IpadSlotProbe() {}
}
