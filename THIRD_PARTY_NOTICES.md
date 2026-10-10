# Third-party notices

The extension is MIT-licensed. Inventory validation and interaction conventions were studied in [GeyserMC/Geyser](https://github.com/GeyserMC/Geyser/blob/e215fa5cf1ffc3874c4d46c579d25800b20f17e4/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockInventoryTransactionTranslator.java), copyright (c) 2019-2022 GeyserMC. Geyser is distributed under the MIT license. The rewrite delegates ordinary inventory operations to Geyser and calls its block-reach helper instead of bundling the old full translator fork.

The user-supplied DoubleHands 5.6.0 add-on, credited to Areed Elahi in its manifest, was inspected as a behavioral reference. No DoubleHands source code, UI files or texture assets are redistributed. See [the inspection record](docs/doublehands-reference.md).

The 0.2.0 resource pack contains only its manifest and vanilla texture-path aliases. It bundles no Minecraft images, player models, attachables or animation definitions. Geyser's integrated pack is loaded separately by Geyser.

Historical files in `docs/previews` contain Blockbench screenshots and texture-free inspection projects from the previous experimental implementation. Minecraft imagery remains the property of Mojang. Bedrock bone hierarchy/pivots in those historical projects were taken from [Mojang/bedrock-samples](https://github.com/Mojang/bedrock-samples), copyright Mojang AB, under its Minecraft EULA notice. Those previews do not verify the Bedrock runtime renderer and are not used by the rewrite.
