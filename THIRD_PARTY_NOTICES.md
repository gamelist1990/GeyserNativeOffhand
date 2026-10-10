# Third-party notices

The inventory translator retains the GeyserMC MIT copyright and license in its source header. The repository LICENSE covers this extension's original implementation.

`resource-pack/animations/nativeoffhand_vanilla_pose.animation.json` extends the small `animation.player.first_person.empty_hand` definition from [Mojang/bedrock-samples](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/player_firstperson.animation.json). The existing right-arm and item-pivot formulas are retained to preserve the vanilla behavior; the conditional, mirrored left-arm pose is added by this extension. The original sample is (c) Mojang AB, all rights reserved, and subject to the [sample repository's Minecraft EULA notice](https://github.com/Mojang/bedrock-samples/blob/main/LICENSE.md).

Food geometry and consumption animation formulas are authored for this extension. Cooldown's attachable mechanism was studied, but its third-party models and animations are not redistributed. Minecraft texture files are not bundled; attachables reference texture paths supplied by the client. Geyser's integrated pack is loaded separately by Geyser and is not bundled into this extension.
