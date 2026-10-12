package org.pexserver.nativeoffhand;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.*;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.registry.*;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

public final class NativeOffhandExtension implements Extension {
    private final NativeUseRouter use = new NativeUseRouter();
    private final NativeOffhandHud hud = new NativeOffhandHud();
    private final List<Runnable> restore = new ArrayList<>();
    private final Map<SimpleItemDefinition, NbtMap> nativeData = new IdentityHashMap<>();
    private final Map<org.geysermc.geyser.registry.type.ItemMapping, String> names = new IdentityHashMap<>();
    private Field componentData, translation;

    @Subscribe public void items(GeyserDefineCustomItemsEvent event) {
        try { logger().info("Registered " + NativeItemCatalog.register(event) + " stable move-capable definitions; HUD retains native mappings."); }
        catch (Exception error) { throw new IllegalStateException("Cannot define inventory aliases", error); }
    }

    @Subscribe public void packs(GeyserDefineResourcePacksEvent event) {
        try {
            Files.createDirectories(dataFolder());
            var target = dataFolder().resolve("GeyserNativeOffhand.mcpack");
            try (var stream = getClass().getResourceAsStream("/GeyserNativeOffhand.mcpack")) {
                if (stream == null) throw new java.io.IOException("Missing bundled pack");
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            event.register(org.geysermc.geyser.api.pack.ResourcePack.create(org.geysermc.geyser.api.pack.PackCodec.path(target)));
        } catch (Exception error) { throw new IllegalStateException("Cannot register inventory icons", error); }
    }

    @Subscribe public void initialize(GeyserPostInitializeEvent event) {
        try {
            PaperUseBridge.initialize();
            componentData = SimpleItemDefinition.class.getDeclaredField("componentData");
            componentData.setAccessible(true);
            translation = org.geysermc.geyser.registry.type.ItemMapping.class.getDeclaredField("translationString");
            translation.setAccessible(true);
            for (var mappings : Registries.ITEMS.get().values()) {
                for (var mapping : mappings.getItems()) {
                    if (mapping == null || !NativeItemCatalog.hasAlias(mapping.getJavaItem().javaKey().asString())
                            || names.containsKey(mapping)) continue;
                    names.put(mapping, mapping.getTranslationString());
                    translation.set(mapping, mapping.getJavaItem().translationKey());
                }
                for (var definition : mappings.getItemDefinitions().values()) {
                    // NONE and LEGACY retain their complete native definitions.
                    // Only the already data-driven vanilla items need this flag.
                    if (!(definition instanceof SimpleItemDefinition simple)
                            || definition.getVersion() != ItemVersion.DATA_DRIVEN
                            || definition.getIdentifier().startsWith("nativeoffhand_ui:") || nativeData.containsKey(simple)) continue;
                    var root = definition.getComponentData();
                    if (root == null) throw new IllegalStateException("Missing native components: " + definition.getIdentifier());
                    nativeData.put(simple, root);
                    var components = root.getCompound("components");
                    var properties = components.getCompound("item_properties").toBuilder().putBoolean("allow_off_hand", true).build();
                    componentData.set(simple, root.toBuilder().putCompound("components",
                        components.toBuilder().putCompound("item_properties", properties).build()).build());
                }
            }
            hook(Registries.JAVA_PACKET_TRANSLATORS, ClientboundContainerSetSlotPacket.class, false);
            hook(Registries.JAVA_PACKET_TRANSLATORS, ClientboundContainerSetContentPacket.class, false);
            hook(Registries.JAVA_PACKET_TRANSLATORS, ClientboundContainerClosePacket.class, false);
            hook(Registries.JAVA_PACKET_TRANSLATORS, ClientboundOpenScreenPacket.class, false);
            hook(Registries.BEDROCK_PACKET_TRANSLATORS, InteractPacket.class, false);
            hook(Registries.BEDROCK_PACKET_TRANSLATORS, ContainerClosePacket.class, false);
            hook(Registries.BEDROCK_PACKET_TRANSLATORS, ItemStackRequestPacket.class, false);
            hook(Registries.BEDROCK_PACKET_TRANSLATORS, InventoryTransactionPacket.class, true);
            hookMetadata();
            hookHud(ClientboundSetTitleTextPacket.class);
            hookHud(ClientboundSetSubtitleTextPacket.class);
            hookHud(ClientboundSetTitlesAnimationPacket.class);
            hookHud(ClientboundClearTitlesPacket.class);
            logger().info("Native renderer rewrite enabled; cursor/touch inventory definitions; vanilla held mappings; same-connection Paper use routing.");
        } catch (Throwable error) {
            restoreAll();
            logger().error("Native offhand initialization failed: " + error);
        }
    }

    @SuppressWarnings("unchecked")
    private <T, P extends T> void hook(PacketTranslatorRegistry<T> registry, Class<P> type, boolean route) {
        var delegate = (PacketTranslator<P>) registry.get(type);
        if (delegate == null) throw new IllegalStateException("Missing translator: " + type);
        PacketTranslator<P> wrapper = new PacketTranslator<>() {
            @Override public void translate(GeyserSession session, P packet) {
                if (packet instanceof ItemStackRequestPacket request) NativeInventoryView.beforeRequest(session, request);
                NativeInventoryView.translate(session, () -> {
                    if (route) use.translate(session, (InventoryTransactionPacket) packet,
                        (PacketTranslator<InventoryTransactionPacket>) delegate);
                    else delegate.translate(session, packet);
                });
                if (packet instanceof ItemStackRequestPacket request) NativeInventoryView.afterRequest(session, request);
                NativeInventoryView.refresh(session);
                use.refresh(session);
                hud.update(session);
            }
            @Override public boolean shouldExecuteInEventLoop() { return delegate.shouldExecuteInEventLoop(); }
        };
        registry.register(type, wrapper);
        restore.add(() -> { if (registry.get(type) == wrapper) registry.register(type, delegate); });
    }

    private <P extends org.geysermc.mcprotocollib.network.packet.Packet> void hookHud(Class<P> type) {
        @SuppressWarnings("unchecked")
        var delegate = (PacketTranslator<P>) Registries.JAVA_PACKET_TRANSLATORS.get(type);
        if (delegate == null) throw new IllegalStateException("Missing title translator: " + type);
        PacketTranslator<P> wrapper = new PacketTranslator<>() {
            @Override public void translate(GeyserSession session, P packet) {
                hud.refresh(session);
            }
            @Override public boolean shouldExecuteInEventLoop() { return delegate.shouldExecuteInEventLoop(); }
        };
        Registries.JAVA_PACKET_TRANSLATORS.register(type, wrapper);
        restore.add(() -> { if (Registries.JAVA_PACKET_TRANSLATORS.get(type) == wrapper)
            Registries.JAVA_PACKET_TRANSLATORS.register(type, delegate); });
    }

    @SuppressWarnings("unchecked")
    private void hookMetadata() {
        var type = ClientboundSetEntityDataPacket.class;
        var delegate = (PacketTranslator<ClientboundSetEntityDataPacket>) Registries.JAVA_PACKET_TRANSLATORS.get(type);
        if (delegate == null) throw new IllegalStateException("Missing metadata translator");
        PacketTranslator<ClientboundSetEntityDataPacket> wrapper = new PacketTranslator<>() {
            @Override public void translate(GeyserSession session, ClientboundSetEntityDataPacket packet) {
                delegate.translate(session, use.metadata(session, packet));
            }
            @Override public boolean shouldExecuteInEventLoop() { return delegate.shouldExecuteInEventLoop(); }
        };
        Registries.JAVA_PACKET_TRANSLATORS.register(type, wrapper);
        restore.add(() -> { if (Registries.JAVA_PACKET_TRANSLATORS.get(type) == wrapper) Registries.JAVA_PACKET_TRANSLATORS.register(type, delegate); });
    }

    private void restoreAll() {
        for (int i = restore.size() - 1; i >= 0; i--) restore.get(i).run();
        restore.clear();
        use.clear();
        PaperUseBridge.clear();
        NativeInventoryView.clear();
        hud.clear();
        nativeData.forEach((item, data) -> { try { componentData.set(item, data); } catch (Exception error) { logger().error(error.toString()); } });
        nativeData.clear();
        names.forEach((mapping, name) -> { try { translation.set(mapping, name); } catch (Exception error) { logger().error(error.toString()); } });
        names.clear();
    }

    @Subscribe public void join(org.geysermc.geyser.api.event.bedrock.SessionJoinEvent event) {
        if (event.connection() instanceof GeyserSession session) { NativeHeldView.install(session); hud.start(session); }
    }

    @Subscribe public void disconnect(org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent event) {
        if (event.connection() instanceof GeyserSession session) { use.forget(session); hud.forget(session); NativeInventoryView.forget(session); }
    }
    @Subscribe public void shutdown(GeyserShutdownEvent event) { restoreAll(); }
}
