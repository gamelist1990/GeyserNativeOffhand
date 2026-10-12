#!/usr/bin/env python3
"""Check the native renderer against the current Mojang resource_pack; no PNGs."""
import hashlib
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REPO = "Mojang/bedrock-samples"


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "NativeOffhand-source-check"}), timeout=20) as response:
        return response.read()


def parse(data):
    text = data.decode("utf-8-sig")
    return json.loads(re.sub(r'("(?:\\.|[^"\\])*?")|(/\*[\s\S]*?\*/|//[^\r\n]*)',
                            lambda match: match.group(1) or "", text))


def main():
    # Always resolve main; reuse only immutable inputs for that commit.
    commit = json.loads(fetch("https://api.github.com/repos/" + REPO + "/commits/main"))
    sha = commit["sha"]
    cache = ROOT / "build/mojang-source" / sha
    cache.mkdir(parents=True, exist_ok=True)
    inputs = {}
    for name in ["ui/ui_common.json", "ui/hud_screen.json", "blocks.json", "textures/terrain_texture.json", "textures/item_texture.json"]:
        path = cache / name
        if not path.exists():
            data = fetch("https://raw.githubusercontent.com/" + REPO + "/" + sha + "/resource_pack/" + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        inputs[name] = path.read_bytes()
    common = parse(inputs["ui/ui_common.json"])
    hud = parse(inputs["ui/hud_screen.json"])
    if common["item_renderer"]["renderer"] != "inventory_item_renderer":
        raise ValueError("Mojang's native item renderer changed")
    if not hud["hotbar_hud_item_icon@common.item_renderer"]["property_bag"]["force_update"]:
        raise ValueError("Mojang's hotbar renderer update setting changed")
    blocks = parse(inputs["blocks.json"])
    terrain = parse(inputs["textures/terrain_texture.json"])["texture_data"]
    for name in ["lantern", "soul_lantern", "soul_torch"]:
        block = blocks[name]
        key = block.get("carried_textures", block.get("textures"))
        if not isinstance(terrain[key]["textures"], str):
            raise ValueError("Mojang's " + name + " texture definition changed")
    item_textures = parse(inputs["textures/item_texture.json"])["texture_data"]
    # Placeable items whose vanilla inventory icon is a sprite rather than
    # the placed block. Resolve paths from the same current Mojang commit.
    carried = {"redstone": "redstone_dust"}
    icons_path = ROOT / "src/main/resources/icons.json"
    atlas_path = ROOT / "resource-pack/textures/item_texture.json"
    icons = json.loads(icons_path.read_text())
    atlas = json.loads(atlas_path.read_text())
    for java_name, vanilla_key in carried.items():
        texture = item_textures[vanilla_key]["textures"]
        if not isinstance(texture, str):
            raise ValueError("Expected a vanilla carried item texture: " + java_name)
        icons[java_name] = texture
        atlas["texture_data"]["nativeoffhand_ui_" + java_name] = {"textures": texture}
    icons_path.write_text(json.dumps(icons, indent=2) + "\n")
    atlas_path.write_text(json.dumps(atlas, indent=2) + "\n")
    output = {
        "repository": "https://github.com/" + REPO, "ref": "main", "commit": sha,
        "commitDate": commit["commit"]["committer"]["date"],
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in inputs.items()},
        "renderer": "inventory_item_renderer", "forceUpdate": True,
        "bundledItemPngs": 0,
        "note": "Native client assets are rendered through AUX; source texture paths do not become HUD image controls. Mojang's resource_pack is not a numerical ID registry."
    }
    path = ROOT / "src/main/resources/mojang-hud-source.json"
    text = json.dumps(output, indent=2) + "\n"
    if not path.exists() or path.read_text() != text:
        path.write_text(text)
    print("Mojang main checked:", sha, "| native renderer; no item PNG downloads")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit("Mojang HUD source check failed: " + str(error))
