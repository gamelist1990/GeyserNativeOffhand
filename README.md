# GeyserNativeOffhand

Bedrock の通常のインベントリで、アイテムをオフハンドへ移動し、Java サーバーへ設置・使用を送る Geyser-Spigot 拡張です。

**0.2.14-rewrite.14 / RP 0.2.14 は、ユーザー提供の DoubleHands を参考に実装を組み直した検証版です。** ゲーム画面では元のバニラのアイテム ID、種類、ブロック状態を使います。以前の腕補正・食品モデル・アニメーションは取り除きました。食品の消費は機能テストを通っていますが、独自の食事モーションはありません。

DoubleHands の元の HUD はユーザーの実機で良好との報告を受けました。インベントリ背景の左手の斧は 0.2.11 で表示でき、「めっちゃ惜しい」との報告を受けました。0.2.12 はユーザーの指示に従い、Blockbench のバニラ Java 左手モデルを基準に、道具の向き・倍率・ピクセルの厚みを合わせます。表示できた保持座標とモデルの中心を使い、子ボーンで形を回転させます。Blockbench の比較で元モデルと一致し、0.2.12 の向きは実機でも「完璧」と確認されました。開いた時の位置には差が残るため、0.2.13 は同じモデルの左手の保持座標を調整します。提供された通常画面とインベントリ画面の画像を基準に、Blockbench で位置を比較しました。新しい座標の実機確認は待機中です。

開発ブランチは `native-renderer-rewrite`。従来の公開版は `main` / [v0.1.2](https://github.com/gamelist1990/GeyserNativeOffhand/releases/tag/v0.1.2)、失敗した表示補正の記録は `attachable-consumption` に残します。本拡張は Geyser の公式機能ではありません。

## DoubleHands から参考にしたこと

[解析記録](docs/doublehands-reference.md)に、入力ファイルの SHA-256 と確認した処理を記載しています。DoubleHands は Bedrock Script API と `replaceitem` でバニラのアイテムを装備させます。保持モデルや腕の姿勢を置き換えていません。一方、通常のインベントリ操作を解禁する仕組みはなく、スニーク・エモート・コマンドで切り替えます。

この拡張では、その「装備はバニラのアイテムのまま」という方針を参考に、新しい Geyser 用コードを作成しました。DoubleHands のスクリプト・行動パックは同梱せず、HUD の配置・枠・個数表示を取り込み、0.2.14 はアイコン部分をクライアントの標準 renderer へ切り替えます。オフハンド切り替えキー、スニーク切り替え、専用メニューは実装しません。

## 今回の操作要件と表示の制約

通常の Bedrock インベントリで、持ち物欄やホットバーから既存のオフハンド枠へ直接アイテムを置き、取り出せることを優先します。専用フォームやキー切り替えを必要としません。個数を分ける・同じアイテムをまとめる・入っている別アイテムと交換する操作も、通常の ItemStackRequest を使います。Java 側には実際のオフハンドとして反映します。

ユーザーが提示した DoubleHands の説明では、オフハンドのブロックが小さく見えることは Bedrock 側の既知の表示制約とされています。ユーザーはこの小さい表示を許容しています。この版ではブロックの拡大や腕の補正を追加せず、スロット操作・アイテム保持・設置を優先します。小さい表示だけを理由に移動や設置を失敗とは判定しません。

同じ説明にある食品の使用制限は DoubleHands のものです。本拡張の Java 側での消費はポーション・りんごの実サーバー試験で通っています。ただし、左手の食品表示・食事モーションがバニラ同等であることを示す結果ではありません。この基準版に独自の食品モデルや食事アニメーションはありません。

## 左端のオフハンド HUD

0.2.14 はユーザーの指示に従い、`textures/items/iso_renders` のアイコン PNG 1,674 枚と旧テクスチャマップを全削除します。画像は DoubleHands のオフハンド枠 1 枚だけ残します。元の通常 HUD への追加処理、タッチ／クラシックの位置と個数表示を維持し、アイコン部分を Bedrock の `inventory_item_renderer` に替えます。

Geyser は保持中のネイティブ定義の runtime ID とデータ値から AUX を作り、title へ数値文字列、subtitle へ 2 個以上の個数を送ります。空なら AUX は 0、個数は空文字とし、枠を隠します。インベントリ表示中も HUD には移動用 alias を使いません。アイコンの画像・モデルはクライアント側の既存資産を使う構成です。

ビルドしたパックは **50,834 バイト**、旧 RP 0.2.13 は 9,560,402 バイトでした（約 99.47% 削減）。**新しい数値 binding とアイコン描画は実機確認待ちです。** AUX は完全なアイテムスタックではないため、ポーションの色、染色・模様・エンチャント等の細かな表示、他パックのカスタムアイテムへの対応も未確認です。

通常タイトルと副題のチャンネルは HUD 専用に使います。Java のタイトル・副題・時間・リセットで HUD の数値と個数を上書きせず、アクションバーは元の Geyser の処理を使います。250ms ごとに確認し、変化がない場合も 1 秒ごとに更新します。

[軽量化調査と一次資料](docs/hud-texture-research.md) / [HUD の変更記録と実機確認項目](docs/offhand-hud.md)。以前の画像式 0.2.4 はユーザー確認済みですが、今回の renderer の合格を示すものではありません。

## 実装

- Geyser のアイテムマッピングとブロックパレットから自動列挙し、通常のプレイヤーインベントリ用に 1,625 件の低優先度の data-driven 定義を登録します。
- 通常のインベントリが開いている間だけ、移動を許可する `nativeoffhand_ui:` 定義を選びます。通常の ItemStackRequest を Geyser の処理へ渡します。
- 移動用の道具には `hand_equipped=true` を設定します。UI 用の剣・斧・採掘道具 35 種類に左右の手を判定する attachable を付けます。左手・一人称はバニラの盾の保持構成を参照し、256 個の立方体で元の画像を描きます。0.2.12 は子ボーンの向きとピクセル倍率 0.68 を、Blockbench のバニラ Java 左手モデルへ合わせます。右手・三人称は既存の配置です。[技術記録と元モデルとの比較](docs/ui-tool-attachables.md)。
- 閉じると元のバニラ ID とブロック状態へ戻します。両手の表示を更新し、移動用の定義から保持用の定義へ変わるアイテムには新しい Bedrock のスタック ID を割り当てます。Java 側の種類・個数・名前・コンポーネントは維持します。
- 元の NONE・LEGACY のアイテム定義、保持モデル、プレイヤーの定義・ボーン・アニメーションを変更しません。元から data-driven のアイテムにはオフハンド許可だけを設定します。
- 食品や道具も、ゲーム画面ではバニラの ID を使います。リソースパックにはバニラの画像パスを参照する移動用アイコン一覧と DoubleHands 由来の HUD・枠と、クライアントのアイコン描画への数値 binding を含めます。元のバニラ ID のモデルは変更しません。UI 用の道具の attachable・モデル・保持アニメーションは同梱します。
- メインハンドが空、または剣などの使用しないアイテムのとき、使用を Java の OFF_HAND へ送ります。ブロックを置ける右手は優先します。他の操作は元の Geyser translator へ渡します。
- オフハンドの設置では Geyser の到達距離判定を使い、保持定義、カーソル、ワールド境界、連続入力と予測の状態を確認します。消費は Java 側で処理し、食事・飲用中の繰り返し入力で使用を再開始させません。
- 名前はプレイヤーの言語設定で翻訳し、プレイヤーが付けた名前を優先します。無効化時は translator と変更した定義・翻訳を復元します。

Bedrock の使用パケットには手の指定がないため、Java の全ての PASS 判定を完全に再現したものではありません。満腹で食べられない食品を右手に持つ場合などには追加検証が必要です。クリエイティブの一覧や、別のコンテナ UI、他のカスタムアイテム拡張との同時使用も、この基準版の検証対象外です。

## 検証

独立した Paper + Geyser 検証鯖と [PrismarineJS/bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) で **0.2.14 の 70 項目が通過しました**。自動テストは Bedrock 本体のドラッグ制限や画面描画を実行しません。

- 剣・斧など 13 種類の移動用定義の `hand_equipped` と、食品・ブロックなど 6 種類に道具用の設定を追加しないことを受信パケットから確認。UI 用 attachable は別途、識別子・参照・左右の支点を検査します。
- 松明・ランタン・魂の松明・石・石レンガ・剣・斧・食品・バケツ・本など 23 種類をインベントリとオフハンドの間で往復移動。
- 板材、色付き羊毛、ガラス、フェンス、階段、チェスト、シュルカーボックス、ベッド、旗、看板、植木鉢などの個数・Java 側の種類・元のブロック状態を維持。
- 通常の持ち物欄から部分スタックをオフハンドへ移動し、同じ種類をまとめ、カーソルで分けて持ち物欄へ戻す。
- オフハンドの既存のブロックを食品や剣と交換し、耐久値・エンチャント・名前と全アイテムの残数を維持。
- 石と階段を移動して閉じ、元の定義で 1 個設置し、開き直して残りを戻す。
- 閉じる際に、表示解除と元の OFFHAND 装備パケットが送られることを確認。
- 松明・水バケツ・エンダーパール・ポーション・りんごをオフハンドから使用。
- 右手のブロックを優先し、二重設置を防ぐ。範囲外の操作と不正な保持定義を拒否。
- 日本語の石の名前と、名前付きの石を開く・移動・閉じる各段階で維持。
- HUD にネイティブ AUX・個数・空状態を送り、インベントリ表示中も HUD に移動用 alias を使わないことを確認。
- 表示データを定期更新し、Java の通常タイトル・副題・リセットがHUD データを上書きしないこと、アクションバーが使えることを確認。

[実行手順](tests/bedrock/README.md) / [画像を全削除した HUD 0.2.14 の検証結果](tests/results/2026-10-11-native-hud-no-icons.json) / [盾の基準にした候補の検証結果](tests/results/2026-10-10-ui-tool-shield-basis.json) / [正の倍率にした比較候補の記録](tests/results/2026-10-10-ui-tool-positive-scale.json) / [UI 用道具 0.2.7 の失敗記録](tests/results/2026-10-10-ui-tool-attachables.json) / [バニラ装備パケットの検証結果](tests/results/2026-10-10-native-equipment.json) / [道具の描画プリセットの検証結果](tests/results/2026-10-10-tool-render-presets.json) / [DoubleHands HUD の検証結果](tests/results/2026-10-10-doublehands-hud.json) / [前版の実機での失敗記録](tests/results/2026-10-10-offhand-hud.json) / [開閉時の道具描画設定を修正した結果](tests/results/2026-10-10-handheld-pose.json) / [通常のインベントリ操作を追加した結果](tests/results/2026-10-10-native-inventory.json) / [最初の検証結果](tests/results/2026-10-10-rewrite.1.json)

前の RP 0.1.14 は、実機で石の位置が正常になりましたが移動できませんでした。0.1.15 も移動できませんでした。0.1.16 は移動できましたが、ブロックが左上に離れて表示されました。これらを修正完了とは扱いません。[最後の失敗記録](tests/results/2026-10-10-attachable.14.json)と過去の Blockbench プレビューは履歴として残します。

## 対応環境

| 項目 | 検証したバージョン |
| --- | --- |
| Geyser-Spigot | 2.12.0-SNAPSHOT、build 3080、commit `e215fa5cf1ffc3874c4d46c579d25800b20f17e4` |
| Paper | 26.3-159-main@a4b87cf |
| Bedrock 自動クライアント | 1.26.51、protocol 2193 |
| サーバー Java | 26 |
| ビルド | JDK 25 以上、Python 3 |

検証した Geyser JAR の SHA-256 は `424cba7e10e81c0655839fd3fdc2aeaac3962ffd7d6b0222a8fe2552ac5c859c`。内部クラスを使うため、他の Geyser ビルドや Standalone・Velocity での互換性は未確認です。

## 導入とビルド

停止した検証サーバーの `plugins/Geyser-Spigot/extensions/` に JAR を置き、Geyser の `gameplay.enable-custom-content`、`gameplay.force-resource-packs`、`gameplay.enable-integrated-pack` を `true` にして完全に再起動します。パックは JAR に同梱され、自動登録します。更新後は Bedrock も再接続してください。

検証した Geyser-Spigot JAR と Paper のライブラリを用意します。

```bash
export GEYSER_JAR=/path/to/Geyser-Spigot.jar
export PAPER_LIBRARIES=/path/to/test-server/libraries
export EXTRA_CP="$(python3 -c 'import os; from pathlib import Path; print(os.pathsep.join(str(p) for p in Path(os.environ["PAPER_LIBRARIES"]).rglob("*.jar")))')"
./build.sh
```

出力は `build/GeyserNativeOffhand-0.2.14-rewrite.14.jar` と `build/classes/GeyserNativeOffhand.mcpack` です。

## ライセンスと出典

独自コードは MIT。DoubleHands 由来の UI と枠画像は第三者ファイルで、MIT として再許諾しません。[第三者の出典](THIRD_PARTY_NOTICES.md)を参照してください。AI による実装補助を使用しています。
