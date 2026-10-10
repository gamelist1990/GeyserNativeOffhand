package org.pexserver.nativeoffhand;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

public final class NativeOffhandExtension implements Extension {
    private record Original(ItemVersion version, boolean componentBased, NbtMap data) {}
    private final Map<ItemDefinition, Original> originals = new IdentityHashMap<>();
    private Field itemVersion;
    private Field componentBased;
    private Field componentData;
    private Field translationString;
    private final Map<org.geysermc.geyser.registry.type.ItemMapping, String> originalTranslations = new IdentityHashMap<>();
    private org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty offhandPose;
    private final java.util.List<Runnable> visualRestorers = new java.util.ArrayList<>();
    private PacketTranslator<InventoryTransactionPacket> previous;
    private OffhandInventoryTransactionTranslator installed;
    private org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty consumeMode;
    private PacketTranslator<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket> previousMetadata;
    private PacketTranslator<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket> metadataHook;

    @Subscribe
    public void defineItems(GeyserDefineCustomItemsEvent event) {
        try {
            int fallbacks = ItemCatalog.register(event);
            logger().info("Registered " + fallbacks + " automatic data-driven item fallbacks; retained native block mappings for "
                + ItemCatalog.nativeBlockCount() + " Java items with inventory-only aliases.");
        }
        catch (Exception failure) { throw new IllegalStateException("Cannot register native offhand item catalog", failure); }
    }

    @Subscribe
    public void defineProperties(org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent event) {
        consumeMode = event.registerIntegerProperty(org.geysermc.geyser.api.util.Identifier.of("minecraft:player"),
            org.geysermc.geyser.api.util.Identifier.of("nativeoffhand:consume_mode"), 0, 2, 0);
        offhandPose = event.registerIntegerProperty(org.geysermc.geyser.api.util.Identifier.of("minecraft:player"),
            org.geysermc.geyser.api.util.Identifier.of("nativeoffhand:offhand_pose"), 0, 3, 0);
    }

    @Subscribe
    public void packs(org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent event) {
        try {
            java.nio.file.Files.createDirectories(dataFolder());
            var target = dataFolder().resolve("GeyserNativeOffhand.mcpack");
            try (var stream = getClass().getResourceAsStream("/GeyserNativeOffhand.mcpack")) {
                if (stream == null) throw new java.io.IOException("Bundled pack is missing");
                java.nio.file.Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            event.register(org.geysermc.geyser.api.pack.ResourcePack.create(org.geysermc.geyser.api.pack.PackCodec.path(target)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot register offhand icon aliases", failure); }
    }

    @Subscribe
    public void initialize(GeyserPostInitializeEvent event) {
        try {
            // Retain native item versions and state-aware block mappings.
            // Save and restore every modified permission and definition.
            translationString = org.geysermc.geyser.registry.type.ItemMapping.class.getDeclaredField("translationString");
            translationString.setAccessible(true);
            itemVersion = SimpleItemDefinition.class.getDeclaredField("version");
            itemVersion.setAccessible(true);
            componentBased = SimpleItemDefinition.class.getDeclaredField("componentBased");
            componentData = SimpleItemDefinition.class.getDeclaredField("componentData");
            componentBased.setAccessible(true);
            componentData.setAccessible(true);
            java.util.Set<String> foodDefinitions = new java.util.HashSet<>();
            try (var stream = getClass().getResourceAsStream("/food-textures.json")) {
                if (stream == null) throw new java.io.IOException("Food texture catalog is missing");
                var names = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                names.keySet().forEach(name -> foodDefinitions.add("nativeoffhand:" + name));
            }
            for (var mappings : Registries.ITEMS.get().values()) {
                for (var mapping : mappings.getItems()) {
                    if (mapping == null || (!ItemCatalog.isFallback(mapping.getJavaItem().javaKey().asString())
                            && !ItemCatalog.usesNativeBlock(mapping.getJavaItem().javaKey().asString()))
                            || originalTranslations.containsKey(mapping)) continue;
                    originalTranslations.put(mapping, mapping.getTranslationString());
                    // Use Geyser's per-session Java locale, preserving custom names,
                    // rarity and potion-specific names in its normal item translator.
                    translationString.set(mapping, mapping.getJavaItem().translationKey());
                }
                for (ItemDefinition definition : mappings.getItemDefinitions().values()) {
                    if (definition.getRuntimeId() == 0 || originals.containsKey(definition)) continue;
                    if (!(definition instanceof SimpleItemDefinition))
                        throw new IllegalStateException("Unsupported item definition: " + definition.getClass());
                    NbtMap old = definition.getComponentData();
                    originals.put(definition, new Original(definition.getVersion(), definition.isComponentBased(), old));
                    NbtMap root = old == null ? NbtMap.EMPTY : old;
                    NbtMap components = root.getCompound("components");
                    var properties = components.getCompound("item_properties").toBuilder()
                        .putBoolean("allow_off_hand", true);
                    var patchedComponents = components.toBuilder().putCompound("item_properties", properties.build());
                    if (definition.getIdentifier().startsWith("nativeoffhand:")
                            && !foodDefinitions.contains(definition.getIdentifier())
                            && !definition.getIdentifier().endsWith(":torch")
                            && !definition.getIdentifier().endsWith("_torch")) {
                        // Native first-person empty_hand positions the right arm,
                        // but not the left arm for generic items. The pack mirrors
                        // that vanilla pose only for these owned fallbacks.
                        // Food attachables and native torch rendering retain their
                        // existing positions; other packs' items are not tagged.
                        var tags = new java.util.ArrayList<>(components.getList("item_tags", NbtType.STRING));
                        if (!tags.contains("nativeoffhand:vanilla_offhand_pose"))
                            tags.add("nativeoffhand:vanilla_offhand_pose");
                        if (components.getCompound("item_properties").getBoolean("hand_equipped")
                                && !tags.contains("nativeoffhand:handheld_pose"))
                            tags.add("nativeoffhand:handheld_pose");
                        patchedComponents.putList("item_tags", NbtType.STRING, tags);
                    }
                    NbtMap patched = root.toBuilder()
                        .putString("name", definition.getIdentifier()).putInt("id", definition.getRuntimeId())
                        .putCompound("components", patchedComponents.build()).build();
                    componentData.set(definition, patched);
                    componentBased.setBoolean(definition, true);
                }
            }
            previous = (PacketTranslator<InventoryTransactionPacket>) Registries.BEDROCK_PACKET_TRANSLATORS.get(InventoryTransactionPacket.class);
            if (previous == null) throw new IllegalStateException("Inventory translator is missing");
            installed = new OffhandInventoryTransactionTranslator(previous);
            Registries.BEDROCK_PACKET_TRANSLATORS.register(InventoryTransactionPacket.class, installed);
            previousMetadata = (PacketTranslator<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket>)
                Registries.JAVA_PACKET_TRANSLATORS.get(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class);
            var useState = new OffhandUseState(consumeMode);
            metadataHook = new PacketTranslator<>() {
                @Override public void translate(org.geysermc.geyser.session.GeyserSession session,
                        org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket packet) {
                    previousMetadata.translate(session, packet);
                    useState.accept(session, packet);
                }
            };
            Registries.JAVA_PACKET_TRANSLATORS.register(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class, metadataHook);
            var visualState = new OffhandVisualState(offhandPose);
            hookVisual(Registries.JAVA_PACKET_TRANSLATORS,
                org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket.class, visualState);
            hookVisual(Registries.JAVA_PACKET_TRANSLATORS,
                org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket.class, visualState);
            hookVisual(Registries.BEDROCK_PACKET_TRANSLATORS,
                org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket.class, visualState);
            hookVisual(Registries.BEDROCK_PACKET_TRANSLATORS, InventoryTransactionPacket.class, visualState);
            hookVisual(Registries.BEDROCK_PACKET_TRANSLATORS,
                org.cloudburstmc.protocol.bedrock.packet.InteractPacket.class, visualState);
            hookVisual(Registries.BEDROCK_PACKET_TRANSLATORS,
                org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket.class, visualState);
            hookVisual(Registries.JAVA_PACKET_TRANSLATORS,
                org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket.class, visualState);
            hookVisual(Registries.JAVA_PACKET_TRANSLATORS,
                org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket.class, visualState);
            logger().info("Enabled native offhand permission on " + originals.size()
                + " item definitions; installed validated native Java hand routing.");
        } catch (Throwable failure) {
            restore();
            logger().error("Native offhand initialization failed; restored original definitions: " + failure);
        }
    }

    @SuppressWarnings("unchecked")
    private <T, P extends T> void hookVisual(org.geysermc.geyser.registry.PacketTranslatorRegistry<T> registry,
            Class<P> packetType, OffhandVisualState visualState) {
        var delegate = (PacketTranslator<P>) registry.get(packetType);
        if (delegate == null) throw new IllegalStateException("Missing inventory translator: " + packetType);
        PacketTranslator<P> wrapper = new PacketTranslator<>() {
            @Override public void translate(org.geysermc.geyser.session.GeyserSession session, P packet) {
                boolean hadPlayerInventory = InventoryBlockPresentation.playerInventoryOpen(session);
                InventoryBlockPresentation.translate(session, () -> delegate.translate(session, packet));
                if (hadPlayerInventory && !InventoryBlockPresentation.playerInventoryOpen(session)) {
                    // Refresh all slots, including OFFHAND, using native
                    // definitions after either a client or server close.
                    session.getPlayerInventoryHolder().updateInventory();
                }
                visualState.update(session);
            }
            @Override public boolean shouldExecuteInEventLoop() { return delegate.shouldExecuteInEventLoop(); }
        };
        registry.register(packetType, wrapper);
        visualRestorers.add(() -> {
            if (registry.get(packetType) == wrapper) registry.register(packetType, delegate);
        });
    }

    private void restore() {
        for (int i = visualRestorers.size() - 1; i >= 0; i--) visualRestorers.get(i).run();
        visualRestorers.clear();
        OffhandVisualState.clear();
        if (metadataHook != null && Registries.JAVA_PACKET_TRANSLATORS.get(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class) == metadataHook)
            Registries.JAVA_PACKET_TRANSLATORS.register(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class, previousMetadata);
        OffhandUseState.clear();
        if (installed != null && Registries.BEDROCK_PACKET_TRANSLATORS.get(InventoryTransactionPacket.class) == installed)
            Registries.BEDROCK_PACKET_TRANSLATORS.register(InventoryTransactionPacket.class, previous);
        originals.forEach((definition, old) -> {
            try {
                itemVersion.set(definition, old.version());
                componentBased.setBoolean(definition, old.componentBased());
                componentData.set(definition, old.data());
            } catch (IllegalAccessException e) {
                logger().error("Cannot restore item definition " + definition.getIdentifier() + ": " + e);
            }
        });
        originals.clear();
        originalTranslations.forEach((mapping, old) -> {
            try { translationString.set(mapping, old); }
            catch (IllegalAccessException e) { logger().error("Cannot restore item translation: " + e); }
        });
        originalTranslations.clear();
        installed = null;
    }

    @Subscribe
    public void disconnect(org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent event) {
        if (event.connection() instanceof org.geysermc.geyser.session.GeyserSession session) {
            OffhandUseState.disconnect(session);
            OffhandVisualState.disconnect(session);
        }
    }

    @Subscribe
    public void shutdown(GeyserShutdownEvent event) { restore(); }
}
