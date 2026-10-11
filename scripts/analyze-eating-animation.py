#!/usr/bin/env python3
"""Audit an externally supplied Eating Animation ZIP without copying its assets."""
import argparse, hashlib, json, math, zipfile
from pathlib import Path

def audit(path):
    raw = path.read_bytes()
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        prefix = next(n[:-len("manifest.json")] for n in names if n.endswith("/manifest.json"))
        def read(name):
            return json.loads(z.read(prefix + name))
        manifest = read("manifest.json")
        attachables = [n for n in names if n.startswith(prefix + "attachables/") and n.endswith(".json")]
        ids, conditions = [], set()
        for name in attachables:
            desc = json.loads(z.read(name))["minecraft:attachable"]["description"]
            ids.append(desc["identifier"])
            conditions.add(json.dumps(desc["scripts"]["animate"]))
        expected_animation_hash = "baa3e680d43d45c25163d8a34c481940b4fd646e109b875ebce9fed8ad5cf928"
        actual_animation_hash = hashlib.sha256(z.read(prefix + "animations/consumable.animation.json")).hexdigest()
        if actual_animation_hash != expected_animation_hash:
            raise ValueError("Motion samples support only the investigated 1.1.5 animation definition")
        controller = read("animation_controllers/consumable.animation_controller.json")
        animations = read("animations/consumable.animation.json")
        render = read("render_controllers/consumable.render_controllers.json")
        sources = {n[len(prefix):]: hashlib.sha256(z.read(n)).hexdigest()
                   for n in names if n.endswith((".json", ".molang")) and not n.endswith("manifest.json")}
        samples = []
        for t in [0, .125, .25, .3125, .375, .4375, .5, 1]:
            ramp = min(t / .25, 1)
            samples.append({"seconds": t, "rotation_degrees": [32*ramp, 0, 96*ramp],
                            "position_model_units": [-12*ramp, 3*ramp, 2*math.sin(math.radians(t*1440+.5)) if t>.25 else 0]})
        return {"archive_sha256": hashlib.sha256(raw).hexdigest(), "archive_bytes": len(raw),
                "files": len(names), "expanded_bytes": sum(z.getinfo(n).file_size for n in names),
                "pack": manifest["header"], "png_count": sum(n.endswith(".png") for n in names),
                "attachables": sorted(ids), "animate_patterns": sorted(conditions),
                "controller": controller, "animations": animations, "render_controller": render,
                "source_sha256": sources, "motion_samples": samples,
                "sample_scope": "Local child-bone transforms derived from this pack; not screen coordinates or retail measurements."}

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("zip", type=Path)
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    result = audit(args.zip)
    # Keep original expression-bearing definitions private; the public audit contains summaries and hashes.
    for key in ["controller", "animations", "render_controller", "pack"]:
        result.pop(key)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output)
    else:
        print(output, end="")
