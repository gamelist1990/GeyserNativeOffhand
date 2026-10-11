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

## 0.2.9: 盾の左手座標を基準にする

0.2.8 の確認を依頼した後、ユーザーはインベントリ背景の左手が見えない画像を提示し、盾の座標に加算して少し持ち上げる方法を提案しました。画像には RP の番号が写っていません。負の倍率を外しただけで修正できたという報告はありません。

公式の [盾の attachable](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/attachables/shield.entity.json) は、一人称の左右で別の保持座標を使います。左手の位置は `[-13.5, -5.8, 5.1]`、回転は `[1, 176, -2.5]` です。0.2.9-rewrite.10 / RP 0.2.9 の左手・一人称はこの位置に Y +2 を加え、`[-13.5, -3.8, 5.1]` へ置きます。[盾の geometry](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/shield.geo.json) の支点と板の中心に合わせて 16×16 のアイテム画像を配置します。nativeoffhand_ui の道具 35 種類が対象で、元の盾は変更しません。右手と三人称は前の値を維持します。

以前の Blockbench プレビューは左腕を右腕の鏡像として置いており、バニラの一人称の左右別の姿勢を再現できていませんでした。公式の [player_firstperson.animation.json](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/player_firstperson.animation.json) の stationary empty_hand を確認し、新しいプレビューでは右腕だけにその変形を使い、左腕に鏡像の変形を作りません。スロット binding はプレビュー用の複製へ再現するため、これも実機描画の合格結果ではありません。静止中・arm height 1 のモデル確認であり、歩行・持ち替え時の腕の高さやクライアントの画面表示は実機で判定します。

- [斧・一人称](previews/ui-tools-shield/iron_axe-first-paired.png) / [剣・一人称](previews/ui-tools-shield/diamond_sword-first-paired.png)
- [斧・三人称](previews/ui-tools-shield/iron_axe-third-paired.png) / [剣・三人称](previews/ui-tools-shield/diamond_sword-third-paired.png)
- [実モデルの SHA-256](previews/ui-tools-shield/verification.json) / [盾の基準値と出典の SHA-256](previews/ui-tools-shield/shield-basis.json)
- [検証記録](../tests/results/2026-10-10-ui-tool-shield-basis.json)

## 0.2.9 の実機報告と次の切り分け

0.2.9 の再接続確認を依頼した後、ユーザーから「見えん」との報告を受けました。提示画像では、インベントリ内の斧のアイコンと右手の剣は表示されますが、背景の左手の斧はありません。盾の座標を使ったカスタムメッシュの修正は、実機で成功したとは判定できません。画像にはパックの番号がありません。

検証鯖専用の診断版 0.2.10 は、鉄の斧の UI 別名だけを、バニラの盾の geometry・texture・animation controller・render controller へ差し替えます。独自メッシュを使わない比較です。識別用の静的な `NOH 0.2.10 D` 表示と新しいパック UUID を使い、パック適用とモデル描画を分けて確認します。インベントリを閉じると元の斧へ戻り、スロットのアイコンと Java 側の種類・機能は斧のままです。これは一時的な診断であり、修正済みの公開版ではありません。

## 0.2.10 の診断結果: バニラの盾は表示できる

ユーザーから「盾が出てきた」と画像付きの報告を受けました。インベントリ背景の左手に盾の形が見え、スロットのアイコンは鉄の斧のままです。UI 別名を使った attachable の描画経路は動作しています。この比較では geometry、texture、animation、render controller をまとめて変えたため、以前の失敗の原因を一つの項目だけに特定したとは扱いません。

## 0.2.11: 左手の道具を立方体で描く候補

左手・一人称では `texture_mesh` を使いません。16×16 の各ピクセルを一辺 0.75 の立方体にし、6 面ともそのピクセルを UV で参照します。256 個の立方体の透明部分は `entity_alphatest` で除かれます。画像は元のバニラのパスを参照し、ゲームの画像ファイルは同梱しません。形は画像のアルファから決まるため、35 種類の道具に同じ geometry を使えます。

ボーンはバニラの盾の animation が対象にする `shield` とし、支点 `[1, 15.5, 3]`、スロット binding、左手の保持位置 `[-13.5, -5.8, 5.1]`、回転 `[1, 176, -2.5]` を診断版と同じ値にします。画像の範囲は X=-5..7、Y=30..42 で、盾の板の上端 Y=39 より 3 単位上です。画像の縦横比とピクセルの厚みを保ちます。これは候補の配置であり、実機の表示位置の合格結果ではありません。

左手・一人称は `Geometry.default` と、実機で表示できた `controller.render.item_default` / `controller.animation.shield.wield` を参照します。右手・三人称は以前のメッシュを維持し、geometry を条件式で選んでいた render controller を、各 geometry を固定で参照する controller と attachable 側の選択条件へ分けます。右手の保持位置・回転と、三人称の位置・回転の数値は維持します。ゲーム画面の元のバニラの保持定義、食品、ブロック、盾、DoubleHands の 1,679 ファイルは変更しません。

Blockbench 5.2.2 の Bedrock 形式へ実パックを読み込み、斧・剣の一人称と三人称をプレビューしました。ボーン binding をプレビュー用の複製で再現し、静止中のバニラの item pivot と first-person empty_hand を使います。実機のインベントリ背景のカメラや腕の高さの遷移は Blockbench では実行できません。

- [斧・一人称](previews/ui-tools-cubes/iron_axe-first-paired.png) / [剣・一人称](previews/ui-tools-cubes/diamond_sword-first-paired.png)
- [斧・三人称](previews/ui-tools-cubes/iron_axe-third-paired.png) / [剣・三人称](previews/ui-tools-cubes/diamond_sword-third-paired.png)
- [モデルと変形の確認記録](previews/ui-tools-cubes/verification.json)

## 0.2.11 の実機報告と 0.2.12 のバニラ比較

2026-10-11 に、インベントリ背景で斧が表示された画像と「めっちゃ惜しい」との報告を受けました。続けて、Blockbench で元のバニラの形を見て合わせるよう依頼されました。0.2.12 は、以前示された Java の剣の参考画像に合わせ、バニラ Java `item/handheld` の `firstperson_lefthand` を比較基準にします。基準の表示設定は rotation `[0,90,-25]`、translation `[1.13,3.2,1.13]`、scale `[0.68,0.68,0.68]` です。

Blockbench が実際に描画する元モデルの変換を取得し、Bedrock プレビューのカメラ座標へ変換します。Bedrock geometry の読み込みでは X 軸が反転するため、その反転も計算に含めます。表示できた `shield` のボーンと保持アニメーションは使い続け、固定のモデル中心 `[1,36,-0.125]` に子ボーンを追加します。元モデルの向きとの差をその子ボーンに適用し、各ピクセルの幅・高さ・厚みを 0.75 から 0.68 へ合わせます。補正は generator 内で行列から計算し、値を手で加算しません。

比較画像では元モデルと候補を同じ中心へ置き、同じテクスチャ、カメラ、FOV で描きます。さらに、読み込まれた斧・剣それぞれの 6,144 頂点を、元モデルの各ピクセルの対応する頂点へ照合しました。最大差は約 0.00000188 モデル単位です。これは浮動小数点による差です。モデル中心の変化は約 0.000000043 単位です。

| アイテム | 0.2.11 | バニラ基準 | 0.2.12 |
| --- | --- | --- | --- |
| 斧 | [前のモデル](previews/ui-tools-vanilla/iron_axe-before.png) | [元モデル](previews/ui-tools-vanilla/iron_axe-vanilla.png) | [候補](previews/ui-tools-vanilla/iron_axe-after.png) |
| 剣 | [前のモデル](previews/ui-tools-vanilla/diamond_sword-before.png) | [元モデル](previews/ui-tools-vanilla/diamond_sword-vanilla.png) | [候補](previews/ui-tools-vanilla/diamond_sword-after.png) |

[比較カメラ・変形・頂点の照合記録](previews/ui-tools-vanilla/verification.json)。比較画像は形・向き・厚みを確認するために中心を揃えたものです。Java の表示設定を基準にしており、Blockbench が Bedrock 本体の内部のアイテム描画やインベントリ背景を実行したという意味ではありません。実機での開閉時の位置・大きさ・向きは別に確認します。HUD と Java の移動・使用処理はこの候補で変更しません。
