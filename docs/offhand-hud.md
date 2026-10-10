# オフハンド HUD

ユーザー提供の DoubleHands 5.6.0 の `ui_sync.js` と HUD を参照しました。ホットバー左に独立した枠・アイコン・個数を表示し、タッチ操作では位置をずらす動作を取り入れました。原本のスクリプト・UI・画像はコピーしていません。

Geyser の `NativeOffhandHud` が実際のオフハンドを読み、表示用の Item ID Aux と個数を送ります。アイコンにはゲーム画面の定義を使い、通常のインベントリが開いているときも移動専用の定義へ切り替えません。ID Aux は `(runtime ID << 16) | (metadata & 0xffff)`。HUD は標準の `inventory_item_renderer` で描画します。アイコン画像の固定一覧は必要ありません。染色・旗の柄・地図などの追加 NBT を HUD へ送る処理はこの版にはありません。

専用の title/subtitle データだけを保存する JSON UI を登録します。`hud_title_text` には専用データの表示を隠す binding を追加し、元のタイトルの controls とアニメーションを残します。独自の登録ファイルを使い、他パックの `ui/hud_screen.json` をファイル単位で置き換えません。枠は独自の 22×22 画像です。

初回と装備・個数の変更時に送信します。通常のインベントリ処理の直後に更新し、250ms ごとの確認で他の装備変更にも対応します。変化がなければ同じ title/subtitle を送り続けません。切断時は確認タスクを解除します。

通常の Java タイトルを受けたら元の副題と表示時間を復元し、通知の終了まで HUD の送信を延期します。JSON UI に保存済みのアイコン・個数は残す設計です。通知中にアイテムを変えると個数表示も通知後に更新します。アクションバーは操作しません。Bedrock 内部や他拡張が Java translator を通さず title を送る場合、および別の HUD パックと同時に使う場合は追加の実機確認が必要です。

参照した JSON UI の仕様:

- [Item ID Aux と inventory_item_renderer](https://wiki.bedrock.dev/json-ui/json-ui-documentation)
- [文字列から数値への変換](https://wiki.bedrock.dev/json-ui/type-conversion) — `0 +` を使います。
- [専用データの保存](https://wiki.bedrock.dev/json-ui/preserve-title-texts) — マーカーを含むデータだけ更新します。

実機では通常のインベントリから石・パン・斧をオフハンドへ入れ、左端のアイコンと個数を確認します。設置・消費で数が減ること、1 個で数字が消えること、全部戻すと枠が消えること、通常のタイトルと HUD が共存することを確認してください。自動クライアントは JSON UI を実行せず、実機描画の合否は判定しません。

プレイヤーインベントリの遅延オープンは network-stack-latency の応答からも更新されます。0.2.3 ではこの callback をセッションのイベントループ内で実行し、移動用の定義が非同期経路で外れないようにしています。通常のインベントリ以外の latency 処理は元の translator へ直接渡します。
