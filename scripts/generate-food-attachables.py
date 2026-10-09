#!/usr/bin/env python3
"""Generate vanilla-texture food attachables from the tested Geyser catalog."""
from pathlib import Path
import json
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
with zipfile.ZipFile(sys.argv[1]) as jar:
    components = json.loads(jar.read("mappings/item_data_components.json"))
    mappings = json.loads(jar.read("mappings/items.json"))
    versions = {item["name"]: item["version"] for item in json.loads(jar.read("bedrock/runtime_item_states.26_50.json"))}
textures = json.loads((root / "src/main/resources/food-textures.json").read_text())
target = root / "resource-pack/attachables/nativeoffhand_food"
target.mkdir(parents=True, exist_ok=True)
for old in target.glob("*.json"):
    old.unlink()
count = 0
for item in components:
    if not {"minecraft:food", "minecraft:consumable"} <= item["components"].keys():
        continue
    java_id = item["key"]
    name = java_id.split(":")[1]
    if name not in textures:
        raise ValueError("Missing vanilla food texture: " + java_id)
    bedrock_id = mappings[java_id]["bedrock_identifier"]
    identifier = bedrock_id if versions[bedrock_id] == 1 else "nativeoffhand:" + name
    description = {
        "identifier": identifier,
        "item": {identifier: "query.owner_identifier == 'minecraft:player'"},
        "materials": {"default": "entity_alphatest", "enchanted": "entity_alphatest_glint"},
        "textures": {"default": textures[name], "enchanted": "textures/misc/enchanted_item_glint"},
        "geometry": {"default": "geometry.nativeoffhand.food"},
        "animations": {
            "consume_controller": "controller.animation.nativeoffhand.food",
            "wield": "animation.nativeoffhand.food.wield",
            "eat": "animation.nativeoffhand.food.eat",
            "drink": "animation.nativeoffhand.food.drink"
        },
        "scripts": {
            "pre_animation": ["variable.noh_left = context.item_slot == 'off_hand';", "variable.noh_mode = variable.noh_left ? query.property('nativeoffhand:consume_mode') : 0;"],
            "animate": ["consume_controller"]
        },
        "render_controllers": ["controller.render.nativeoffhand.food"]
    }
    (target / (name + ".json")).write_text(json.dumps({"format_version": "1.10.0", "minecraft:attachable": {"description": description}}, indent=2) + "\n")
    count += 1
print("Generated", count, "offhand food attachables using vanilla texture paths.")
