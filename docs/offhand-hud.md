# オフハンド HUD の修正

RP 0.2.3 は自動テストで同期を確認できましたが、ユーザーの実機画像で通常のホットバー・体力・空腹などが消える問題が確認されました。自動クライアントは JSON UI を実行しません。

0.2.3 は独自の nativeoffhand_hud_inject.json から hud.root_panel を登録していました。画面のルート定義を別のファイルで再登録する経路が通常の HUD を破壊した可能性があります。0.2.4 はこれを削除し、ユーザーの指示に従って元ファイルを取り込みます。

## 0.2.4 で原本と一致したファイル

DoubleHands 5.6.0 の作者表記は Areed Elahi。次のファイルは内容を変更せずコピーしています。

- ui/_ui_defs.json
- ui/hud_screen.json
- ui/doublehands_hud.json
- textures/ui/offhand_slot.png と textures/ui/empty.png
- textures/items/iso_renders/*.png の 1,674 枚

元の ui/hud_screen.json の modifications で HUD へ枠を追加します。元の image コントロールと画像を使い、前版の inventory_item_renderer と保存用 binding は含めません。

## Geyser 側の同期

元の TextureMap を、名前を minecraft: なしに揃えた properties に変換します。まずバニラの Bedrock の定義から名前を取得し、移動用 alias は使いません。Java 名での参照と元と同じ textures/items/<name> のフォールバックも用意します。

title は画像パス、subtitle は 2 個以上なら個数、1 個なら空文字。空なら textures/ui/empty を送ります。表示時間は 2 秒、装備・個数の変更時と、変化がない場合は 1 秒ごとに更新します。250ms ごとに状態を確認し、切断時にタスクを解除します。

元の DoubleHands UI は通常のタイトルと副題を隠します。この版ではそのチャンネルを専用に予約し、Java の title/subtitle/times/reset を受けても HUD を更新します。アクションバーは元の translator へ渡します。通常タイトルの表示との共存機能はこの版にはありません。

インベントリ移動・設置・Java 側の食事処理と、遅延インベントリ処理は維持します。元の Script API や行動パックは Geyser で実行しません。

## 実機確認

RP 0.2.4 を取得し、通常のホットバー・体力・空腹表示と、その左の枠・アイコン・個数を確認します。石・パン・斧の移動、消費による残数の更新、1 個で数字が消えること、空で枠が消えること、タッチボタンに重ならないことも確認します。

自動テストは Java の装備・消費・パケットと画像ファイルの存在を確認します。実機描画の合否には使いません。UI・画像の原本とのハッシュ一致も記録します。

0.2.4 の実機確認では、ユーザーから HUD が良好との報告を受けました。残っている斧・道具の開閉時の向きの問題は HUD とは別に記録します。0.2.5 は UI・枠・画像を変更せず、移動用の道具の登録データだけを補います。

## 0.2.14: アイコン画像の全削除

ユーザーが画像群の全削除と枠画像の維持を指示したため、`iso_renders` の 1,674 PNG、未使用になる `textures/ui/empty.png`、旧 `offhand-hud-textures.properties` を削除しました。パックの PNG は `textures/ui/offhand_slot.png` だけです。

`ui/_ui_defs.json`、`ui/hud_screen.json`、枠 PNG は前版と同じ内容です。元のルートへの追加方法を保ち、`doublehands_hud.offhand_slot_content` の画像コントロールだけを custom `inventory_item_renderer` に替えます。枠・個数・タッチ／クラシック配置を維持します。

title の形式は `(native runtime ID << 16) | (native data value & 0xffff)` の符号付き整数の文字列表現です。UI では `(#nativeoffhand_aux_string / 1)` を `#item_id_aux` に渡します。空は文字列 `0`、1 個以下の subtitle は空です。ネイティブの ID は Geyser のインベントリ alias 用の変換範囲の外で取得します。

パックは 50,834 バイトです。同期試験では実際に受信した item registry・保持アイテム・データ値と AUX を照合します。石、パン、赤い羊毛、りんご、斧、1 個、空、定期更新、通常通知との干渉、インベントリ用 alias の表示中も元の AUX が送られることを検証します。自動クライアントは JSON UI の数値変換とアイコン描画を実行しません。

RP 0.2.14 を取得して入り直し、通常のホットバー・体力・空腹が見えることと、左の枠に石・パン・斧などのアイコンと正しい個数が出ることを実機で確認します。空では枠が隠れ、インベントリを開閉しても出し入れできることを確認します。色・模様・ポーション・エンチャントなど、AUX だけでは表せないスタックの表示は別途確認が必要です。
