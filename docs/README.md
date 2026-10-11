# ドキュメント

GeyserNativeOffhand 0.2.23の利用・開発資料です。概要と導入は[公開README](../README.md)を参照してください。

## 利用・開発ガイド

| 資料 | 内容 |
| --- | --- |
| [互換性](compatibility.md) | 検証した環境、表示・操作の制約 |
| [ビルド](building.md) | 依存JAR、ビルド方法、生成するパック |
| [実装の構成](architecture.md) | インベントリ、保持、使用、HUDの処理 |
| [確認状況](validation.md) | 現行版の実機結果と自動試験の範囲 |
| [統合テスト](testing.md) | 独立した検証サーバーの設定と試験の実行 |

## 詳細資料・調査

- [DoubleHandsの解析](doublehands-reference.md)
- [オフハンドHUDの変更記録](offhand-hud.md)
- [既存のバニラ資産を使うHUDの調査](hud-texture-research.md)
- [ネイティブAUXとA/B描画の修正](native-hud-aux-repair.md)
- [生成したネイティブAUX一覧](research/native-hud-aux-literals.json)
- [インベントリ内の道具のモデル・保持位置](ui-tool-attachables.md)
- [保持パケットの比較](native-equipment-view.md)
- [道具の描画プリセットの比較](tool-render-presets.md)

## 開発履歴

[開発・検証の記録](development-history.md)と[統合試験の開発履歴](testing-history.md)には、失敗した候補や当時の確認待ちの記述を残しています。現行版の合否は[確認状況](validation.md)で確認してください。生の試験結果は[tests/results](../tests/results)、モデル比較は[previews](previews)にあります。
