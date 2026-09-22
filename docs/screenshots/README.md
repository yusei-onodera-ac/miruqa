# スクリーンショット(v0.5 UI・デモデータのみ)

記事・ピッチ用。すべて自作デモサイト(`http://127.0.0.1:8765/`)に対する実行結果で、
実在のサービス・個人情報は含まない。2026-09-21、Playwright(このリポジトリの既存依存)で撮影。

| ファイル | 画面 |
|---|---|
| `01-wizard-url.png` | 新規点検ウィザード ステップ1(URL・仕様書ドラッグ&ドロップ) |
| `02-wizard-plan-loading.png` | ステップ2(下見・項目書生成中のローディング) |
| `03-wizard-testcases.png` | ステップ3(テスト項目書。危険度バッジ・一括承認チェック) |
| `04-result-overview-exploratory-tab.png` | 結果画面のKPIカードと「探索的テスト」タブ |
| `05-result-testcases-tab.png` | 結果画面の「テスト項目(合否)」タブ |
| `06-result-spec-traceability-tab.png` | 結果画面の「仕様トレーサビリティ」タブ |
| `07-result-finding-drawer.png` | 指摘クリックで開く右ドロワー(期待/実際/仕様項目/再現手順/修正案) |
| `08-history.png` | 履歴一覧 |
| `09-error-worker-down.png` | ワーカー停止時のエラー画面(スタックトレースなし。評価③) |
| `10-run-worker-down-mid-poll.png` | 実行中にワーカーが落ちた場合の画面(自動再試行、崩れない) |

実データ: `runs/samples/run-0b84031e60/`(33件のテスト項目、22件の指摘、実測コスト$0.6072)。
