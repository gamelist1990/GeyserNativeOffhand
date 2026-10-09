package org.pexserver.nativeoffhand;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.item.custom.GeyserCustomItemDefinition;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

public final class NativeOffhandExtension implements Extension {
    private record Original(boolean componentBased, NbtMap data) {}
    private final Map<ItemDefinition, Original> originals = new IdentityHashMap<>();
    private Field componentBased;
    private Field componentData;
    private PacketTranslator<InventoryTransactionPacket> previous;
    private OffhandInventoryTransactionTranslator installed;
    private org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty useProperty;
    private PacketTranslator<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket> previousMetadata;
    private PacketTranslator<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket> metadataHook;

    @Subscribe
    public void defineItems(GeyserDefineCustomItemsEvent event) {
        try { logger().info("Registered " + ItemCatalog.register(event) + " automatic data-driven item fallbacks."); }
        catch (Exception failure) { throw new IllegalStateException("Cannot register native offhand item catalog", failure); }
    }

    @Subscribe
    public void defineProperties(org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent event) {
        useProperty = event.registerIntegerProperty(Identifier.of("minecraft:player"), Identifier.of("nativeoffhand:use"), 0, 2, 0);
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
        } catch (Exception failure) { throw new IllegalStateException("Cannot register offhand animations and icon aliases", failure); }
    }

    @Subscribe
    public void initialize(GeyserPostInitializeEvent event) {
        try {
            // The allow_off_hand property is also used by Geyser's vanilla cake,
            // armor stand and firework overrides. Preserve identifier, runtime ID,
            // item version, native components, textures and Java mapping.
            componentBased = SimpleItemDefinition.class.getDeclaredField("componentBased");
            componentData = SimpleItemDefinition.class.getDeclaredField("componentData");
            componentBased.setAccessible(true);
            componentData.setAccessible(true);
            for (var mappings : Registries.ITEMS.get().values()) {
                for (ItemDefinition definition : mappings.getItemDefinitions().values()) {
                    if (definition.getRuntimeId() == 0 || originals.containsKey(definition)) continue;
                    if (!(definition instanceof SimpleItemDefinition))
                        throw new IllegalStateException("Unsupported item definition: " + definition.getClass());
                    NbtMap old = definition.getComponentData();
                    originals.put(definition, new Original(definition.isComponentBased(), old));
                    NbtMap root = old == null ? NbtMap.EMPTY : old;
                    NbtMap components = root.getCompound("components");
                    NbtMap properties = components.getCompound("item_properties").toBuilder()
                        .putBoolean("allow_off_hand", true).build();
                    NbtMap patched = root.toBuilder()
                        .putString("name", definition.getIdentifier()).putInt("id", definition.getRuntimeId())
                        .putCompound("components", components.toBuilder()
                            .putCompound("item_properties", properties).build()).build();
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
            var animations = new OffhandAnimations(useProperty);
            metadataHook = new PacketTranslator<>() {
                @Override public void translate(org.geysermc.geyser.session.GeyserSession session,
                        org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket packet) {
                    previousMetadata.translate(session, packet);
                    animations.accept(session, packet);
                }
            };
            Registries.JAVA_PACKET_TRANSLATORS.register(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class, metadataHook);
            logger().info("Enabled native offhand permission on " + originals.size()
                + " item definitions; installed validated native Java hand routing.");
        } catch (Throwable failure) {
            restore();
            logger().error("Native offhand initialization failed; restored original definitions: " + failure);
        }
    }

    private void restore() {
        if (metadataHook != null && Registries.JAVA_PACKET_TRANSLATORS.get(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class) == metadataHook)
            Registries.JAVA_PACKET_TRANSLATORS.register(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket.class, previousMetadata);
        OffhandAnimations.clear();
        if (installed != null && Registries.BEDROCK_PACKET_TRANSLATORS.get(InventoryTransactionPacket.class) == installed)
            Registries.BEDROCK_PACKET_TRANSLATORS.register(InventoryTransactionPacket.class, previous);
        originals.forEach((definition, old) -> {
            try {
                componentBased.setBoolean(definition, old.componentBased());
                componentData.set(definition, old.data());
            } catch (IllegalAccessException e) {
                logger().error("Cannot restore item definition " + definition.getIdentifier() + ": " + e);
            }
        });
        originals.clear();
        installed = null;
    }

    @Subscribe
    public void disconnect(org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent event) {
        if (event.connection() instanceof org.geysermc.geyser.session.GeyserSession session) OffhandAnimations.disconnect(session);
    }

    @Subscribe
    public void shutdown(GeyserShutdownEvent event) { restore(); }
}
