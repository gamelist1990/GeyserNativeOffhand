#!/usr/bin/env python3
"""Generate native AUX literals for flat carried blocks; no texture images."""
import importlib.util
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("mojang_source", ROOT / "scripts/check-mojang-hud-source.py")
mojang = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mojang)
source = json.loads((ROOT / "src/main/resources/mojang-hud-source.json").read_text())
cache = ROOT / "build/mojang-source" / source["commit"]
blocks = mojang.parse((cache / "blocks.json").read_bytes())
terrain = mojang.parse((cache / "textures/terrain_texture.json").read_bytes())["texture_data"]
with zipfile.ZipFile(os.environ["GEYSER_JAR"]) as jar:
    states = json.loads(jar.read("bedrock/runtime_item_states.26_50.json"))
native_ids = {item["name"]: item["id"] for item in states}
literals = {}
for name, block in sorted(blocks.items()):
    if not isinstance(block, dict):
        continue
    identifier = name if ":" in name else "minecraft:" + name
    item_id = native_ids.get(identifier, 0)
    if item_id >= 0:
        continue
    key = block.get("carried_textures")
    torch = name == "torch" or name.endswith("_torch")
    if key is None and torch:
        key = block.get("textures")
    if not isinstance(key, str):
        continue
    path = terrain.get(key, {}).get("textures")
    if not isinstance(path, str) or not (path.startswith("textures/items/") or torch):
        continue
    # The path only selects flat carried items; it never becomes a HUD texture.
    literals[identifier] = item_id * 65536
for required in ["minecraft:lantern", "minecraft:soul_lantern", "minecraft:soul_torch"]:
    if required not in literals:
        raise SystemExit("Missing native AUX literal: " + required)

ui_path = ROOT / "resource-pack/ui/doublehands_hud.json"
ui = json.loads(ui_path.read_text())
# Preserve the proven base frame/layout; retire comparison-only controls.
ui["offhand_slot_panel"]["controls"] = [
    entry for entry in ui["offhand_slot_panel"]["controls"]
    if not next(iter(entry)).startswith("diagnostic_")
]
content = ui["offhand_slot_content"]["controls"]
content[:] = [entry for entry in content if next(iter(entry)) != "native_aux_icons"]
controls = []
for identifier, aux in literals.items():
    name = identifier.split(":", 1)[1]
    controls.append({name: {
        "type": "custom", "renderer": "inventory_item_renderer",
        "layer": 3, "size": [16, 16],
        "anchor_from": "center", "anchor_to": "center", "offset": [0, 0],
        "property_bag": {"#item_id_aux": aux, "force_update": True},
        "bindings": [
            {"binding_type": "global", "binding_name": "#hud_title_text_string",
             "binding_name_override": "#native_aux"},
            {"binding_type": "view", "source_property_name": "(#native_aux = '" + str(aux) + "')",
             "target_property_name": "#visible"}
        ]
    }})
content.insert(2, {"native_aux_icons": {"type": "panel", "size": ["100%", "100%"],
                                      "layer": 0, "controls": controls}})
conditions = " or ".join("(#nativeoffhand_aux_string = '" + str(aux) + "')" for aux in literals.values())
# Restore diagnostic B's proven numeric conversion without altering its AUX.
# A owns only exact generated native values; B remains the fallback elsewhere.
content[1]["item_renderer"]["bindings"][1]["source_property_name"] = "(0 + #nativeoffhand_aux_string)"
content[1]["item_renderer"]["bindings"][-1]["source_property_name"] = (
    "(not ((#nativeoffhand_aux_string = '') or (#nativeoffhand_aux_string = '0')) and not (" + conditions + "))"
)
ui_path.write_text(json.dumps(ui, ensure_ascii=False, indent=2) + "\n")
audit = {
    "sourceCommit": source["commit"], "geyserItemStates": "bedrock/runtime_item_states.26_50.json",
    "count": len(literals), "nativeAuxLiterals": literals,
    "mechanism": "Native inventory_item_renderer with integer property_bag AUX; title equality selects the matching control.",
    "bundledItemPngs": 0,
    "dynamicFallback": "Diagnostic B: (0 + #nativeoffhand_aux_string), using unshifted native runtime AUX. Mutually exclusive with A by exact title matches.",
    "limitations": ["Static carried-item controls match metadata zero. Other metadata keeps the original dynamic AUX renderer.",
                    "Retail 0.2.21 confirmed lantern-type icons and soul torch in the normal slot; other generated items and restored apple fallback still require individual checks."]
}
(ROOT / "docs/research/native-hud-aux-literals.json").write_text(json.dumps(audit, indent=2) + "\n")
print("Generated", len(literals), "native AUX literal controls; no item PNGs")
