package org.pexserver.nativeoffhand;

import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.session.GeyserSession;

/**
 * Selects a movable block definition only while translating the player's
 * native inventory UI. Java stacks, components and stack network IDs remain
 * unchanged. Outside this scope, Geyser retains its original block renderer.
 */
final class InventoryBlockPresentation {
    private static final ThreadLocal<GeyserSession> TRANSLATING = new ThreadLocal<>();

    static boolean playerInventoryOpen(GeyserSession session) {
        var holder = session.getInventoryHolder();
        return holder != null && holder.inventory() instanceof PlayerInventory
            && holder.inventory().isDisplayed();
    }

    static boolean useUiDefinition() {
        var session = TRANSLATING.get();
        return session != null && playerInventoryOpen(session);
    }

    static void translate(GeyserSession session, Runnable action) {
        var previous = TRANSLATING.get();
        TRANSLATING.set(session);
        try {
            action.run();
        } finally {
            if (previous == null) TRANSLATING.remove();
            else TRANSLATING.set(previous);
        }
    }

    private InventoryBlockPresentation() {}
}
