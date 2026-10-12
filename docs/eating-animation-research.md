# 食事アニメーションの調査・比較

調査対象は提供された **Eating Animation 1.1.5（Voxen）**。現行 GeyserNativeOffhand **0.2.23** はこのパックを使用していません。この資料は調査段階の記録です。その後の左手向け実装は[左手の食事モーション](left-eating-motion.md)を参照してください。

## 対象と再現方法

- ZIP: Eating_Animation.zip、205,605 bytes
- SHA-256: `dc2784cafbff473a6643330677da74babd4d834f752b60e03b3c43e7d51c42b8`
- resources モジュールのみ、最小エンジン 1.20.0、43種類の飲食物の attachable。
- 実ファイル317、PNG268（Default/Bare Bonesとパック画像を含む）。元ZIP、テクスチャは公開リポジトリに追加していません。
- [数値・ファイルハッシュ](research/eating-animation-audit.json)、[動きのグラフ](previews/eating-motion.svg)。

外部で保管したZIPに対して実行します。

```sh
python3 scripts/analyze-eating-animation.py /path/to/Eating_Animation.zip --output audit.json
```

スクリプトのサンプル値は、この版の式を読み解いた数値です。一般的なMoLang実行器ではありません。Blockbench 5.2.2にも元のgeometryと2つのanimationを読み込み、MoLangパーサーで第一人称・時刻を指定した7点を照合しました。モデル骨は consumable / consumable_animated。これはモデル定義と式の確認であり、Bedrock実機の投影・使用同期・ブレンドを検証した結果ではありません。

## 動き

常時適用する親骨の第一人称姿勢は、回転 [38, −120, −63] 度、位置 [−5.5, 23.5, −5]、倍率0.8。第三人称は別の固定姿勢と倍率0.55です。導入すると食事中だけでなく、通常保持の形・位置も変わります。

食事中は子骨を次のように動かします。位置はモデルのローカル単位で、画面上の上下左右とは一致しません。

| 経過秒 | 子骨回転 X / Z（度） | 子骨位置 X / Y / Z |
| --- | --- | --- |
| 0 | 0 / 0 | 0 / 0 / 0 |
| 0.125 | 16 / 48 | −6 / 1.5 / 0 |
| 0.25 | 32 / 96 | −12 / 3 / 0 |
| 0.3125 | 32 / 96 | −12 / 3 / 約2 |
| 0.375 | 32 / 96 | −12 / 3 / 約−0.017 |
| 0.4375 | 32 / 96 | −12 / 3 / 約−2 |
| 0.5 | 32 / 96 | −12 / 3 / 約0.017 |

最初の0.25秒は直線的に持ち上げ・回転し、その後はZ方向に振幅2、毎秒4回で揺れます。MoLangのsinは度数法です。状態遷移のblend_transitionは0.1秒。明示的なanimation_lengthはなく、上表は指定したq.anim_timeにおける式の値です。ループ時刻の実機挙動まで断定しません。

使用時間 / 最大使用時間の比率で、25%、50%、75%を境に通常画像から3段階の食べかけ画像・geometryへ切り替えます。最後の段階はis_using_itemも要求します。ゼロ除算の明示的なガードはありません。molang/consumable_frame.molangと同じ式はrender controller内に直接あり、調査した定義からこの補助ファイルへの参照はありません。

## バニラ・現行実装との違い

比較したMojang資料は [bedrock-samples commit 46ba6ea](https://github.com/Mojang/bedrock-samples/tree/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack) です。クライアント内部の全処理を公開サンプルだけから再現できるとは限りません。

| 項目 | 提供パック | 公開バニラ定義 | 現行拡張 |
| --- | --- | --- | --- |
| 動かすもの | 食べ物の子骨 | use_item_progressのrightarm | 左手専用の食事アニメーションなし |
| 開始 | 第一人称かつq.is_using_item | player controllerの使用進捗条件 | Java側の左手使用を処理 |
| 時間 | q.anim_time、item_in_use_duration比率 | startup / interval progress、main_hand使用時間 | 左手消費中の汎用USING_ITEMを抑制 |
| 食べかけ画像 | パック内の追加PNG | この比較対象に同等の段階画像定義なし | 追加画像なし |

公開バニラの [humanoid.animation.json](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/animations/humanoid.animation.json) の use_item_progress は右腕回転を、startup progressをS、interval progressをIとして **[−60S+11.25I, −22.5S+11.25I, −5.625S+11.25I]** とします。提供パックの食べ物移動とは別の骨・座標なので、値を直接比較して「同じ形」とは判定できません。公開player.entity.jsonの正規化時間もmain_hand基準です。

現行 [NativeUseRouter](../src/main/java/org/pexserver/nativeoffhand/NativeUseRouter.java) は、Javaメタデータの左手消費を検出するとUSING_ITEMをfalseにします。右手を食事動作させないためです。提供パックはその使用クエリを開始条件にするため、単に追加しても現行の左手食事に連動しません。

## 将来実装する場合の条件

1. 左手の使用開始・中断・完了と経過時間を、右手と区別してクライアントのcontrollerへ渡す必要があります。汎用USING_ITEMを戻すだけでは右手への影響を解決できません。
2. rootのslot-to-bone bindingはありますが、左右を区別する姿勢・反転条件はありません。左手モデルの位置と回転を別に確認します。
3. 食べかけの段階表示には追加画像が必要です。通常バニラ画像だけを使う動作と、段階画像を持つ動作は別の選択です。
4. 通常保持・インベントリ背景・第三人称、両手に食べ物がある場合、キャンセル時の復帰を検証します。使用クエリが両手で共有される場合の同時発火は、実機確認が必要です。

この調査段階では開始同期や左手専用アニメーションを実装していません。実機の再接続・食事確認は行わず、検証サーバーは停止したままです。
