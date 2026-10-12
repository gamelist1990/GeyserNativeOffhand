package org.pexserver.nativeoffhand;

import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.geysermc.geyser.session.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Temporary cursor/merge-target definitions; equipment keeps native mappings. */
final class NativeHeldView extends UpstreamSession {
    private record Installed(UpstreamSession original, NativeHeldView view) {}
    private static final Map<GeyserSession, Installed> INSTALLED = new ConcurrentHashMap<>();
    private static final Field UPSTREAM = upstreamField();
    private final GeyserSession owner;
    private final UpstreamSession delegate;
    private ItemData inventoryOffhand;
    private ItemData equippedOffhand;
    private java.util.List<ItemData> inventory;

    private NativeHeldView(GeyserSession owner, UpstreamSession delegate) {
        super(delegate.getSession());
        this.owner = owner;
        this.delegate = delegate;
    }

    private static Field upstreamField() {
        try {
            var field = GeyserSession.class.getDeclaredField("upstream");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot install offhand packet presentation", error);
        }
    }

    static void install(GeyserSession session) {
        if (INSTALLED.containsKey(session)) return;
        var original = session.getUpstream();
        var view = new NativeHeldView(session, original);
        try { UPSTREAM.set(session, view); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        INSTALLED.put(session, new Installed(original, view));
    }

    static void forget(GeyserSession session) {
        var installed = INSTALLED.remove(session);
        if (installed == null) return;
        try {
            if (session.getUpstream() == installed.view()) UPSTREAM.set(session, installed.original());
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    static void invalidateOffhand(GeyserSession session) {
        var installed = INSTALLED.get(session);
        if (installed != null) installed.view().inventoryOffhand = null;
    }

    static void clear() { for (var session : INSTALLED.keySet()) forget(session); }

    private boolean same(ItemData a, ItemData b) {
        return a != null && a.equals(b) && a.getNetId() == b.getNetId()
            && a.isUsingNetId() == b.isUsingNetId();
    }

    private BedrockPacket present(BedrockPacket packet) {
        if (packet instanceof ContainerOpenPacket open) {
            IpadSlotProbe.screen(owner, open.getType() == ContainerType.INVENTORY);
        } else if (packet instanceof ContainerClosePacket) {
            IpadSlotProbe.screen(owner, false);
        }
        if (packet instanceof InventoryContentPacket content && content.getContainerId() == ContainerId.INVENTORY) {
            var transformed = content.clone();
            var contents = new ArrayList<>(content.getContents());
            for (int i = 0; i < Math.min(36, contents.size()); i++) {
                int javaSlot = i < 9 ? i + 36 : i;
                NativeInventoryView.rememberSlot(owner, javaSlot, owner.getPlayerInventory().getItem(javaSlot));
                contents.set(i, NativeInventoryView.slotData(owner, owner.getPlayerInventory().getItem(javaSlot), contents.get(i)));
            }
            transformed.setContents(contents);
            content = transformed;
            packet = transformed;
            if (inventory != null && inventory.size() == content.getContents().size()) {
                boolean unchanged = true;
                for (int i = 0; i < inventory.size(); i++) {
                    if (!same(inventory.get(i), content.getContents().get(i))) { unchanged = false; break; }
                }
                if (unchanged) return null;
            }
            inventory = new ArrayList<>(content.getContents());
            return packet;
        }
        if (packet instanceof InventorySlotPacket slot && slot.getContainerId() == ContainerId.INVENTORY) {
            if (slot.getSlot() >= 0 && slot.getSlot() < 36) {
                var transformed = slot.clone();
                int javaSlot = slot.getSlot() < 9 ? slot.getSlot() + 36 : slot.getSlot();
                NativeInventoryView.rememberSlot(owner, javaSlot, owner.getPlayerInventory().getItem(javaSlot));
                transformed.setItem(NativeInventoryView.slotData(owner, owner.getPlayerInventory().getItem(javaSlot), slot.getItem()));
                slot = transformed;
                packet = transformed;
            }
            if (inventory != null && slot.getSlot() >= 0 && slot.getSlot() < inventory.size())
                inventory.set(slot.getSlot(), slot.getItem());
            return packet;
        }
        if (packet instanceof InventorySlotPacket slot && slot.getContainerId() == ContainerId.UI && slot.getSlot() == 0) {
            var copy = slot.clone();
            var sent = slot.getItem();
            if (sent != null && sent.getDefinition() != ItemData.AIR.getDefinition()
                    && !owner.getPlayerInventory().getCursor().isEmpty())
                copy.setItem(NativeInventoryView.cursorData(owner).toBuilder().count(sent.getCount())
                    .netId(sent.getNetId()).usingNetId(sent.isUsingNetId()).build());
            return copy;
        }
        if (packet instanceof InventoryContentPacket content && content.getContainerId() == ContainerId.OFFHAND
                && !content.getContents().isEmpty()) {
            var copy = content.clone();
            var items = new ArrayList<>(content.getContents());
            NativeInventoryView.rememberSlot(owner, 45, owner.getPlayerInventory().getOffhand());
            var item = NativeInventoryView.slotData(owner, owner.getPlayerInventory().getOffhand(), items.get(0));
            if (same(inventoryOffhand, item)) return null;
            inventoryOffhand = item;
            items.set(0, item);
            copy.setContents(items);
            return copy;
        }
        if (packet instanceof InventorySlotPacket slot && slot.getContainerId() == ContainerId.OFFHAND) {
            var copy = slot.clone();
            NativeInventoryView.rememberSlot(owner, 45, owner.getPlayerInventory().getOffhand());
            var item = NativeInventoryView.slotData(owner, owner.getPlayerInventory().getOffhand(), slot.getItem());
            if (same(inventoryOffhand, item)) return null;
            inventoryOffhand = item;
            copy.setItem(item);
            return copy;
        }
        if (packet instanceof MobEquipmentPacket equip && equip.getContainerId() == ContainerId.OFFHAND
                && equip.getRuntimeEntityId() == owner.getPlayerEntity().geyserId()) {
            var copy = equip.clone();
            var item = equip.getItem();
            if (same(equippedOffhand, item)) return null;
            equippedOffhand = item;
            copy.setItem(item);
            return copy;
        }
        return packet;
    }

    @Override public void sendPacket(BedrockPacket packet) { var view = present(packet); if (view != null) delegate.sendPacket(view); }
    @Override public void sendPacketImmediately(BedrockPacket packet) { var view = present(packet); if (view != null) delegate.sendPacketImmediately(view); }
    @Override public void queuePostStartGamePacket(BedrockPacket packet) { var view = present(packet); if (view != null) delegate.queuePostStartGamePacket(view); }
    @Override public void sendPostStartGamePackets() { delegate.sendPostStartGamePackets(); }
    @Override public void disconnect(String reason) { delegate.disconnect(reason); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
    @Override public InetSocketAddress getAddress() { return delegate.getAddress(); }
    @Override public void setInetAddress(InetSocketAddress address) { delegate.setInetAddress(address); }
    @Override public int getProtocolVersion() { return delegate.getProtocolVersion(); }
    @Override public org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper getCodecHelper() { return delegate.getCodecHelper(); }
    @Override public void forciblyClose() { delegate.forciblyClose(); }
    @Override public boolean isInitialized() { return delegate.isInitialized(); }
    @Override public void setInitialized(boolean initialized) { delegate.setInitialized(initialized); }
}
