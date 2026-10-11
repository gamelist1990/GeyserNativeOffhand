# HUD 画像の軽量化調査

2026-10-11、RP 0.2.13 を調査しました。PNG を同梱せず、クライアントの既存アイテム描画を使う経路があります。現在の画像パス型 HUD をそのままにして全画像を削除すると表示は壊れます。`inventory_item_renderer` へオフハンドのアイテム情報を渡す方式への変更が必要です。Geyser と実機での描画は今回まだ検証していません。

## RP 0.2.13 の容量の実測

| 構成 | パックのバイト数 | 状態 |
| --- | ---: | --- |
| 調査時の RP 0.2.13 | 9,560,402 | 変更前の実測 |
| 参照されていない 9 枚を除外 | 9,546,179 | 容量試算のみ |
| バニラ PNG の候補 551 枚へ置換して元画像を除外 | 8,557,620 | 容量試算のみ。状態・色・見た目は未検証 |
| `iso_renders` を全て除外 | 50,832 | 容量試算のみ。新しい UI・同期処理の変更分が別途必要 |

ZIP をメモリ上で再構成して測定しました。稼働中のパックや画像は変更していません。1,674 枚は全て 256×256 RGBA、元画像の合計は 12,406,033 バイト、ZIP 内の圧縮データだけで 9,227,278 バイトです。現在の HUD の画像コントロールは 16×16 UI 単位です。

既存アイコンマップと公式 `item_texture.json` の単一画像パスを調べ、バニラ配布ツリーに PNG が存在するか照合しました。551 枚は置換候補であり、状態・色も正しく描画できるという確認ではありません。ポーション、染色、時計、旗・盾などは画像パスだけで全状態を再現できません。

[実測値と制約](research/hud-texture-audit.json)。

## バニラの画像パス

りんごは `textures/items/apple`、パンは `textures/items/bread`、鉄の斧は `textures/items/iron_axe` を参照できます。その PNG を本拡張へコピーする必要はありません。クライアントの有効なリソースパックから、そのパスの画像を読み込みます。サーバー側ではアイテムとパスの対応を選びます。

調べた [Mojang のバニラ素材](https://github.com/Mojang/bedrock-samples/tree/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/textures)には `iso_renders` のパスはありません。このフォルダーは DoubleHands が同梱する画像群です。石の立体アイコンは、ブロックの表面画像を表示するだけでは再現できません。

## 画像を持たずに描画する経路

[Microsoft の UI 定義](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/jsonuireference/examples/jsonuicomponents/ui_element?view=minecraft-bedrock-stable)は custom renderer と bindings を説明しています。[Mojang の共通アイテムコントロール](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/ui/ui_common.json#L3937)は `inventory_item_renderer` と `#item_renderer_data` を使います。[通常のホットバー](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/ui/hud_screen.json#L641)もこの共通コントロールを参照します。既存のクライアントの画像・モデルを使ってアイテムを描く経路です。

正式なアイテムデータはインベントリの collection から渡されます。公式のインベントリ定義には `offhand_items` がありますが、調べた HUD 定義は `hotbar_items` を使い、`offhand_items` を参照しません。HUD で同じ collection を指定すれば利用できるかは、ソースだけでは断定できません。

別の入口は数値の `#item_id_aux` です。[Mojang の定義](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/ui/inventory_screen.json#L1474)に固定値 3801088 の例があります。[Chest-UI 作者の実装](https://github.com/Herobrine643928/Chest-UI/blob/115d95c8239a0ee2f578a9a7710699a8f6627f26/RP/ui/chest_server_form.json#L294)はフォームで受け取った数値文字列を演算して `#item_id_aux` に渡しています。この実装を根拠に、Geyser が title に数値を送り、HUD がアイテムを描く構成が有望です。AUX と完全なアイテムデータは同じではなく、カスタムアイテムや色・模様などは別に確認します。

本リポジトリの 0.2.3 も AUX renderer を使いましたが、実機で通常の HUD が消えました。ルートの注入など複数の変更があったため、その報告だけで renderer 自体が使えないとは結論しません。再検証は、表示できている DoubleHands のルート追加・枠・個数を基準に、画像コントロールと受け渡すデータを段階的に替えます。

## 最小の確認手順

[未組み込みの JSON 草案](research/native-hud-icon.json)を置きました。稼働パックの `_ui_defs.json` はこの草案を読み込みません。

1. 固定 AUX の `static_baseline` で、通常の HUD を維持しながら作業台を表示できるか確認します。
2. `dynamic_aux_icon` に数値 title を送り、文字列の数値演算と動的切り替えを確認します。この演算式は草案であり、実機確認済みの式として扱いません。
3. Geyser の保持アイテムから AUX を作り、石・りんご・斧、個数、空状態、再接続を確認します。UI 専用 alias は使わず、対象クライアントのバージョンで ID の対応を照合します。
4. ブロック状態、ポーション、染色、旗・盾、エンチャント、他のカスタムパックを確認し、AUX だけで失われる情報を調べます。
5. PNG が不要なことを実機で確認してから画像群と旧 TextureMap を取り除き、配信サイズを測り直します。

JSON の構文、パスの存在、容量は確認しています。Bedrock renderer の実行、Geyser の AUX と UI の対応、数値 title の評価、他パックへの追従は実機検証待ちです。現在の 0.2.13 の保持座標・HUD・インベントリ処理は変更していません。

## ユーザー指示を受けた 0.2.14 の実装

調査後、ユーザーから画像群の全削除と枠 PNG の維持を指示されました。旧 TextureMap と PNG 群を削除し、表示できていた DoubleHands のルート・配置・枠・個数を保って native AUX renderer へ切り替えました。実装パックは **50,834 バイト**です。[変更内容と確認項目](offhand-hud.md)。

先の草案は引き続き参照資料として置き、稼働 UI からは読み込みません。実際の UI は `resource-pack/ui/doublehands_hud.json` にあります。今回の title の数値変換と、クライアントの既存画像・モデルの描画は実機確認待ちです。0.2.13 の道具モデルと座標は維持しています。

0.2.14 の実機結果はアイコン非表示でした。0.2.15 は固定 AUX・数値変換・実際の hotbar collection を比較する診断版です。[診断の構成](offhand-hud.md)を参照してください。画像の全削除は維持しています。

0.2.16 は標準 HUD のレイヤーと更新設定へ合わせた後、実機で石と全ての診断アイコンが表示されました。0.2.17 は診断表示を削除した版で、パックは **50,847 バイト**、同梱 PNG は枠 1 枚です。描画対象のアイコン PNG を持たずにクライアント側の標準 renderer を使う経路が、石について実機で確認できました。


## 0.2.18 の撤回と標準 renderer の比較

画像パス方式の0.2.18は欠落テクスチャの報告で取り下げ、0.2.17へ戻しました。0.2.19は標準AUX方式で符号変換とdata-drivenのローカル番号を補正しましたが、ランタン・魂の松明は実機で非表示でした。0.2.20は同じrendererへ固定値・2通りのカスタム番号・完全なスタックのcollectionを渡す診断版です。[経緯と現在の確認事項](native-hud-aux-repair.md)。画像PNGは枠1枚の構成を維持しています。


0.2.20の実機でAに魂の松明が表示されると確認されました。0.2.21は同じ整数AUXの標準rendererを枠内へ移し、Mojang mainの携帯用定義とGeyserのIDから65種類の固定AUXを生成します。比較表示を削除し、PNGは枠1枚を維持します。最終パックは53,056バイトです。枠内での最終表示と他の種類の描画は確認待ちです。
