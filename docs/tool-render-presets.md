# インベントリ表示中の道具の描画プリセット

0.2.4 の実機画像では、インベントリを開いたときの斧・道具の向きが通常の保持時と異なっていました。`hand_equipped` の指定だけでは解消していません。元の DoubleHands HUD はユーザーから良好との報告を受けています。

## Geyser のカスタムアイテム処理

通常のインベントリからオフハンドへ置けるようにする処理は、既に `GeyserDefineCustomItemsEvent`、`GeyserCustomItemDefinition.Builder` と `CustomItemBedrockOptions.allowOffhand(true)` を使っています。インベントリを表示している間だけ移動用の `nativeoffhand_ui:` 定義を選び、閉じると元のバニラ定義・ブロック状態へ戻します。

0.2.5 は、その移動用の道具に種類別の `components.render_offsets` 文字列プリセットを加えます。

| 道具 | プリセット |
| --- | --- |
| 剣・斧・ツルハシ・シャベル・クワ・メイス・ブラシ | `tools` |
| 弓・クロスボウ | `bow` |
| 釣り竿・ニンジン付きの棒・歪んだキノコ付きの棒 | `fishing_rods` |
| トライデント | `trident` |

登録 API v2 の公開オプションにはこのプリセットを指定する項目がないため、登録後に Geyser 内部の `SimpleItemDefinition.componentData` を補います。Geyser build 3080 の `CustomItemRegistryPopulator` も、防具の登録に同じ非 namespaced の `render_offsets` を使っています。元のデータは保存し、拡張を無効にすると復元します。Geyser の内部実装に依存する候補です。

食品・一般アイテム・ブロックには道具用プリセットを追加しません。元の NONE・LEGACY の定義と、保持用のモデル・プレイヤーの腕・アニメーションは変更しません。

## 確認できた範囲

実際の Paper/Geyser と bedrock-protocol による 69 項目の試験が通過しました。13 種類の道具に種類別プリセットが届くこと、6 種類の一般アイテム・ブロックに追加されないこと、移動・個数・Java 側の使用・元の HUD 同期を確認しています。

自動クライアントは Bedrock の画面を描画しません。プリセットが現行 Bedrock で有効か、道具の向き・大きさを改善するかは未確認です。右手に剣、左手に斧を持ったままインベントリを開閉し、背景の道具とプレビューの姿勢を実機で確認してください。ブロックの小さい表示はユーザーが許容した既存の制約として扱います。

## 参照

- [Geyser の登録処理（検証した commit）](https://github.com/GeyserMC/Geyser/blob/e215fa5cf1ffc3874c4d46c579d25800b20f17e4/core/src/main/java/org/geysermc/geyser/registry/populator/CustomItemRegistryPopulator.java)
- [古い Bedrock のプリセット一覧](https://gist.github.com/toka7290/c457abb9454289b1ceef3fecb009e58f): 古いバージョンの記録のため、現在の描画を保証しません。
- [Microsoft の minecraft:render_offsets 説明](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/itemreference/examples/itemcomponents/minecraft_render_offsets?view=minecraft-bedrock-stable): 公開の数値変換コンポーネントは非推奨で、現行版では使われないとされています。本候補はその数値変換 API を使わず、Geyser が送る内部プリセットを検証します。
