# Bedrock統合テスト

PrismarineJSのbedrock-protocol 3.60.1を使い、実際のPaper / Geyserへ接続してJava側の状態とBedrockパケットを照合します。Node.js 24以上が必要です。

試験はNativeOHのインベントリ・ゲームモード・位置を変更し、原点周辺に足場と試験ブロックを作ります。独立した新しい検証ワールドを使ってください。実機参加者がいる場合は、試験用プレイヤーと原点周辺の試験領域から分けてください。

## サーバーの準備

[検証した環境](compatibility.md)に合わせて新しいPaperサーバーを用意し、Paper・Geyser・Minecraftの配布条件を確認します。Geyser-Spigotと本拡張のJARを配置してください。

`server.properties`の関連項目を次のように設定します。

```properties
server-ip=127.0.0.1
server-port=25601
online-mode=false
enable-rcon=true
rcon.port=25602
rcon.password=YOUR_LOCAL_TEST_PASSWORD
difficulty=peaceful
level-type=minecraft:flat
spawn-protection=0
```

生成されたGeyser設定の関連項目を更新します。設定全体をこの抜粋で置き換えないでください。

```yaml
bedrock:
  address: 127.0.0.1
  port: 19195
  transport: raknet
java:
  auth-type: offline
gameplay:
  enable-custom-content: true
  force-resource-packs: true
  enable-integrated-pack: true
advanced:
  java:
    use-direct-connection: false
  bedrock:
    validate-bedrock-login: false
```

署名のない自動クライアント用のローカル設定です。接続先をループバックに限定して使います。

検証したJava 26の起動方法は次のとおりです。

```bash
java -Xms512M -Xmx2G -DGeyser.RakSendCookie=false -DdisableNativeEventLoop=true -jar paper.jar nogui
```

リポジトリ直下の `.integration/test-settings.json` へ接続設定を保存します。このファイルはgitで無視されます。

```json
{
  "bedrockPort": 19195,
  "rconPort": 25602,
  "rconPassword": "YOUR_LOCAL_TEST_PASSWORD"
}
```

## 実行

リポジトリのルートから実行します。

```bash
cd tests/bedrock
npm ci
npm test
```

HUDの同期と7種類の往復を重点確認する場合は、同じディレクトリで実行します。

```bash
TEST_HUD_ONLY=1 npm test
```

| 変数 | 用途 |
| --- | --- |
| `TEST_SETTINGS` | 接続設定JSONのパス |
| `TEST_RESULT` | 結果JSONの出力先。既定は `.integration/results.json` |
| `TEST_LOCALE` | クライアント言語。既定は `ja_JP` |
| `TEST_DEBUG=1` | 受信パケットの詳細表示 |

自動クライアントはBedrock 1.26.51、プレイヤー名NativeOH、検証用XUID31001で接続します。失敗時の終了コードは1です。

食品の試験では空腹を確認し、使用入力を保持する状況を模して連続した使用パケットを送ります。難易度は実行前の値を記録し、通常の終了処理で元へ戻します。実機参加者の検証環境ではPeaceful・持ち物保持・敵の自然出現停止を設定しました。

## 結果の読み方

試験は移動応答だけでなく、Java側で確定した装備・個数・種類と、閉じた後のバニラ定義・ブロック状態を照合します。HUDはネイティブAUXとA/Bのtitle、個数、空状態、通知との干渉、定期更新を確認します。

自動クライアントはBedrock本体のJSON UI、アイコン描画、画面上のドラッグ制限を実行しません。実機では通常インベントリから直接移動し、ホットバー・体力・空腹と左のオフハンド枠、アイコンの切り替え、個数、空状態を確認します。道具の保持位置と向きは、通常画面とインベントリ背景を比較します。

[現行版の確認状況](validation.md)、[結果JSON](../tests/results)、[過去の試験履歴](testing-history.md)を参照してください。
