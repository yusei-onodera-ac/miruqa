# サンプル実行(リプレイ用)

LLM/ネットなしでもデモできるように、実行結果をコミットしている(AC-8)。

## v0.5(仕様書駆動・新形式)

| runId | 内容 |
|---|---|
| `run-0b84031e60` | **実物・実LLM**(`google/gemini-3.5-flash`、OrcaRouter経由)。`demo-site/spec/spec.md`(20項目)を取り込み、下見で7画面・33件のテスト項目書を生成、全項目を承認して実行。**指摘22件**(送料不一致・特商法ページへのリンク欠落など、複数のTestCase横断でconfirmed多数)。**実測コスト$0.6072**、107ステップ。仕様トレーサビリティ(`coverage.specItems`: 20項目中16項目をカバー)・テスト項目の合否(`testResults`、33件)を含む新形式(`docs/contracts.md`のv0.5スキーマ: `planId`/`specIds`/`testResults`/`exploratory`/`coverage`。`goal`/`persona`/`mode`は無い)。この回の探索的テストはLLM呼び出しの復旧不能な失敗により0件で打ち切られた(`exploratory.note`にその旨を記録。品を落とさず安全側に倒れることの実例として残す。**別の実行では探索で8〜9件の気になった点を検出できることを確認済み**、詳細は`docs/STATUS.md`のM4)。Java層(`web/`)の新UIから、この実行をブラウザで見た目確認済み(スクリーンショットは`docs/STATUS-web.md`参照) |

Java層のウィザードから作成した場合、`run.planId`(`p-6838881f`)・`specIds`(`s-7b4b6be3`)に対応する
`runs/plans/`・`runs/specs/`のデータは`.gitignore`対象のため同梱していない(標準ライブラリの
`http.server`が動的に作るデータのため)。そのため、この`run.json`をJava層の実行結果画面で開くと、
テスト項目のタイトル・観点や仕様トレーサビリティの列は補完できず簡略表示になるが、指摘一覧・
テスト項目の合否・カバレッジ件数・コストなど`run.json`自体が持つ情報は問題なく表示される
(ワーカーの単一HTMLレポート`report.html`は完全に自己完結しており、この制約を受けない。AC-8はこちらで満たす)。

## v0.4まで(旧形式。ペルソナ・ゴールを含む。参考として残す)

| runId | mode | LLM | 内容 |
|---|---|---|---|
| `run-9409d6a593` | inspect | **実物・有料**(`google/gemini-3.5-flash`、OrcaRouter経由) | トップ→商品→カート→決済確認画面まで自律実行(16ステップ)。指摘7件と無料モデルより踏み込んだ検出(価格の過大請求・フォームのlabel未関連付け・ヘッダー導線の分かりにくさ等)。**コスト実測 $0.101592**。2026-09-20、APIキーのスコープ拡張後に取得 |
| `run-8fff06d407` | inspect | 実物・無料(`deepseek/deepseek-v4-flash-free`、コスト$0) | トップ→商品→カート→決済確認画面まで自律実行。LLM自身が価格不一致(High)・誤字「送量無料」・返品特約の不明示を検出。ルールベースの送料不一致検出と合わせて計4件。2026-09-20、WP0のAPIキー到着後に取得した最初の実LLM実行 |
| `run-a04bf5ce05` | inspect | 擬似(`fake/mock`) | 商品ページ閲覧→誤字報告→finish。ルールベースの横断チェック(送料不一致)も1件検出。配線検証用 |
| `run-5b303eeedc` | l4 | 擬似(`fake/mock`) | 決済画面でT-01(連打・二重送信)のテスト計画を提示→承認→rapid_click実行→二重注文(5件)を検出。配線検証用 |

`run-a04bf5ce05`・`run-5b303eeedc` は `ORCAROUTER_API_KEY` 未設定の段階で、スクリプトで用意した
擬似LLM応答を使ってループ・ブラウザ操作・ポリシーゲート・レポート生成の**配線**を検証したもの
(`resolvedModel: "fake/mock"`、コストはダミー値)。他2件は実際のOrcaRouter呼び出し。

## 無料 vs 有料の比較(実測。1回の実行のみ、厳密な比較にはbench/run_bench.pyの複数回実行が必要)
| | `deepseek-v4-flash-free`(無料) | `google/gemini-3.5-flash`(有料) |
|---|---|---|
| ステップ数 | 6 | 16 |
| 指摘件数 | 4 | 7 |
| コスト | $0 | $0.101592 |
| 傾向 | 明確な不整合(価格・誤字)を検出 | より細かい問題(アクセシビリティ、導線)まで検出。ステップ数が多い分、動作も丁寧 |

各ディレクトリの `report.html` は `report/html.py` の `build_report_html(run, runs_root)` で
`run.json` から生成したもの(`runs_root` はこの `samples/` ディレクトリ自体を渡す)。

再生成する場合:
```python
from pathlib import Path
from report.html import build_report_html, load_run
run_dir = Path("runs/samples/run-8fff06d407")
run = load_run(run_dir)
(run_dir / "report.html").write_text(build_report_html(run, Path("runs/samples")), encoding="utf-8")
```

## 分かったこと(実測。docs/design-decisions.md にも記録)
- この鍵(APIキー)のスコープは、現状 `orcarouter/free`(不安定)と `-free` サフィックス付きの直接モデルID
  (例 `deepseek/deepseek-v4-flash-free`)のみ許可。`orcarouter/auto`・`orcarouter/fusion*`・有料直接モデルは
  `model_access_denied`(403, `block_key_scope`)。コンソールでスコープを広げる必要がある。
- 無料モデルのレート制限に実際に到達した(`err_free_rate`、`retry_after_seconds` が27357秒=約7.6時間という
  非常に大きな値だったことを確認)。この場合、エージェントは待たずに `LLM_MAX_RETRIES` で見切りをつけて
  `abandoned` として穏やかに終了した(FR-30の設計通り)。
