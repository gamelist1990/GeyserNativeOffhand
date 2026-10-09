# Bedrock 統合テスト

PrismarineJS の bedrock-protocol 3.60.1 を使い、実際の Paper / Geyser に接続します。Node.js 24 以上が必要です。

**新しい検証専用ワールドで実行してください。** テストは NativeOH のインベントリ、ゲームモード、位置を変更し、原点周辺に設置用の足場と試験ブロックを作成します。ホワイトリストを無効にします。運用中のサーバーや既存ワールドでは実行しないでください。

## サーバー

検証した組み合わせはリポジトリの README と results の JSON に記載しています。以下は同じ構成を用意する手順です。

1. 新しいディレクトリで Paper を起動し、生成された EULA を確認・承諾します。Paper、Geyser、Minecraft の配布条件に従って各 JAR を別途用意してください。
2. Paper の `plugins/` に対応する Geyser-Spigot を配置します。
3. 本拡張の JAR を `plugins/Geyser-Spigot/extensions/` に配置します。
4. `server.properties` を設定します。

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

5. 生成済みの Geyser 設定へ以下の値を反映します。設定全体をこの抜粋に置き換えないでください。

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
  enable-integrated-pack: false
advanced:
  java:
    use-direct-connection: false
  bedrock:
    validate-bedrock-login: false
```

これは署名を持たないローカルの自動クライアントを接続するための検証設定です。ループバックに限定し、インターネットへ公開しないでください。

6. Java 26 でサーバーを完全に再起動します。RakNet cookie とネイティブイベントループを無効にした構成で検証しました。

```bash
java -Xms512M -Xmx2G -DGeyser.RakSendCookie=false -DdisableNativeEventLoop=true -jar paper.jar nogui
```

7. リポジトリ直下へ `.integration/` を作り、`.integration/test-settings.json` に次の設定を保存します。このファイルは git で無視されます。

```json
{
  "bedrockPort": 19195,
  "rconPort": 25602,
  "rconPassword": "YOUR_LOCAL_TEST_PASSWORD"
}
```

## 実行

```bash
cd tests/bedrock
npm ci
npm test
```

接続先は 127.0.0.1、Bedrock 1.26.51、言語 ja_JP、プレイヤー名 NativeOH、検証用 XUID 31001 です。`TEST_SETTINGS` で別の設定ファイル、`TEST_RESULT` で結果ファイルを指定できます。既定の出力先は `.integration/results.json`。`TEST_DEBUG=1` で受信情報を表示します。`TEST_LOCALE=en_US` で英語の表示名を検証できます。

テストは実際の ItemStackRequest 応答、Java 側の装備と個数、ブロック、食事完了、PlayAnimation パケットと、それより先に届く開始プロパティを確認します。石の翻訳名・カスタム名の維持・盾のバニラ ID も検証します。範囲外の操作と不正な保持アイテム定義も検証します。失敗すると終了コード 1 です。テレポート後は移動で確認応答を送り、Paper が操作を受け付ける状態になってから使用します。

画面描画と、実機のインベントリにおけるドラッグ操作は自動化していません。実機では拡張を更新した後に再接続し、パックのダウンロード完了後に左手の食事と飲用の動きを確認してください。
