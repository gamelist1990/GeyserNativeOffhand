package org.pexserver.nativeoffhand;

import com.google.gson.*;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.item.custom.GeyserCustomItemDefinition;
import org.geysermc.geyser.registry.BlockRegistries;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class ItemCatalog {
    private static final Set<String> FALLBACK_IDS = new HashSet<>();
    static boolean isFallback(String javaId) { return FALLBACK_IDS.contains(javaId); }
    private static String readableName(String name) {
        StringJoiner words = new StringJoiner(" ");
        for (String word : name.split("_")) words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
        return words.toString();
    }
    private static JsonElement read(Class<?> owner, String resource) throws IOException {
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing resource " + resource);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
    static int register(GeyserDefineCustomItemsEvent event) throws IOException {
        FALLBACK_IDS.clear();
        JsonObject mappings = read(GeyserImpl.class, "/mappings/items.json").getAsJsonObject();
        JsonObject icons = read(ItemCatalog.class, "/icons.json").getAsJsonObject();
        Set<String> dataDriven = new HashSet<>();
        for (JsonElement element : read(GeyserImpl.class, "/bedrock/runtime_item_states.26_50.json").getAsJsonArray()) {
            JsonObject state = element.getAsJsonObject();
            if (state.get("version").getAsInt() == 1) dataDriven.add(state.get("name").getAsString());
        }
        // A vanilla shield already supports offhand and its identifier selects
        // the native attachable, banner patterns and blocking animations.
        dataDriven.add("minecraft:shield");
        var blocks = BlockRegistries.BLOCKS.get().get(Collections.max(BlockRegistries.BLOCKS.get().keySet()));
        int count = 0;
        for (var entry : mappings.entrySet()) {
            String javaId = entry.getKey(), name = javaId.substring(javaId.indexOf(':') + 1);
            JsonObject mapping = entry.getValue().getAsJsonObject();
            String bedrockId = mapping.get("bedrock_identifier").getAsString();
            if (name.equals("air") || dataDriven.contains(bedrockId)) continue;
            Identifier javaItem = Identifier.of(javaId);
            var builder = new GeyserCustomItemDefinition.Builder(Identifier.of("nativeoffhand", name), javaItem);
            var options = CustomItemBedrockOptions.builder().allowOffhand(true)
                .displayHandheld(name.endsWith("_sword") || name.endsWith("_pickaxe")
                    || name.endsWith("_axe") || name.endsWith("_hoe") || name.endsWith("_shovel")
                    || name.equals("bow") || name.equals("crossbow") || name.equals("trident"));
            boolean block = mapping.has("firstBlockRuntimeId") && !mapping.get("firstBlockRuntimeId").isJsonNull();
            if (block) {
                // Use Geyser's actual block palette, including names that differ
                // between Java and Bedrock, rather than a list of allowed items.
                String placedBlock = blocks.getBedrockBlock(mapping.get("firstBlockRuntimeId").getAsInt())
                    .getState().getString("name");
                builder.geyserComponent(GeyserItemDataComponents.BLOCK_PLACER,
                    GeyserBlockPlacer.of(Identifier.of(placedBlock), true));
            } else {
                if (!icons.has(name)) throw new IOException("No vanilla icon alias for " + javaId);
                options.icon("nativeoffhand_" + name);
            }
            builder.priority(-1000000).predicate(context -> true)
                .displayName(readableName(name))
                .bedrockOptions(options);
            event.register(javaItem, builder.build());
            FALLBACK_IDS.add(javaId);
            count++;
        }
        return count;
    }
}
