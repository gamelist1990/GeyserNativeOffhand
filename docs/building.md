# ビルド

JDK 25以上、Python 3、検証したGeyser-Spigot JARとPaperのライブラリを用意します。[互換性](compatibility.md)に検証した版とチェックサムを記載しています。

## 実行

リポジトリを取得し、そのルートで実行します。パスは手元のファイルへ置き換えてください。

```bash
export GEYSER_JAR=/path/to/Geyser-Spigot.jar
export PAPER_LIBRARIES=/path/to/test-server/libraries
export EXTRA_CP="$(python3 -c 'import os; from pathlib import Path; print(os.pathsep.join(str(p) for p in Path(os.environ["PAPER_LIBRARIES"]).rglob("*.jar")))')"
bash build.sh
```

`GEYSER_JAR`はコンパイルとネイティブIDの生成に使います。`EXTRA_CP`にはPaperが用意した依存ライブラリを指定します。

## 出力

- `build/GeyserNativeOffhand-0.2.23-rewrite.23.jar` — サーバーへ導入する拡張。パックも同梱します。
- `build/classes/GeyserNativeOffhand.mcpack` — 同梱パックの確認用出力。通常の導入で別途配信設定は不要です。

JARを停止したサーバーの `plugins/Geyser-Spigot/extensions/` へ配置します。[READMEの導入手順](../README.md#導入)に従ってGeyserの既存設定を更新し、完全に再起動してください。

## 公式定義の取得と生成

毎回のビルドでMojang/bedrock-samplesのmainを解決し、そのコミットのUI・ブロック・terrain定義を照合します。同じコミットの入力は `build/mojang-source/` にキャッシュします。取得に失敗した場合や期待する定義が変わった場合は、古い定義へ黙って戻らずビルドを失敗させます。

画像PNGはダウンロード・同梱しません。公式定義から携帯用ブロックを選び、Geyserの `bedrock/runtime_item_states.26_50.json` と照合してネイティブAUXを生成します。UIとJava側の選択に同じ一覧を使います。

| ファイル | 用途 |
| --- | --- |
| `src/main/resources/mojang-hud-source.json` | 取得コミットと入力のSHA-256 |
| `src/main/resources/native-hud-literal-aux.txt` | Java側が選ぶAの整数AUX |
| `resource-pack/ui/doublehands_hud.json` | A/Bの描画と枠・個数 |
| `docs/research/native-hud-aux-literals.json` | 生成した対応と検証範囲 |

サーバー実行時にMojangのリポジトリへ問い合わせる処理はありません。生成ファイルが更新された場合は、関連するファイルを一緒にレビューしてください。
