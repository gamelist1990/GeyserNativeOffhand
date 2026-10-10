# Bedrock の補正を Blockbench で確認した結果

Web Blockbench 5.2.2 の **Bedrock Entity** プロジェクトへ、Mojang のプレイヤーのボーン階層・ピボットと、本パックの実際のアニメーションを読み込みました。Ubuntu の独立した Chrome を Remote Desktop Commander MCP と Chrome DevTools で操作しています。

## 道具

旧版の `leftitem` Y 軸 180° 回転では、実機画像の剣・斧がまだ内側へ向いていました。パック 0.1.12 / attachable.10 はそれを撤去し、handheld タグ付きの非 VR の左腕へ回転と移動を加えます。基準の眼の位置 `[0, 26, 0]` から握り位置への軸の周りに 75° 回す方式です。

| 対象 | 補正前のボーンを使う代用形状 | 補正後のボーンを使う代用形状 | Blockbench 操作画面 |
| --- | --- | --- | --- |
| 鉄の斧 | [前](iron_axe-before-diagnostic.png) | [後](iron_axe-after-diagnostic.png) | [Bedrock プロジェクト](iron_axe-bedrock-blockbench.png) |
| ダイヤの剣 | [前](diamond_sword-before-diagnostic.png) | [後](diamond_sword-after-diagnostic.png) | [Bedrock プロジェクト](diamond_sword-bedrock-blockbench.png) |

Blockbench 座標で左手の握り位置は、前が `[11.435659273435, 16.851945232846, 16.223805403633]`、後が `[11.435659273503, 16.851945232861, 16.223805403658]`。差は 1e-8 未満です。右腕・右手、handheld タグを持たない通常品、食品・ブロック・松明・盾、VR 条件では補正前後の値が一致しました。

計算は `R_new = R_view_axis(-75°) × R_old`、位置の補償は `(R_old - R_new) × item_relative_pivot` です。相対ピボットには実際の `get_default_bone_pivot` の差を使います。軸、クォータニオン、係数、評価したボーンの値、ソースのハッシュは [verification.json](verification.json) に保存しています。

**道具の画像はボーン補正を見比べる代用形状です。** Bedrock の内蔵 `hand_equipped` アイテム描画は Blockbench に公開されていません。代用形状は既知の柄のテクセルを握りのピボットに置いた半サイズの薄い cube で、基準の腕回転を打ち消すローカル姿勢を使います。Minecraft の内蔵描画を復元したモデルではなく、ゲーム内の大きさ・位置・最終的な刃の向きの検証済み画像ではありません。補正の実機確認には同じパックを読み込んだ Bedrock を使います。

## 食品

りんごには、本パックの実際の `geometry.nativeoffhand.food.offhand` と保持・食事アニメーションを読み込み、一人称条件を 1 に指定して評価しました。Java の display 定義は使っていません。

| 状態 | 実際のモデルを描画したプレビュー |
| --- | --- |
| attachable.7 の保持位置 | [補正前](apple-before-model-space.png) |
| attachable.8 以降の保持位置 | [補正後](apple-idle-model-space.png) |
| 食事開始から 0.3 秒 | [食事](apple-eat-model-space.png) |
| 操作画面 | [Bedrock の食品モデル](apple-bedrock-blockbench.png) |

保持位置はモデル空間で横へ 3.5、上へ 2.0 移ります。食事中は実際の式で持つモデルが動きます。これもモデル空間のプレビューで、Bedrock 本体の attachment・カメラ・render controller を完全には再現していません。

## 編集用プロジェクト

[斧](iron_axe-bedrock-texture-free.bbmodel)、[剣](diamond_sword-bedrock-texture-free.bbmodel)、[りんご](apple-bedrock-texture-free.bbmodel) を Blockbench で開けます。Minecraft の画像を再配布しないため、テクスチャは含みません。自分のバニラのテクスチャを追加し、cube を選んで割り当てると画像付きで確認できます。

元の Molang アニメーションに加え、今回の条件を評価した `preview.before.resolved` / `preview.after.resolved`（食品は `before` / `idle` / `eat`）を保存しています。この固定プレビューは Blockbench の追加設定なしで姿勢を確認するためのスナップショットです。元の動的アニメーションを再評価する場合は、handheld と vanilla_offhand_pose のタグを有効、VR を無効、食品は一人称を有効にしてください。今回は MCP 経由で Molang の評価コンテキストへ条件を指定しました。これらの条件設定はゲームの実装ではありません。

プレイヤーのピボットの出典は [Mojang humanoid.custom.geo.json](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/humanoid.custom.geo.json)。バニラの一人称式は [player_firstperson.animation.json](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/player_firstperson.animation.json)。出典・配布条件は [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md) を参照してください。
