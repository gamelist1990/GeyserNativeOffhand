package org.pexserver.nativeoffhand;

import com.google.gson.JsonParser;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.session.GeyserSession;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Synchronizes the actual selected offhand renderer with the player's animation. */
final class OffhandVisualState {
    private static final Map<GeyserSession, Integer> MODES = new ConcurrentHashMap<>();
    private final GeyserIntEntityProperty property;
    private final Set<String> foodNames;

    OffhandVisualState(GeyserIntEntityProperty property) {
        this.property = property;
        try (var stream = getClass().getResourceAsStream("/food-textures.json")) {
            if (stream == null) throw new IllegalStateException("Food texture catalog missing");
            foodNames = Set.copyOf(JsonParser.parseReader(new InputStreamReader(stream,
                StandardCharsets.UTF_8)).getAsJsonObject().keySet());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read food texture catalog", failure);
        }
    }

    void update(GeyserSession session) {
        var offhand = session.getPlayerInventory().getOffhand();
        int mode = 0;
        if (!offhand.isEmpty()) {
            String javaId = offhand.asItem().javaKey().asString();
            String name = javaId.substring(javaId.indexOf(':') + 1);
            var definition = offhand.getItemData(session).getDefinition();
            String renderedId = definition.getIdentifier();
            // Keep other extensions' selected item definitions and native torch /
            // shield rendering. Classify what was actually translated for this player.
            boolean ownRenderer = renderedId.startsWith("nativeoffhand:")
                || (renderedId.startsWith("minecraft:")
                    && (ItemCatalog.usesNativeBlock(javaId)
                        || definition.getVersion() == org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion.DATA_DRIVEN));
            if (ownRenderer && !name.equals("shield") && !name.equals("torch") && !name.endsWith("_torch")) {
                mode = foodNames.contains(name) ? 3 : ItemCatalog.isHandheld(name) ? 2 : 1;
            }
        }
        Integer previous = MODES.put(session, mode);
        if (previous == null || previous != mode) {
            final int value = mode;
            session.getPlayerEntity().updatePropertiesBatched(updater -> updater.update(property, value), true);
        }
    }

    static void disconnect(GeyserSession session) { MODES.remove(session); }
    static void clear() { MODES.clear(); }
}
