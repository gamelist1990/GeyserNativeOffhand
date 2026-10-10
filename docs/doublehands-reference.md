# DoubleHands 5.6.0 の参照記録

ユーザー提供の `DoubleHands-5.6.0-26.50+.mcaddon` を解析しました。manifest の作者表記は **Areed Elahi**。入力ファイルの SHA-256:

```text
0b6d3ddf9584617764c22c4ae8d8ab4442734091e1c5a978f835cc4b221c7dab
```

確認した構成:

- BP は `@minecraft/server` 2.3.0 と `@minecraft/server-ui` 2.1.0 を使うスクリプト。
- `offhand_switch.js` はインベントリと装備を取得し、バニラの種類を `replaceitem` または装備 API でオフハンドへ移す。スニーク、エモート、コマンドの切り替えを提供。
- 盾・地図・矢・トーテム・ロケットなど、データを安全に保持するための扱いを区別。
- `main.js` は Bedrock のブロック設置、バケツやロケット等の使用をスクリプトで処理し、必要な場面で組み込みの `animation.player.offhand_swing` を再生。
- 一部のアイテムには対象外の判定がある。全アイテムを通常のインベントリからオフハンドへドラッグ可能にする実装ではない。
- RP は主に HUD とアイコン。保持用の attachable、geometry、腕の補正、食品アニメーションの JSON は含まれていない。

新実装では、バニラのアイテム ID を保持に使う設計を参考にしました。Java サーバーでの実際の設置・消費は Java の OFF_HAND パケットで行い、DoubleHands の Bedrock Script API を Geyser 上で実行しません。通常のインベントリ移動の許可は Geyser 用の UI 定義で追加します。

入力のスクリプト、画像、UI JSON は本リポジトリにコピーしていません。以前の補正コードも削除し、新しい 4 つの Java クラスへ実装し直しました。独自の食品モーションはこの基準版に追加していません。
