# GeyserNativeOffhand

Bedrock の通常のインベントリで、松明・ランタン・ブロック・剣などをオフハンドへ移動できるようにする Geyser-Spigot 拡張です。Java サーバーへオフハンドの設置・使用を送り、食事・飲用時は左手用の PlayAnimation を再生します。

**v0.1.1 は実験版です。対応する Geyser の内部実装に依存します。** 最新の食事アニメーションの見た目は実機で確認中です。

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
3. Geyser の `gameplay.enable-custom-content` と `gameplay.force-resource-packs` を `true` にします。
4. サーバーを完全に再起動し、Bedrock で接続してリソースパックを受け入れます。

専用パックは JAR に同梱され、拡張が自動登録します。配布する mcpack を手動で重複登録する必要はありません。更新後は Bedrock も再接続してください。ホットリロードには対応しません。

## 実装

- 起動時に Geyser の Java アイテムマッピングと Bedrock ブロックパレットから対象を列挙します。松明だけの許可リストを使いません。
- 既存のネイティブな data-driven アイテムを保ち、それ以外に低優先度の v2 カスタムアイテムを登録します。バニラでオフハンドに対応する盾は元の ID を保ち、盾の attachable・模様・防御描画を使います。検証環境では 1,625 件のフォールバックを登録しました。
- ブロックは Geyser の実際の Bedrock ブロック情報を使い、その他のアイコンはバニラのテクスチャパスを参照します。Minecraft の画像ファイルは同梱しません。
- 全プロトコルの非空気アイテム定義へ `allow_off_hand` を設定します。検証クライアントへ送信された 3,702 件の定義で有効でした。
- 通常の ItemStackRequest を使ってオフハンド枠へ移動します。設置・使用は Java の OFF_HAND パケットで処理します。
- Java の使用中メタデータに従い、オフハンドの EAT / DRINK を PlayAnimation（AnimateEntityPacket）で開始・停止します。開始条件のプロパティを再生前に即時送信し、一人称にはアイテムの上下運動、三人称には左腕の食事・飲用モーションを使います。
- フォールバックの表示名は Geyser の Java アイテム翻訳とプレイヤーの言語設定で処理します。プレイヤーが付けた名前を優先します。

メインハンドが空、または剣などの使用しないアイテムの場合にオフハンドへ振り分けます。設置できるメインハンドのブロックなどはメインハンドを優先します。Bedrock の ITEM_USE には手の指定がないため、Java のすべての PASS → オフハンド判定を完全に再現したものではありません。食べられない満腹状態や、対象によって使用できない道具などの組み合わせには追加検証が必要です。

到達距離・カーソル・アイテム定義・ワールド境界・予測処理の検証は Geyser の既存処理に従います。オフハンド切り替えキーは実装しません。

## 検証状況

実際の独立した Paper + Geyser サーバーと [PrismarineJS/bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) を使った **26 項目が通過**しました。

- 通常のインベントリから松明をオフハンドへ移動。
- 空の右手／右手の剣で、左手の松明を 1 個ずつ設置。
- メインハンドのブロックを優先し、重複設置を防止。
- オフハンドの水バケツ・エンダーパール・ポーション・りんごを使用。
- 食事・飲用の PlayAnimation 開始と idle への復帰、および再生前の開始プロパティ送信。
- 日本語クライアントへ送る石の表示名、カスタム名の維持、盾のバニラ ID とオフハンド往復移動。
- ランタン・魂のランタン・魂の松明・石・剣・りんご・水バケツ・記入済みの本のオフハンド往復移動。
- 範囲外の操作と不正な保持アイテム定義を拒否。

実機では、プレイヤーが松明のインベントリからの移動・設置、りんごの消費、全アイテム対応版でのオフハンドへの移動を確認しています。v0.1.0 では翻訳キーの露出、盾の描画不良、食事モーションが出ない不具合が報告されたため、v0.1.1 で修正しました。修正版の名前・盾・食事モーションの見た目は実機確認待ちです。パケットテストは描画を検証しません。

特殊なアイテムの表示・翻訳名・attachable、他のカスタムアイテム拡張やリソースパックとの同時使用も網羅していません。フォールバックの優先度は低くしていますが、個別の互換性確認が必要です。

[保存した検証結果](tests/results/2026-10-09-v0.1.1.json) / [再実行手順](tests/bedrock/README.md)

## ビルド

検証した Geyser-Spigot JAR と、起動済み Paper のライブラリを用意します。Java のコンパイルに Gson・Guava・Netty・Checker Qual なども必要なため、Paper の `libraries` を追加クラスパスに指定します。

```bash
export GEYSER_JAR=/path/to/Geyser-Spigot.jar
export PAPER_LIBRARIES=/path/to/test-server/libraries
export EXTRA_CP="$(python3 -c 'import os; from pathlib import Path; print(os.pathsep.join(str(p) for p in Path(os.environ["PAPER_LIBRARIES"]).rglob("*.jar")))')"
./build.sh
```

出力は `build/GeyserNativeOffhand-0.1.1.jar` と `build/classes/GeyserNativeOffhand.mcpack` です。Geyser の内部クラスを使用するため、API JAR だけではビルドできません。

## ライセンスと出典

MIT。インベントリ操作トランスレーターは [GeyserMC/Geyser の該当コミット](https://github.com/GeyserMC/Geyser/blob/e215fa5cf1ffc3874c4d46c579d25800b20f17e4/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockInventoryTransactionTranslator.java) を基にしています。元の著作権表示とライセンスを保持しています。

開発には AI による実装補助を使用しました。このリポジトリは独立した実験拡張で、Geyser の公式機能・公式配布物ではありません。
