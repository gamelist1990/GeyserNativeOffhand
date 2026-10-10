package org.pexserver.nativeoffhand;

import com.google.gson.*;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.*;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.item.custom.GeyserCustomItemDefinition;
import org.geysermc.geyser.registry.BlockRegistries;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UI aliases never replace a vanilla item in the game view. */
final class NativeItemCatalog {
    private static final Set<String> ALIASED = new HashSet<>();
    private static final Map<String, String> RENDER_PRESETS = new HashMap<>();

    static String renderPreset(String identifier) { return RENDER_PRESETS.get(identifier); }

    static boolean hasAlias(String javaId) { return ALIASED.contains(javaId); }

    /** Built-in transform groups used by Bedrock's item component registry. */
    private static String renderPresetForVanilla(String bedrockId) {
        String name = bedrockId.substring(bedrockId.indexOf(':') + 1);
        if (name.endsWith("_sword") || name.endsWith("_axe") || name.endsWith("_pickaxe")
                || name.endsWith("_shovel") || name.endsWith("_hoe")
                || name.equals("mace") || name.equals("brush")) return "tools";
        return switch (name) {
            case "bow", "crossbow" -> "bow";
            case "fishing_rod", "carrot_on_a_stick", "warped_fungus_on_a_stick" -> "fishing_rods";
            case "trident" -> "trident";
            default -> null;
        };
    }

    private static JsonElement resource(Class<?> owner, String path) throws IOException {
        try (var stream = owner.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    static int register(GeyserDefineCustomItemsEvent event) throws IOException {
        ALIASED.clear();
        RENDER_PRESETS.clear();
        var mappings = resource(GeyserImpl.class, "/mappings/items.json").getAsJsonObject();
        var icons = resource(NativeItemCatalog.class, "/icons.json").getAsJsonObject();
        var nativeComponents = new HashSet<String>();
        for (var entry : resource(GeyserImpl.class, "/bedrock/runtime_item_states.26_50.json").getAsJsonArray()) {
            var item = entry.getAsJsonObject();
            if (item.get("version").getAsInt() == 1) nativeComponents.add(item.get("name").getAsString());
        }
        nativeComponents.add("minecraft:shield");
        var palette = BlockRegistries.BLOCKS.get();
        var blocks = palette.get(Collections.max(palette.keySet()));
        for (var entry : mappings.entrySet()) {
            String javaId = entry.getKey();
            String name = javaId.substring(javaId.indexOf(':') + 1);
            var mapping = entry.getValue().getAsJsonObject();
            if (name.equals("air") || nativeComponents.contains(mapping.get("bedrock_identifier").getAsString())) continue;
            var id = Identifier.of(javaId);
            var alias = new GeyserCustomItemDefinition.Builder(Identifier.of("nativeoffhand_ui", name), id);
            String preset = renderPresetForVanilla(mapping.get("bedrock_identifier").getAsString());
            var options = CustomItemBedrockOptions.builder().allowOffhand(true)
                .displayHandheld(preset != null);
            if (preset != null) RENDER_PRESETS.put("nativeoffhand_ui:" + name, preset);
            if (mapping.has("firstBlockRuntimeId") && !mapping.get("firstBlockRuntimeId").isJsonNull()) {
                var block = blocks.getBedrockBlock(mapping.get("firstBlockRuntimeId").getAsInt());
                alias.geyserComponent(GeyserItemDataComponents.BLOCK_PLACER,
                    GeyserBlockPlacer.of(Identifier.of(block.getState().getString("name")), true));
            } else {
                if (!icons.has(name)) throw new IOException("Missing vanilla icon for " + javaId);
                options.icon("nativeoffhand_ui_" + name);
            }
            alias.priority(-1000000).predicate(context -> NativeInventoryView.aliasesEnabled())
                .displayName(name).bedrockOptions(options);
            event.register(id, alias.build());
            ALIASED.add(javaId);
        }
        return ALIASED.size();
    }
}
