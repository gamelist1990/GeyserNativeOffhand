# GeyserNativeOffhand

Bedrock の通常のインベントリで、松明・ランタン・ブロック・剣などをオフハンドへ移動できるようにする Geyser-Spigot 拡張です。Java サーバーへオフハンドの設置・使用を送ります。食事・飲用も Java 側で処理します。

**v0.1.3-attachable.7 は、一人称のオフハンド食事モーションと通常アイテムの保持位置を試す版です。食品と松明の表示は実機で確認しました。石が遠く離れ、エンダーパールが画面下へずれる報告を受け、通常品の左腕へバニラの右腕を反転した姿勢を適用する版を検証中です。描画の確認は別途必要です。** 対応する Geyser の内部実装に依存します。

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
- 既存のネイティブな data-driven アイテムを保ち、それ以外に低優先度の v2 カスタムアイテムを登録します。バニラでオフハンドに対応する盾は元の ID を保ち、盾の attachable・模様・防御描画を使います。検証環境では 1,625 件のフォールバックを登録しました。
- ブロックは Geyser の実際の Bedrock ブロック情報を使い、その他のアイコンはバニラのテクスチャパスを参照します。Minecraft の画像ファイルは同梱しません。
- 全プロトコルの非空気アイテム定義へ `allow_off_hand` を設定します。検証クライアントへ送信された 3,709 件の定義で有効でした。
- 通常の ItemStackRequest を使ってオフハンド枠へ移動します。設置・使用は Java の OFF_HAND パケットで処理します。
- Java の使用中メタデータから食事・飲用の状態を管理します。繰り返し届く使用入力によるタイマーの再開始を防ぎ、Java 側で消費を完了させます。
- この試作では `nativeoffhand:consume_mode` プロパティで使用状態を同期し、リソースパックの attachable とアニメーションコントローラーで左手の食べ物モデルを動かします。プレイヤーのボーンへの独自 PlayAnimation は送りません。
- フォールバックの表示名は Geyser の Java アイテム翻訳とプレイヤーの言語設定で処理します。プレイヤーが付けた名前を優先します。

メインハンドが空、または剣などの使用しないアイテムの場合にオフハンドへ振り分けます。設置できるメインハンドのブロックなどはメインハンドを優先します。Bedrock の ITEM_USE には手の指定がないため、Java のすべての PASS → オフハンド判定を完全に再現したものではありません。食べられない満腹状態や、対象によって使用できない道具などの組み合わせには追加検証が必要です。

到達距離・カーソル・アイテム定義・ワールド境界・予測処理の検証は Geyser の既存処理に従います。オフハンド切り替えキーは実装しません。

## 食事 attachable の試作

Geyser の食品・消費コンポーネントから 40 種類を列挙し、バニラのテクスチャパスを使う attachable を生成します。食品のモデルをアイテムスロットに結び付け、右手と左手で別々の geometry と持つ姿勢を使います。左手はバニラの盾が使う座標系を基準に、バニラ食品テクスチャを薄いモデルへ貼ります。左手だけで食事状態を参照し、右手の食品にはこの食事モーションを適用しません。使用中は一人称のモデルを口元へ寄せて上下させ、終了時に持つ姿勢へ戻す構成です。Java の消費処理はそのまま使います。

りんご・パン・肉・シチュー・ベリーなどと、食品扱いの蜂蜜入りの瓶を対象にします。ポーションと牛乳はこのモデルの対象外です。三人称の食事モーションもこの試作の対象外です。食品の持つモデルは両手に適用します。右手の表示と、左手で持っているだけの姿勢が正常かを含め、実機で確認する必要があります。

Cooldown と同じ attachable の仕組みを参考にし、モデルとアニメーションを本拡張用に作成しました。Cooldown のモデルやアニメーション資産、Minecraft の画像はコピーしていません。プレイヤーの定義や Cooldown のコントローラー ID を上書きしません。ただし、同じ食品を変更する別のパックとの表示互換性は未検証です。

## 通常アイテムの保持姿勢

バニラの `animation.player.first_person.empty_hand` は右腕の一人称姿勢を設定しますが、通常品を持つ左腕にはその姿勢を設定しません。この版では、このアニメーションの既存の右腕・アイテムの処理を保ち、左右反転した右腕の位置と回転を左腕へ条件付きで追加します。石などのブロックとエンダーパールなどの通常品には新しい独自モデルを使わず、クライアントのアイテム描画を使います。右手や三人称の姿勢は変更しません。

条件は、この拡張が登録した非食品フォールバックだけに付ける `nativeoffhand:vanilla_offhand_pose` タグです。実機で良好な松明と同系統の魂の松明・レッドストーンの松明は除外します。食品の attachable、バニラの盾、他の拡張が登録したカスタムアイテムにもタグを付けません。食品のモデル・アニメーションは、実機で確認した attachable.5 と同じ内容です。

attachable.6 で試した旧式の `minecraft:render_offsets` による一律の座標補正は撤去しました。検証サーバーでは Geyser の内蔵パックも有効に戻しています。プレイヤーの entity 定義や root コントローラーを置き換えず、Cooldown / Glowing の既存コントローラーからも参照されるバニラの empty_hand アニメーションを拡張します。同じアニメーションを別途置き換えるパックとの互換性には確認が必要です。[出典](THIRD_PARTY_NOTICES.md)も参照してください。

## 検証状況

実際の独立した Paper + Geyser サーバーと [PrismarineJS/bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) を使った **31 項目が通過**しました。

- 通常のインベントリから松明をオフハンドへ移動。
- 空の右手／右手の剣で、左手の松明を 1 個ずつ設置。
- メインハンドのブロックを優先し、重複設置を防止。
- オフハンドの水バケツ・エンダーパール・ポーション・りんごを使用。
- 右手に剣を持った状態で食事・飲用が完了し、プロパティが「飲用 → 待機 → 食事 → 待機」と変化すること。独自の PlayAnimation は送らないこと。
- 日本語クライアントへ送る石の表示名、カスタム名の維持、盾のバニラ ID とオフハンド往復移動。
- ランタン・魂のランタン・魂の松明・石・剣・りんご・水バケツ・記入済みの本のオフハンド往復移動。
- 食事の終了後にアニメーション状態が戻り、右手の剣＋左手の石で設置できること。
- 食事後も、右手の石が左手の食品より優先されること。
- 通常品のバニラ姿勢用タグが送られ、旧式の座標補正がなく、松明・食品・盾が対象外であること。
- 範囲外の操作と不正な保持アイテム定義を拒否。

実機では、プレイヤーが松明のインベントリからの移動・設置、りんごの消費、全アイテム対応版でのオフハンドへの移動を確認しています。表示名と盾の ID は v0.1.1 で修正しました。従来の v0.1.2 は食事・飲用のカスタムモーションを削除した版です。このブランチで attachable 方式を再検証しています。40 個の attachable の ID と JSON も確認していますが、パケットテストは実機の描画を検証しません。attachable.5 の一人称の食品表示と食事モーションについて、実機で「いい感じ」との確認を受けました。通常品については、その後の実機画像で石が左上に小さく離れて表示され、エンダーパールが画面下へずれること、松明はバニラの姿勢で正常であることを確認しました。attachable.6 の旧式の補正は採用せず、attachable.7 で通常品の左腕にバニラの保持姿勢を反転して適用します。食品パックの各ファイルは、実機で確認した attachable.5 と同じ内容です。新しい左腕補正の実機描画は確認待ちです。

特殊なアイテムの表示・翻訳名・attachable、他のカスタムアイテム拡張やリソースパックとの同時使用も網羅していません。フォールバックの優先度は低くしていますが、個別の互換性確認が必要です。

[サーバー側の検証結果](tests/results/2026-10-10-attachable.7.json) / [補正版の確認結果](tests/results/2026-10-10-block-regression-before.json) / [再実行手順](tests/bedrock/README.md)

## ビルド

検証した Geyser-Spigot JAR と、起動済み Paper のライブラリを用意します。Java のコンパイルに Gson・Guava・Netty・Checker Qual なども必要なため、Paper の `libraries` を追加クラスパスに指定します。

```bash
export GEYSER_JAR=/path/to/Geyser-Spigot.jar
export PAPER_LIBRARIES=/path/to/test-server/libraries
export EXTRA_CP="$(python3 -c 'import os; from pathlib import Path; print(os.pathsep.join(str(p) for p in Path(os.environ["PAPER_LIBRARIES"]).rglob("*.jar")))')"
./build.sh
```

出力は `build/GeyserNativeOffhand-0.1.3-attachable.7.jar` と `build/classes/GeyserNativeOffhand.mcpack` です。Geyser の内部クラスを使用するため、API JAR だけではビルドできません。

## ライセンスと出典

MIT。インベントリ操作トランスレーターは [GeyserMC/Geyser の該当コミット](https://github.com/GeyserMC/Geyser/blob/e215fa5cf1ffc3874c4d46c579d25800b20f17e4/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockInventoryTransactionTranslator.java) を基にしています。元の著作権表示とライセンスを保持しています。

開発には AI による実装補助を使用しました。このリポジトリは独立した実験拡張で、Geyser の公式機能・公式配布物ではありません。
