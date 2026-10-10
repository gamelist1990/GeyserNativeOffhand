# GeyserNativeOffhand

Bedrock の通常のインベントリで、松明・ランタン・ブロック・剣などをオフハンドへ移動できるようにする Geyser-Spigot 拡張です。Java サーバーへオフハンドの設置・使用を送ります。食事・飲用も Java 側で処理します。

**v0.1.3-attachable.14 / RP 0.1.16 は、通常のインベントリ内でだけ移動用のブロック定義を使う検証候補です。** 元の定義を使った RP 0.1.14 では、実機の石の表示位置は正常になりましたが、オフハンドへ移動できませんでした。0.1.15 の LEGACY 読込でも移動できませんでした。今回、インベントリでは許可を持つ data-driven 定義へ切り替え、閉じると元の ID・ブロック状態で再送します。Java 側のアイテムと個数・名前は維持します。実機では移動できましたが、閉じた後のブロックが左上に離れて表示され、両立には失敗しました。ユーザー提供の DoubleHands を参考に、新しいブランチで実装を組み直します。

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
- 既存のネイティブな data-driven アイテムを保ち、それ以外に低優先度の v2 カスタムアイテムを登録します。バニラでオフハンドに対応する盾は元の ID を保ち、盾の attachable・模様・防御描画を使います。検証環境では 496 件のフォールバックと 1,129 件のインベントリ専用ブロック定義を登録しました。通常のブロックは元のマッピングを保ちます。
- 通常のブロックは、ゲーム画面で元の Bedrock アイテム ID・種類と描画用ブロック状態を使います。通常のプレイヤーインベントリを開いている間だけ、オフハンド許可を持つ `nativeoffhand_ui:` の定義を送ります。閉じると全スロットを元の定義で再送します。松明と食品扱いの植えられるアイテムは従来のフォールバックのままです。Minecraft の画像ファイルは同梱しません。
- 全プロトコルの非空気アイテム定義へ `allow_off_hand` を設定します。検証クライアントへ送信された 3,709 件の定義で設定を確認しました。元の NONE・LEGACY ブロックでは、この値だけで実機の移動許可を変えられませんでした。
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

通常のブロックを常時 data-driven 定義へ置き換えると、一人称の表示位置が壊れました。元の NONE 定義では正しい位置になりますが、追加した `allow_off_hand` を実機が無視しました。この版では、Geyser のアイテム翻訳にインベントリ処理の範囲だけを渡し、その範囲と実際のプレイヤーインベントリの表示状態に応じて低優先度のカスタム定義を選びます。定義の切り替えでも Java のスタックやコンポーネントを書き換えず、スタックのネットワーク ID を保ちます。通常の ItemStackRequest を Geyser へ委譲して移動を処理し、クライアントまたはサーバーが閉じた後はインベントリ全体とオフハンドを元のマッピングで再送します。移動用の定義をゲーム画面の保持姿勢へ使い続けません。

対象は通常のプレイヤーインベントリです。クリエイティブのアイテム一覧からの取得や、他のコンテナの UI は、この切り替えの検証対象外です。パックの食品・道具モデルとアニメーションは 0.1.13 と同じで、今回の変更ではその向きや位置の問題を修正したとは扱いません。

元のバニラアニメーションのパス `animations/player_firstperson.animation.json` に、保持姿勢の変更を置きます。元の右手の式を保ちながら、サーバーの実際の装備を表す `nativeoffhand:offhand_pose` を条件に左腕の姿勢を選びます。

| 値 | 描画の対象 |
| --- | --- |
| 0 | 空、元の種類を保ったブロック、バニラの松明・盾、別拡張のカスタム定義 |
| 1 | 本拡張が扱う通常品の data-driven 定義 |
| 2 | 本拡張が扱う剣・斧などの道具 |
| 3 | 食品 attachable |

Java のスロット・インベントリ変更と Bedrock のインベントリ要求を元の translator で処理した後、実際に選択された描画定義を調べて状態を同期します。値が変わった場合だけ更新します。切断と無効化時には状態を片付け、translator を元へ戻します。古い item tag だけで描画を判定しません。

対象の左手では、バニラの swap-item アニメーションが追加する高さ移動を止め、保持姿勢と二重に加算されることを防ぎます。通常品・食品の左腕は右腕の鏡映、非 VR の道具は握り位置を維持した向きの補正を使います。プレイヤーの entity 定義や root controller を複製していません。[出典](THIRD_PARTY_NOTICES.md)も参照してください。

ブロック切り分けは次の通りです。0.1.16 の食品と道具のモデル・アニメーションは 0.1.13 と同じです。

| 石の定義 | RP 0.1.13 | RP 0.1.14 | RP 0.1.15 | RP 0.1.16 |
| --- | --- | --- | --- | --- |
| ゲーム画面の種類 | DATA_DRIVEN | NONE | LEGACY | NONE |
| インベントリ内の種類 | DATA_DRIVEN | NONE | LEGACY | DATA_DRIVEN |
| ゲーム画面の ID | minecraft:stone | minecraft:stone | minecraft:stone | minecraft:stone |
| インベントリ内の ID | minecraft:stone | minecraft:stone | minecraft:stone | nativeoffhand_ui:stone |
| 元の定義への block-placer 注入 | あり | なし | なし | なし |
| ゲーム画面の描画用 block state | 維持 | 維持 | 維持 | 維持 |

## Blockbench での Bedrock モデル確認

Ubuntu の [Web Blockbench](https://web.blockbench.net/) 5.2.2 を Remote Desktop Commander MCP と Chrome DevTools 経由で操作しました。Bedrock 形式の公式プレイヤーボーンのピボットに、今回の実際の食品モデルとアニメーションを読み込みます。Blockbench が条件式を直接インポートした際の軸反転差を避けるため、Molang を評価した数値を標準の Bedrock animation codec へ渡しています。

りんごとパンの待機状態で、左右の食品モデルのワールド行列が X 鏡映として一致しました。道具用補正では、握り位置の移動量が約 `2.64e-10` モデル単位以下でした。食品の食事中の移動も数値と画像で記録しています。

[現在の比較・数値・再現方法](docs/previews/bedrock/0.1.13/README.md) / [確認結果 JSON](docs/previews/bedrock/0.1.13/verification.json)

Blockbench は Bedrock 本体の組み込みブロック・道具の描画や、attachment 処理を実行しません。この確認はモデル・ボーンの比較であり、実機の最終位置・向きを保証しません。[0.1.12 のプレビュー](docs/previews/bedrock/0.1.12/README.md) は実機で失敗が残った過去の記録として保存します。

## 検証状況

実際の独立した Paper + Geyser サーバーと [PrismarineJS/bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) を使った **55 項目が通過**しました。

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
- 石とブロックの往復移動で、開いている間は移動用の data-driven 定義を使い、閉じた後は `ItemVersion.NONE`、元の ID・データ値・block runtime state を保つこと。元の定義にカスタムの block-placer を注入しないこと。
- 名前付きの石が、インベントリを開く・移動する・閉じる各段階で名前を保つこと。
- 石と階段を通常のインベントリからオフハンドへ移動し、閉じた後に元の定義とスタック ID を保って 1 個設置できること。再び開くと移動用の定義になり、残数を戻せること。
- 板材・色付き羊毛・ガラス・フェンス・階段・チェスト・シュルカーボックス・色付きベッド・色付き旗・看板・植木鉢のオフハンド往復移動。
- パン・石レンガ・鉄の斧も通常のインベントリでオフハンドへ往復移動でき、描画用状態が更新・解除されること。
- 範囲外の操作と不正な保持アイテム定義を拒否。

実機では、松明のインベントリからの移動・設置、食品の消費、その他アイテムのオフハンド移動を確認しています。RP 0.1.13 では石が左上に離れて表示されました。RP 0.1.14 では石の表示位置は正常になりましたが、インベントリから移動できませんでした。RP 0.1.15 でも移動できませんでした。0.1.16 の候補を独立した検証サーバーに適用しました。パケットテストは Bedrock 本体のドラッグ制限や描画を実行しないため、実機では移動できましたが、表示位置は再び左上に離れていました。この候補は表示の失敗として記録します。食品・道具の表示についても未解決の報告があります。

特殊なアイテムの表示・翻訳名・attachable、他のカスタムアイテム拡張やリソースパックとの同時使用も網羅していません。フォールバックの優先度は低くしていますが、個別の互換性確認が必要です。

[サーバー側の検証結果](tests/results/2026-10-10-attachable.14.json) / [補正版の確認結果](tests/results/2026-10-10-block-regression-before.json) / [再実行手順](tests/bedrock/README.md)

## ビルド

検証した Geyser-Spigot JAR と、起動済み Paper のライブラリを用意します。Java のコンパイルに Gson・Guava・Netty・Checker Qual なども必要なため、Paper の `libraries` を追加クラスパスに指定します。

```bash
export GEYSER_JAR=/path/to/Geyser-Spigot.jar
export PAPER_LIBRARIES=/path/to/test-server/libraries
export EXTRA_CP="$(python3 -c 'import os; from pathlib import Path; print(os.pathsep.join(str(p) for p in Path(os.environ["PAPER_LIBRARIES"]).rglob("*.jar")))')"
./build.sh
```

出力は `build/GeyserNativeOffhand-0.1.3-attachable.14.jar` と `build/classes/GeyserNativeOffhand.mcpack` です。Geyser の内部クラスを使用するため、API JAR だけではビルドできません。

## ライセンスと出典

MIT。インベントリ操作トランスレーターは [GeyserMC/Geyser の該当コミット](https://github.com/GeyserMC/Geyser/blob/e215fa5cf1ffc3874c4d46c579d25800b20f17e4/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockInventoryTransactionTranslator.java) を基にしています。元の著作権表示とライセンスを保持しています。

開発には AI による実装補助を使用しました。このリポジトリは独立した実験拡張で、Geyser の公式機能・公式配布物ではありません。
