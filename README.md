# GeyserNativeOffhand

Bedrock の通常のインベントリで、松明・ランタン・ブロック・剣などをオフハンドへ移動できるようにする Geyser-Spigot 拡張です。Java サーバーへオフハンドの設置・使用を送ります。食事・飲用も Java 側で処理します。

**v0.1.3-attachable.13 / RP 0.1.15 は、ブロックの描画とインベントリ許可を両立できるかの検証候補です。** RP 0.1.13 の実機では石が左上に離れて表示されました。元のアイテム種類に戻した 0.1.14 では、実機の石の位置は正常になりましたが、インベントリからオフハンドへ移動できなくなりました。今回、LEGACY のコンポーネント読込を使い、元の ID・ブロック状態を保ったまま許可を追加します。実機での表示とインベントリ操作は確認中です。

モーションを追加しない従来版は [v0.1.2](https://github.com/gamelist1990/GeyserNativeOffhand/releases/tag/v0.1.2) と `main` にあります。この試作は `attachable-consumption` ブランチで公開します。

## 対応環境

| 項目 | 検証したバージョン |
| --- | --- |
| Geyser-Spigot | 2.12.0-SNAPSHOT、build 3080、commit `e215fa5cf1ffc3874c4d46c579d25800b20f17e4` |
| Paper | 26.3-159-main@a4b87cf |
| Bedrock 自動クライアント | 1.26.51、protocol 2193 |
| サーバー Java | 26 |
| ビルド | JDK 25 以上、Python 3 |

この Geyser は Java 26.3 対応プレビューです。別の Geyser ビルド、Standalone、Velocity、他の Bedrock バージョンでの互換性は未確認です。検証した Geyser JAR の SHA-256 は `424cba7e10e81c0655839fd3fdc2aeaac3962ffd7d6b0222a8fe2552ac5c859c`。

## 導入

1. [Releases](https://github.com/gamelist1990/GeyserNativeOffhand/releases) から拡張 JAR を取得します。
2. 停止した検証サーバーの `plugins/Geyser-Spigot/extensions/` に配置します。
3. Geyser の `gameplay.enable-custom-content`、`gameplay.force-resource-packs`、`gameplay.enable-integrated-pack` を `true` にします。
4. サーバーを完全に再起動し、Bedrock で接続してリソースパックを受け入れます。

専用パックは JAR に同梱され、拡張が自動登録します。配布する mcpack を手動で重複登録する必要はありません。更新後は Bedrock も再接続してください。ホットリロードには対応しません。

## 実装

- 起動時に Geyser の Java アイテムマッピングと Bedrock ブロックパレットから対象を列挙します。松明だけの許可リストを使いません。
- 既存のネイティブな data-driven アイテムを保ち、それ以外に低優先度の v2 カスタムアイテムを登録します。バニラでオフハンドに対応する盾は元の ID を保ち、盾の attachable・模様・防御描画を使います。検証環境では 496 件のフォールバックを登録し、1,129 種の Java ブロックアイテムで元のマッピングを保ちました。
- 通常のブロックは元の Bedrock アイテム ID と描画用ブロック状態を保ちます。LEGACY のコンポーネント読込を使い、オフハンド許可と既定のスタック上限を設定します。インベントリからの移動は実機で確認します。松明と食品扱いの植えられるアイテムは従来のフォールバックのままです。その他のアイコンはバニラのテクスチャパスを参照します。Minecraft の画像ファイルは同梱しません。
- 全プロトコルの非空気アイテム定義へ `allow_off_hand` を設定します。検証クライアントへ送信された 2,580 件の定義で有効でした。
- 通常の ItemStackRequest を使ってオフハンド枠へ移動します。設置・使用は Java の OFF_HAND パケットで処理します。
- Java の使用中メタデータから食事・飲用の状態を管理します。繰り返し届く使用入力によるタイマーの再開始を防ぎ、Java 側で消費を完了させます。
- この試作では `nativeoffhand:consume_mode` プロパティで使用状態を同期し、リソースパックの attachable とアニメーションコントローラーで左手の食べ物モデルを動かします。プレイヤーのボーンへの独自 PlayAnimation は送りません。
- フォールバックの表示名は Geyser の Java アイテム翻訳とプレイヤーの言語設定で処理します。プレイヤーが付けた名前を優先します。

メインハンドが空、または剣などの使用しないアイテムの場合にオフハンドへ振り分けます。設置できるメインハンドのブロックなどはメインハンドを優先します。Bedrock の ITEM_USE には手の指定がないため、Java のすべての PASS → オフハンド判定を完全に再現したものではありません。食べられない満腹状態や、対象によって使用できない道具などの組み合わせには追加検証が必要です。

到達距離・カーソル・アイテム定義・ワールド境界・予測処理の検証は Geyser の既存処理に従います。オフハンド切り替えキーは実装しません。

## 食事 attachable の試作

Geyser の食品・消費コンポーネントから 40 種類を列挙し、バニラのテクスチャパスを使う attachable を生成します。食品のモデルをアイテムスロットに結び付けます。右手で使用する texture mesh と大きさを左手にも使い、保持姿勢を X 軸で鏡映します。左手だけで食事状態を参照し、モデルを内側・上へ移動させて上下に反復します。右手の食品には食事モーションを適用しません。Java の消費処理はそのまま使います。

りんご・パン・肉・シチュー・ベリーなどと、食品扱いの蜂蜜入りの瓶を対象にします。ポーションと牛乳はこのモデルの対象外です。三人称の食事モーションもこの試作の対象外です。食品の持つモデルは両手に適用します。右手の表示と、左手で持っているだけの姿勢が正常かを含め、実機で確認する必要があります。

Cooldown と同じ attachable の仕組みを参考にし、モデルとアニメーションを本拡張用に作成しました。Cooldown のモデルやアニメーション資産、Minecraft の画像はコピーしていません。プレイヤーの定義や Cooldown のコントローラー ID を上書きしません。ただし、同じ食品を変更する別のパックとの表示互換性は未検証です。

## 通常アイテムと食品の保持姿勢

通常のブロックを v2 カスタムアイテムへ置き換えると、Geyser の `ItemTranslator.translateCustomItem` が描画用の block runtime definition を消します。この版は、通常ブロックに本拡張のカスタムフォールバックを登録せず、元の `minecraft:` ID、データ値、描画用ブロック状態、Java 側のマッピングを保ちます。元の ID とブロック状態を保ち、コンポーネント読込には LEGACY を使います。`item_properties.allow_off_hand` と既定のスタック上限を設定します。元のブロックへカスタムの `minecraft:block_placer` を注入しません。表示名は元の Java 翻訳とプレイヤーの言語設定を使い、カスタム名を優先します。石だけの許可リストを使いません。Minecraft に対応しない検証用ブロック、松明、食品扱いの植えられるアイテムは従来のフォールバックを保ちます。

元のバニラアニメーションのパス `animations/player_firstperson.animation.json` に、保持姿勢の変更を置きます。元の右手の式を保ちながら、サーバーの実際の装備を表す `nativeoffhand:offhand_pose` を条件に左腕の姿勢を選びます。

| 値 | 描画の対象 |
| --- | --- |
| 0 | 空、元の種類を保ったブロック、バニラの松明・盾、別拡張のカスタム定義 |
| 1 | 本拡張が扱う通常品の data-driven 定義 |
| 2 | 本拡張が扱う剣・斧などの道具 |
| 3 | 食品 attachable |

Java のスロット・インベントリ変更と Bedrock のインベントリ要求を元の translator で処理した後、実際に選択された描画定義を調べて状態を同期します。値が変わった場合だけ更新します。切断と無効化時には状態を片付け、translator を元へ戻します。古い item tag だけで描画を判定しません。

対象の左手では、バニラの swap-item アニメーションが追加する高さ移動を止め、保持姿勢と二重に加算されることを防ぎます。通常品・食品の左腕は右腕の鏡映、非 VR の道具は握り位置を維持した向きの補正を使います。プレイヤーの entity 定義や root controller を複製していません。[出典](THIRD_PARTY_NOTICES.md)も参照してください。

ブロック切り分けは次の通りです。0.1.15 の食品と道具のモデル・アニメーションは 0.1.13 と同じです。

| 石の定義 | 元の Geyser | RP 0.1.13 | RP 0.1.14 | RP 0.1.15 |
| --- | --- | --- | --- | --- |
| アイテム種類 | NONE | DATA_DRIVEN | NONE | LEGACY |
| ID | minecraft:stone | minecraft:stone | minecraft:stone | minecraft:stone |
| カスタム block-placer | なし | 注入 | なし | なし |
| オフハンド許可の追加 | なし | あり | あり | あり |
| 描画用 block runtime state | あり | あり | あり | あり |

## Blockbench での Bedrock モデル確認

Ubuntu の [Web Blockbench](https://web.blockbench.net/) 5.2.2 を Remote Desktop Commander MCP と Chrome DevTools 経由で操作しました。Bedrock 形式の公式プレイヤーボーンのピボットに、今回の実際の食品モデルとアニメーションを読み込みます。Blockbench が条件式を直接インポートした際の軸反転差を避けるため、Molang を評価した数値を標準の Bedrock animation codec へ渡しています。

りんごとパンの待機状態で、左右の食品モデルのワールド行列が X 鏡映として一致しました。道具用補正では、握り位置の移動量が約 `2.64e-10` モデル単位以下でした。食品の食事中の移動も数値と画像で記録しています。

[現在の比較・数値・再現方法](docs/previews/bedrock/0.1.13/README.md) / [確認結果 JSON](docs/previews/bedrock/0.1.13/verification.json)

Blockbench は Bedrock 本体の組み込みブロック・道具の描画や、attachment 処理を実行しません。この確認はモデル・ボーンの比較であり、実機の最終位置・向きを保証しません。[0.1.12 のプレビュー](docs/previews/bedrock/0.1.12/README.md) は実機で失敗が残った過去の記録として保存します。

## 検証状況

実際の独立した Paper + Geyser サーバーと [PrismarineJS/bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) を使った **49 項目が通過**しました。

- 通常のインベントリから松明をオフハンドへ移動。
- 空の右手／右手の剣で、左手の松明を 1 個ずつ設置。
- メインハンドのブロックを優先し、重複設置を防止。
- オフハンドの水バケツ・エンダーパール・ポーション・りんごを使用。
- 右手に剣を持った状態で食事・飲用が完了し、プロパティが「飲用 → 待機 → 食事 → 待機」と変化すること。独自の PlayAnimation は送らないこと。
- 日本語クライアントへ送る石の表示名、カスタム名の維持、盾のバニラ ID とオフハンド往復移動。
- ランタン・魂のランタン・魂の松明・石・剣・りんご・水バケツ・記入済みの本のオフハンド往復移動。
- 食事の終了後にアニメーション状態が戻り、右手の剣＋左手の石で設置できること。
- 食事後も、右手の石が左手の食品より優先されること。
- 装備の変更に応じて、石・階段・エンダーパール・剣・斧・パン・りんご・松明・盾・空の描画用プロパティが更新されること。
- 石とブロックの往復移動で、`ItemVersion.LEGACY`、元の ID、非ゼロの block runtime state を保ち、カスタムの block-placer を注入しないこと。
- 板材・色付き羊毛・ガラス・フェンス・階段・チェスト・シュルカーボックス・色付きベッド・色付き旗・看板・植木鉢のオフハンド往復移動。
- パン・石レンガ・鉄の斧も通常のインベントリでオフハンドへ往復移動でき、描画用状態が更新・解除されること。
- 範囲外の操作と不正な保持アイテム定義を拒否。

実機では、松明のインベントリからの移動・設置、食品の消費、その他アイテムのオフハンド移動を確認しています。前版では松明の表示は正常でしたが、ブロック・パン・斧の一人称表示が正常になりませんでした。今回の候補を独立した検証サーバーに適用しました。実機で食品のモデル位置が変わりましたが、石は引き続き左上に離れて表示されました。RP 0.1.14 では石の表示位置は正常になりましたが、インベントリから移動できませんでした。RP 0.1.15 は LEGACY のコンポーネント読込を使う候補です。パケットテストは描画を検証しないため、この候補を実機確認済みとは扱いません。

特殊なアイテムの表示・翻訳名・attachable、他のカスタムアイテム拡張やリソースパックとの同時使用も網羅していません。フォールバックの優先度は低くしていますが、個別の互換性確認が必要です。

[サーバー側の検証結果](tests/results/2026-10-10-attachable.13.json) / [補正版の確認結果](tests/results/2026-10-10-block-regression-before.json) / [再実行手順](tests/bedrock/README.md)

## ビルド

検証した Geyser-Spigot JAR と、起動済み Paper のライブラリを用意します。Java のコンパイルに Gson・Guava・Netty・Checker Qual なども必要なため、Paper の `libraries` を追加クラスパスに指定します。

```bash
export GEYSER_JAR=/path/to/Geyser-Spigot.jar
export PAPER_LIBRARIES=/path/to/test-server/libraries
export EXTRA_CP="$(python3 -c 'import os; from pathlib import Path; print(os.pathsep.join(str(p) for p in Path(os.environ["PAPER_LIBRARIES"]).rglob("*.jar")))')"
./build.sh
```

出力は `build/GeyserNativeOffhand-0.1.3-attachable.13.jar` と `build/classes/GeyserNativeOffhand.mcpack` です。Geyser の内部クラスを使用するため、API JAR だけではビルドできません。

## ライセンスと出典

MIT。インベントリ操作トランスレーターは [GeyserMC/Geyser の該当コミット](https://github.com/GeyserMC/Geyser/blob/e215fa5cf1ffc3874c4d46c579d25800b20f17e4/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockInventoryTransactionTranslator.java) を基にしています。元の著作権表示とライセンスを保持しています。

開発には AI による実装補助を使用しました。このリポジトリは独立した実験拡張で、Geyser の公式機能・公式配布物ではありません。
