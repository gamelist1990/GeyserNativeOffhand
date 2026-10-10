# インベントリ用の道具を実際の手へ結び付ける

0.2.5 の描画プリセットと 0.2.6 のバニラ装備パケットは、ユーザーの実機では左手の斧の向きを改善しませんでした。0.2.7-rewrite.8 / RP 0.2.7 は、通常のインベントリに使うカスタム定義へ左右別の attachable を追加した候補です。実機では表示が消える、または位置・大きさがおかしいとの報告を受け、修正に失敗しました。

## 変更する範囲

`scripts/generate-ui-tools.py` が、剣・斧・ツルハシ・シャベル・クワの 7 素材、計 35 種類を生成します。識別子は `nativeoffhand_ui:` に限定します。これらは通常のプレイヤーインベントリを開いた間だけ選ぶ定義です。元の `minecraft:` のアイテムやプレイヤーの定義を上書きしません。松明・食品・ブロック・盾にはこの attachable を使いません。

各 attachable はバニラのアイテム画像パスを参照します。素材の画像をコピーせず、エンチャントのグリントも参照します。閉じる際は従来どおり元のバニラ定義へ戻し、表示を更新します。0.2.6 のインベントリ表示中に装備パケットを重ねる補正は削除しました。

## 実際の手と描画形式

ボーンの binding は `q.item_slot_to_bone_name(c.item_slot)` です。render controller は `c.item_slot == 'off_hand'` と `c.is_first_person` で、一人称・三人称それぞれの左右別メッシュを選びます。アイテムの型に道具のフラグを付けるだけではなく、スロットに対応するモデルを指定します。

左手用では位置、回転とローカル支点を反転します。支点を右手と共用すると、反転した画像が違う位置へずれるため、支点の X も反転します。画像の横方向と厚み方向を一緒に反転して、メッシュの向きが裏返らないようにします。腕全体の姿勢は変更せず、この attachable のボーンだけを動かします。

結び付けたボーンの原点に合わせた 24 単位の補正を含みます。[Bedrock Wiki の attachable 説明](https://wiki.bedrock.dev/items/attachables)と、バニラの bound spyglass / bow の保持位置を照合しました。クライアント内のスロット binding と画面上の位置は実機での確認が必要です。

## Blockbench で確認したこと

Blockbench Web 5.2.2 の Bedrock 形式に、パックに含める texture mesh を読み込みました。鉄の斧とダイヤモンドの剣について、一人称・三人称の左右を並べた 4 つのプレビューを保存しました。実際の画像を貼り、左右で画像の支点も反転していることと、片側が裏返って消えないことを確認しています。

- [斧・一人称](previews/ui-tools/iron_axe-first-paired.png)
- [斧・三人称](previews/ui-tools/iron_axe-third-paired.png)
- [剣・一人称](previews/ui-tools/diamond_sword-first-paired.png)
- [剣・三人称](previews/ui-tools/diamond_sword-third-paired.png)
- [バージョン・実モデルの SHA-256・適用した値](previews/ui-tools/verification.json)

プレビューではバニラのプレイヤーのアイテム支点を使い、スロット binding とその原点をプレビュー用の複製で再現します。パック側のモデルから読み込み、プレビューだけで座標を調整して修正成功と扱うことはしません。Blockbench は Bedrock 本体のインベントリと画面描画を実行しないため、プレビューの左右対応は実機での位置・向きの一致を保証しません。

## 検証

独立した Paper + Geyser にパックを同梱した JAR を配置します。自動試験はオフハンドへの移動・分割・交換・設置・食品の消費・HUD を確認します。装備パケット補正専用だった 2 項目を除いた 68 項目です。別に 35 個の attachable の名前・モデル参照・binding・左右の支点を確認し、元の DoubleHands HUD 1,679 ファイルを入力と照合します。

実機では RP 0.2.7 へ再接続し、右手に剣・左手に斧を持ってインベントリを開閉します。左手の向き・大きさと、出し入れを確認します。閉じた通常画面の松明・ブロック・食品の表示も確認してください。実機の合否は [結果 JSON](../tests/results/2026-10-10-ui-tool-attachables.json) に別に記録します。

## 0.2.8 の比較候補

0.2.7 の実機では、インベントリを開いた時に背景の左手の斧が消える／ずれるとの報告を受けました。0.2.8-rewrite.9 / RP 0.2.8 は、左手の texture mesh の倍率を全て正の 1 にします。回転と支点を対応する値へ変え、左右別の binding とアニメーションは維持します。負の倍率が実機での症状の原因かは未確定です。

Blockbench に読み込んだ斧・剣の一人称・三人称、4 つの左手メッシュで変更前後のワールド座標を比較しました。最大差は約 7.2×10⁻¹⁵ 単位で、Blockbench 上では同じ形です。これは Bedrock 本体のメッシュ生成を実行した結果ではありません。実機で改善するかを比較するための候補です。

- [正の倍率の斧・一人称](previews/ui-tools-positive/iron_axe-first-paired.png)
- [正の倍率の斧・三人称](previews/ui-tools-positive/iron_axe-third-paired.png)
- [正の倍率の剣・一人称](previews/ui-tools-positive/diamond_sword-first-paired.png)
- [正の倍率の剣・三人称](previews/ui-tools-positive/diamond_sword-third-paired.png)
- [実モデルの SHA-256](previews/ui-tools-positive/verification.json) / [頂点の比較結果](previews/ui-tools-positive/equivalence.json)
- [サーバー試験と実機の記録](../tests/results/2026-10-10-ui-tool-positive-scale.json)
