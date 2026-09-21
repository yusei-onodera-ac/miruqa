# サービス概要書 — 機能と技術（網羅版）

対象: MiruQA。
作成: 2026-09-21。根拠: ワーカーの作業ツリー（`dev/worker`、最新コミット 3a938e6 付近）のコード・`docs/design-decisions.md`・`docs/contracts.md` を読んで確認した内容。
**記号**: ✅ 実装済みで、動作を確認した ／ ▲ 一部のみ、または限定的 ／ ☐ 未実装（v0.6 で予定を含む）。
**注意**: 数字はすべて、リポジトリに記録された実測値。測っていない数字は載せていない。

---

## 1. 何のサービスか
- **一言**: 仕様書を渡すと、AIが人間のテスト担当と同じ手順（項目書の作成 → 実施 → 不具合票 → 報告）でWebサイトを点検し、「仕様どおりか」を証拠つきで判定する。連打などの想定外の操作も、承認のもとで実際に試す。
- **キャッチコピー**: 「言わずに去るユーザーの代わりに、先に使ってみるAI」。
- **解決する課題**: 重大な不具合は問い合わせで報告されるが、小さな不具合や使いにくさは報告されず、ユーザーは黙って離れる。点検（QA）は属人的で後回しになる。
- **大会**: AI HACK 2026 第2回（東京）。ピッチ 2026-09-23 10:00。テーマ「業務を自立化するAIエージェント」。
- **対象範囲（決定 2026-09-21）**: **開発中・リリース前のテスト環境を、その所有者が点検するサービス**。本番サイトの診断、第三者のサイトへの使用は、対象外（禁止）。技術面の裏づけ: 所有確認済みのドメインだけ／テスト環境の宣言（同意を記録）／能動テストは承認済みの計画・テスト環境フラグ・回数と間隔の上限つき。想定するテスト環境は、公開ステージング（ベーシック認証あり）。社内・VPN内のサイトは、クラウド版からは届かないため対象外（客の環境に置く形は、ロードマップ）。
- **現在の位置づけ**: ハッカソン用のプロトタイプ（v0.5 完了）。**販売できる状態ではない**（認証・課金・DB・所有確認などは未実装）。v0.6 で、1社が使える規模の土台を作る予定（第14章）。

## 2. 全体構成
```
[ブラウザ（利用者）]
      │ HTTP
[Java層  web/  Spring Boot 4.0.0 / Java 21 / Thymeleaf]   ← AIを直接呼ばない。APIキーを持たない
      │ HTTP（既定 http://127.0.0.1:8770）
[ワーカー  agent/  Python 3.9 / 標準 http.server]
      ├─ Playwright（手元の Google Chrome）── 点検対象サイト（許可ドメインのみ）
      ├─ ポリシーゲート（自前） … すべての操作を実行前に評価
      ├─ 個人情報マスク … AIに送る前
      └─ LLM層（openai SDK, OpenAI互換）
             │ https://api.orcarouter.ai/v1
        [OrcaRouter] ─→ 各モデル（既定 google/gemini-3.5-flash）
[台帳 runs/ledger.jsonl]  全LLM呼び出しのコスト・モデル・トークンを記録
[デモサイト demo-site/  標準 http.server、メモリ上の状態、仕込み不具合つき]
```
- **2層の理由**: ワーカー（ブラウザ操作・AI）と、Webアプリ層（画面・承認・レポート表示）を分け、境界をデータ契約とワーカーAPIに固定する。Java層は、将来のサービス化（認証・課金・DB）の受け皿になる。
- 状態は、現状すべて**ファイル（`runs/`）とメモリ**。DBなし。単一プロセス。

## 3. 利用の流れ
| # | 画面 | 利用者がすること | システムがすること |
|---|---|---|---|
| 1 | 新規点検（`/`） | 対象URLを入力。仕様書を添付 | 許可ドメイン外は拒否 |
| 2 | プラン（`/plans/{id}`） | 内容を確認 | 事前調査（リンクを辿る）でサイトマップを作り、仕様項目 × 観点から**テスト項目書**を生成 |
| 3 | 承認 | 項目のON/OFF。危険な項目（連打など）を含む場合は一括承認 | 承認済みの項目だけが実行対象になる |
| 4 | 実行（`/runs/{id}`） | 見守る。実行中の承認待ちに応答 | 項目書を実行 → 探索的テスト。進捗・合否・コストをポーリングで更新 |
| 5 | 結果 | 不具合票・カバレッジ・コストを見る。CSV・HTMLで書き出し | 合否は、コードの根拠を主に判定 |
| 6 | 履歴（`/history`） | 過去の実行を見る | — |
- コスト上限で項目書の生成が途中で止まった場合、「続きを生成する」ボタンで続行できる（`POST /api/plans/{id}/resume`）。

## 4. 機能一覧

### 4.1 仕様書の取り込み（`agent/specs.py`）
| 機能 | 状態 | 内容 |
|---|---|---|
| 対応形式 | ✅ | txt／md／docx／PDF（テキストPDF）。PDFはページ単位、docxは段落スタイル、mdは見出し、txtは番号見出しで、**コード側で**節に分割 |
| 項目への分解 | ✅ | AI（`purpose=spec-extract`）が、各節を `SpecItem`（id・text・section・page・kind）に分解。`tool_choice` で構造化出力を強制。長い仕様書は分割処理（`SPEC_MAX_CHUNKS=40`） |
| 個人情報マスク | ✅ | AIに送る前に、仕様書テキストをマスク |
| 制限 | ▲ | **スキャン画像のPDF（OCR）は非対応**。表の一部が読めない場合は `warnings` に記録。PDF読み取りのAI抽出は、キー障害の時期があり、実LLMでの確認が限定的（STATUS参照） |

### 4.2 事前調査とテスト項目書の生成（`agent/planning.py`）
| 機能 | 状態 | 内容 |
|---|---|---|
| サイトマップ | ✅ | Playwrightでリンクを辿る（**AI不使用**。最大12ページ、`RECON_MAX_PAGES`）。画面の種類（top／list／detail／form／cart／checkout／static）は、URLパターンとフォームの有無で判定 |
| 項目書の生成 | ✅ | 仕様項目 × 観点から `TestCase` を生成（`purpose=testcase-gen`、最大40件、`TESTCASE_MAX_CASES`）。各項目に、観点・対象・仕様参照・前提・手順・期待結果・危険度・マクロ（任意）を持つ |
| 危険度の判定 | ✅ | P-SEC は常に `needs_approval`。P-FLOW は、決済・注文確定に関わるものだけ `needs_approval` |
| 中断と再開 | ✅ | コスト上限で打ち切られたプランを、続きから生成できる（`resume_plan`） |
| 見積もり | ▲ | プランに `estimate`（件数のみ。時間・コストは、測っていないため `null`） |

### 4.3 観点カタログ（`agent/perspectives.py`）
| ID | 観点 | 危険度 |
|---|---|---|
| P-FUNC | 機能（正常系・異常系・境界値・同値分割） | 通常 |
| P-FLOW | 画面遷移・状態遷移（戻る・リロード・手順スキップ・直接アクセス） | 通常／条件により要承認 |
| P-INPUT | 入力検証（必須・桁数・文字種・空欄・超長・絵文字） | 通常 |
| P-TEXT | 表示・文言（誤字・表記ゆれ・仕様との文言差・ページ間の不一致） | 通常 |
| P-LINK | リンク・画像（リンク切れ、alt欠落） | 通常 |
| P-UX | ユーザビリティ（ペルソナなし。ヒューリスティックのチェックリスト） | 通常 |
| P-A11Y | アクセシビリティ（alt、コントラスト、ラベル、キーボード） | 通常 |
| P-SEC | セキュリティ・堅牢性（連打、改ざん、エスケープ、エラー露出、連番ID） | **要承認** |
| P-PERF | 表示速度 | 通常 |
| P-RESP | レスポンシブ | 通常 |
- 観点は、データ（Pythonの辞書）として持つ。P-UX・P-A11Y は、チェックリストの充実が**一部**（▲）。

### 4.4 実行エンジン（`agent/loop.py`, `agent/browser.py`, `agent/judging.py`）
| 機能 | 状態 | 内容 |
|---|---|---|
| 項目書の実行 | ✅ | 項目ごとに、AIがツールを選んで操作し、期待結果と比べる。1項目あたり最大8ターン（`TESTCASE_MAX_STEPS`）。詰まり検知（同じ状態が3回）あり |
| ブラウザ操作ツール | ✅ | `navigate`／`click`／`select_option`／`scroll`／`get_page_state`／`finish_test_case`（結果の申告） |
| 能動テスト（マクロ） | ✅ | `rapid_click`（連打。**最大10回・100ms間隔**）／`navigate_direct`（直接アクセス）／`fill_abnormal`（異常入力）／`override_param`（hidden・パラメータの改ざん） |
| 観察の形 | ✅ | 画像ではなく、**テキスト＋操作可能な要素の一覧**（画像非対応の安いモデルでも動く）。スクショは、証拠として保存 |
| コードによる合否判定 | ✅ | `judging.py`: 二重送信（注文件数）、手順スキップ、価格・数量の改ざん、エラー露出（スタックトレース）、未エスケープ反射（XSS）、仕様の金額と表示の一致。テスト環境の状態確認（`/__test/state`）、リクエスト数、画面の差分が根拠。**AIの感想だけでは「確定」にしない**（根拠が弱い場合は `needs_review`） |
| 前提の整備 | ✅ | カートが空でないと到達できない画面のため、商品追加を自動で行う。価格改ざん系は、実行前にCookieを消してカートを作り直す |
| 固定手順のコード実行 | ▲ | 手順が決まっている項目（連打・直接アクセス）は、AIなしで実行できる（`AGENT_MACRO_CODE_FASTPATH`、**既定オフ**）。2件の実測で、LLM呼び出し・コスト100%減（判定は一致）。対象は限定的 |
| 入力の圧縮 | ▲ | 古いツール結果の圧縮（`AGENT_COMPACT_TOOL_RESULTS`、**既定オフ**）。実測では、効果なし |
| 操作列の記録 | ▲ | 実行した操作を `recordedActions` として記録する（再生は未実装） |

### 4.5 探索的テスト
| 機能 | 状態 | 内容 |
|---|---|---|
| チャーター | ✅ | AIが探索の方針を1文で提案（`purpose=charter`） |
| 探索の実行 | ✅ | 項目書の実施後に、上限（30ステップ、`EXPLORATORY_MAX_STEPS`）まで、未訪問の画面・操作を優先して自由に操作。**P-SECのマクロは一切使わない**（コード上で、拒否される） |
| 気になった点の記録 | ▲ | 根拠つきで `suspicions` に記録。実LLMの通し（最大サンプル）では、0件だった（成果は限定的） |

### 4.6 不具合票・カバレッジ・レポート（`agent/loop.py`, `report/html.py`）
| 機能 | 状態 | 内容 |
|---|---|---|
| 不具合票 | ✅ | 観点・重大度（High/Med/Low）・確度（confirmed／needs_review）・種類（deviation／out_of_spec／unreachable／robustness／text／ux）・仕様参照・期待と実際・スクショ・再現手順 |
| 重複の統合 | ▲ | **完全一致するものだけ**を1件に統合（件数を表示）。似ているが別の文言のものは、統合されない |
| カバレッジ | ✅ | 仕様項目（全体・確認済み・合格・不合格・未確認とその理由）、観点別件数、画面数 |
| レポート | ✅ | 単一のHTML（カバレッジ・指摘一覧・テスト項目の実施結果・探索・モデル別コスト・ポリシー判定ログ・実行タイムライン）。マウス軌跡のSVG、スクショの埋め込み |
| 書き出し | ✅ | CSV（`/runs/{id}/export.csv`）、HTML |
| 再テスト差分 | ☐ | 未実装（契約に `compareTo` のみ） |

### 4.7 Java層の画面（`web/`）
| 画面・機能 | 状態 |
|---|---|
| 新規点検（URL・仕様書アップロード。許可ドメイン外はエラー） | ✅ |
| プラン表示・項目書の確認・承認・「続きを生成」 | ✅ |
| 実行画面（ポーリングで、進捗・合否・気になった点・コスト） | ✅ |
| 結果画面（KPI・タブ・詳細ドロワー・目的別コスト内訳・予算超過バナー） | ✅ |
| 履歴一覧 | ✅ |
| ワーカーの停止・タイムアウト・400の表示と再試行 | ✅ |
| CSV書き出し、レポートの埋め込み | ✅ |
| ログイン・組織・課金・設定・ダッシュボード・公開ページ | ☐（v0.6） |
- 画面は、Thymeleaf＋素のJavaScript＋自前CSS。**CDNを使わない**（オフラインでも動く）。デザインの全面改良は v0.6（AIっぽさを出さない方針）。

## 5. AI（LLM）層 — OrcaRouter経由（`agent/llm.py`, `agent/ledger.py`）
| 項目 | 内容 |
|---|---|
| ゲートウェイ | OrcaRouter（OpenAI互換、`https://api.orcarouter.ai/v1`）。**すべてのLLM呼び出しが経由**。プロバイダーへの直接呼び出しはしない |
| モデル指定 | 環境変数 `ORCA_MODEL`（コードにモデルIDを固定しない）。現状の既定運用は `google/gemini-3.5-flash` |
| 呼び出し方式 | openai SDK、**非ストリーミング**、ツール呼び出しと構造化出力。SDKの自動再試行は使わない（`max_retries=0`）。自前で制御 |
| 回復 | 429（`Retry-After` を守る、最大20秒）、5xx、タイムアウト（30秒）、不正なJSON、無料枠の拒否（`err_free_rate`／`err_free_prompt_cap`／`err_free_access_denied`）に、最大4回の再試行。**2段階のフォールバック**（主モデルが失敗したら、モデルを切り替えて別のリクエストを送る＋OrcaRouterの `extra_body` 連鎖）。無料→有料の自前切替 |
| 予算 | 呼び出し前に、1実行あたり（既定$0.5）・1日あたり（`.env` の値）の上限を確認し、超えたら**APIを呼ばず**、部分結果で終了する |
| 台帳 | 全呼び出し（成功・失敗）を、`runs/ledger.jsonl` に追記式で記録: 目的・モデル（実際に選ばれたモデル）・トークン・即時コスト・確定コスト（`GET /v1/generation`、失敗許容）・遅延・再試行・フォールバック段階。確定額の**追記行は別行**になるため、単純合計は二重計上になる（集計は `python -m agent.cost_report`） |
| 障害注入 | `FAULT_INJECTION=1`（429／5xx／タイムアウトを、確率で注入）。フォールバックの実演に使う |
| 目的（purpose） | `spec-extract`／`recon`／`testcase-gen`／`exec`／`judge`／`charter`／`exploratory`／`verify` |
| 個人情報マスク | `agent/masking.py`: AIに送る前に、メール・電話・氏名・住所（正規表現）を `[EMAIL]` などに置換。**完全ではない**（デモのテストデータ用。実在の個人情報の検出は保証しない） |
| OrcaRouterの機能 | Firewall評価API、Guardrails は**未接続**（自前のポリシーゲートとマスクで代替。隠さず、記録に残す）。スコープ付きAPIキーは、コンソール側の設定（人が管理） |

## 6. 安全・統制
| 機能 | 状態 | 内容 |
|---|---|---|
| 許可ドメイン | ✅ | `ALLOWED_HOSTS`（既定 `127.0.0.1:8765`）。許可外への遷移は、実行前に遮断（`page.route`）。デモサイトと、自分たちのサイトだけを対象にする方針 |
| ポリシーゲート | ✅ | `agent/policy.py`: すべての操作を、実行前に評価。判定は allow／deny／pending_approval など。判定ログを、レポートに載せる。Firewall評価APIは未接続（設定があれば、ベストエフォートで呼ぶ） |
| 承認 | ✅ | `TestCase.risk=needs_approval` は、`TEST_MODE=1` かつ承認済みでないと実行されない。AIは「承認済み」と自己申告できない。注文確定・送信のキーワードのボタンは、実行前に承認待ち |
| 能動テストの上限 | ✅ | 1テストあたり最大10回・100ms間隔（`L4_MAX_COUNT`／`L4_MIN_INTERVAL_MS`） |
| 探索中の制限 | ✅ | P-SECのマクロ4種は、探索フェーズでは、コードで拒否される |
| 隠し指示 | ✅ | デモサイトの `/trouble/injection` に、隠し指示と許可外リンクを置き、承認なしの危険操作が実行されないことを確認 |
| 秘密情報 | ✅ | `.env` はGit管理外。`.env.example` のみ。レポート・ログに出さない |
| 所有確認・同意・SSRF対策 | ☐ | 未実装（v0.6）。現状、UIの「ドメイン所有確認」は模擬 |

## 7. コスト管理
- **台帳**（第5章）＋**集計コマンド** `python -m agent.cost_report`（目的別・モデル別・日別。平均の入力・出力トークン）。
- **API**: `GET /api/cost/summary`、`GET /api/runs/{id}/cost`、`GET /api/plans/{id}/cost`。Java層の結果画面に、目的別の内訳を表示。
- **上限**: `LLM_BUDGET_PER_RUN_USD`（既定0.5）、`LLM_BUDGET_PER_DAY_USD`（既定2.0。実際の値は `.env`）。実行単位の `AGENT_MAX_COST_USD`（既定1.0）。
- **コスト構造の実測**（第11章）。

## 8. データ契約（`docs/contracts.md` が正）
`Action`／`PolicyVerdict`／`LlmCall`／`Spec`（`SpecItem`）／`Plan`（`siteMap`・`testCases`・`estimate`・`coverageForecast`・`budgetStatus`）／`TestCase`／`TestResult`（`recordedActions` を含む）／`ExploratorySession`（`suspicions`）／`Finding`（`origin`・`kind`・`specRef`・`expected`・`actual`）／`Coverage`／`Run`。旧形式（goal／persona／mode／lens）は廃止。旧形式のサンプルは、別ディレクトリに退避。

## 9. API
**ワーカーAPI**（既定 `http://127.0.0.1:8770`。認証なし。ローカルのみ）
| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/health` | 稼働確認 |
| POST | `/api/specs` | 仕様書アップロード → `Spec` |
| GET | `/api/specs/{id}` | 仕様書の取得 |
| POST | `/api/plans` | 事前調査＋項目書生成（非同期） |
| GET | `/api/plans/{id}`／`/cost` | プラン／コスト |
| POST | `/api/plans/{id}/resume` | 打ち切られた生成の続き |
| POST | `/api/plans/{id}/approve` | 項目の承認（危険な項目は一括） |
| POST | `/api/runs` | 実行開始（`{planId}`。旧形状は400） |
| GET | `/api/runs`／`/{id}`／`/{id}/cost` | 一覧／詳細／コスト |
| GET | `/api/runs/{id}/report`／`/export` | HTMLレポート／CSV等 |
| GET | `/api/approvals` | 承認待ちの一覧 |
| POST | `/api/approvals/{id}` | 承認・却下 |
| GET | `/api/cost/summary` | 全体のコスト集計 |

**Java層のルート**: `GET /`、`POST /jobs`、`GET /plans/{id}`（`/status.json`）、`POST /plans/{id}/approve`、`POST /plans/{id}/resume`、`GET /specs/{id}.json`、`GET /runs/{id}`（`/status.json`、`/report`、`/export.csv`）、`POST /runs/{id}/approvals/{approvalId}`、`GET /history`。

**CLI**: `python -m agent.cli`（承認をターミナルで受け付ける `--approve-cli` あり）、`python -m agent.specs`、`python -m agent.planning`、`python -m agent.cost_report`、`bench/run_bench.py`。

## 10. デモサイトと仕込み不具合（`demo-site/server.py`）
標準の `http.server` のみ。状態はメモリ上。注文・問い合わせの処理は**擬似**（メール送信・決済はしない）。`TEST_MODE=1` で、`/__test/state`（注文件数などの状態確認）が有効。
- **画面**: `/`、`/products/1`、`/cart`、`/checkout`、`/complete`、`/legal/tokushoho`、`/privacy`、`/contact`、`/contact-safe`（**対照ケース**: 二重送信対策済み）、`/orders/{id}`、`/trouble`（ポップアップ・遅いページ・500・リダイレクトループ・巨大ページ・隠し指示）。
- **仕込み**: ①誤字 ②表記ゆれ ③価格不一致 ④送料不一致（特商法＝800円、決済＝無料）⑤導線の詰まり ⑥アクセシビリティ ⑦特商法の欠落・リンクなし ⑧同意チェックなし ⑨二重注文 ⑩手順スキップ ⑪価格改ざん ⑫数量の異常値 ⑬未エスケープ反射 ⑭エラー露出。予備: 連番の注文ID、セキュリティヘッダーなし。探索専用の隠れた不具合（#15。項目書には載せない）。
- **デモ用仕様書**: `demo-site/spec/`（md／txt／docx／PDF）。仕込みに対応する仕様項目を含む（例: 「送料は全国一律800円」）。

## 11. 実測結果（記録のあるもの）
**実LLMの通し**（`runs/samples/run-0b84031e60`、デモサイト、`gemini-3.5-flash`）: 107呼び出し、532秒、**$0.607**。テスト項目33件（不合格13・合格12・要確認8）、不具合票22件（確定7、要確認15。同じ内容の重複あり）、仕様項目20件のうち16件を確認。**注意**: 実行の途中でAPIキーが使えなくなり、一部の項目（二重注文・価格改ざんなど）が、最後まで検証できていない。

**検出率**（仕込み14件、項目書10件、3構成とも同じ結果）: L1〜L3 **6/8**、能動テスト **3/6**、誤検知0。**目標（能動テスト4/6）に未達。**

**ルーター比較**（同じ10項目、各1回）
| 構成 | コスト | 所要時間 | 合否一致率（直指定基準） |
|---|---|---|---|
| 直指定 `gemini-3.5-flash` | $0.345 | 191秒 | — |
| Named Router `site-inspector`（セッション固定オフ） | $0.376 | 341秒 | 90% |
| `orcarouter/auto` | $0.356 | 319秒 | 90% |
| Named Router（セッション固定オン） | $0.387 | 404秒 | 100%（非決定性の範囲） |
- **どの構成でも、呼び出しの100%が `gemini-3.5-flash` に振り分けられた**（難易度の判定が、長さやツール使用などの表面的な特徴だけで決まり、このアプリの呼び出しは常に「難しい」側に出るため）。**節約にならず、遅延は増えた。** 直指定で十分、というのが実測結果。
- セッション固定オンで、プロンプトキャッシュのヒット率は約25.7%（オフは未計測）。コスト・時間は改善しなかった。

**コスト構造**: 1呼び出しあたり約$0.005。1回の入力の内訳（概算のトークン数）は、ツール定義 約1,577、実行のシステムプロンプト 約754、ページ状態 約325（**固定費であるツール定義が最大**）。
**削減の手法**: 入力の圧縮は、効果なし（コスト+1.4%、呼び出し35→41回）。**固定手順のコード実行は、対象2件でLLM呼び出し・コストが100%減**（判定は一致）。
**別ベンダーへのフォールバック**: 主モデルを意図的に失敗させ、`openai/gpt-4o` へ切り替わって**3/3完走**（1回あたり約1.66秒、$0.000046）。`X-Orca-Fallback-Model` ヘッダーは付かない（アプリ側の切替のため。実際のモデル名の変化で確認）。
**コスト合計**: 今日の実効コストの合計は、約$2.2（ワーカーの `cost_report` の値。修正前の実行分を含む）。

## 12. 技術スタック
| 領域 | 内容 |
|---|---|
| ワーカー | Python 3.9、標準 `http.server`、Playwright（手元の Google Chrome を `channel="chrome"` で使用）、`openai`（>=1.0）、`httpx`、`python-dotenv`、`pyyaml`、`python-docx`、`pypdf`。開発時のみ: `reportlab`（デモ仕様書のPDF生成）、`tiktoken`（トークン数の近似計測） |
| Java層 | Java 21（実行環境は Java 25 Temurin）、Spring Boot 4.0.0、Maven、Thymeleaf、`jackson-databind`（Jackson 2。WorkerClient用）。`WorkerClient` は、JDK標準の `HttpClient` で実装（Spring Boot 4 の自動設定との相性のため） |
| AI | OrcaRouter（OpenAI互換）。モデルは設定で切替（既定 `google/gemini-3.5-flash`） |
| 保管 | ファイル（`runs/<runId>/run.json`、スクショ、`runs/plans/`、`runs/specs/`、`runs/ledger.jsonl`）。DBなし |
| デモサイト | Python 標準 `http.server`（メモリ上の状態） |
| ポート | デモサイト 8765、ワーカー 8770、Java層 8080（Springの既定） |

## 13. ディレクトリ構成（`dev/worker`）
```
agent/        ワーカー本体（server, loop, planning, specs, judging, policy, browser, llm, ledger, masking, perspectives, config, cost_report, cli）
checks/       ルールベースの安全網（rules.py。送料不一致など）
report/       レポート生成（html.py。CSV書き出しを含む）
bench/        ベンチマーク（run_bench.py、results/*.json）
demo-site/    デモサイト（server.py、spec/ に仕様書）
web/          Java層（Spring Boot。controller, worker クライアント, templates, static）
runs/         実行結果（runs/samples/ のみコミット）
docs/         要件・契約・設計判断・進捗・変更指示書
reference/    Day1の元プロジェクトの移植元（移植済み）
```

## 14. 設定（環境変数。`.env`。値はコミットしない）
| 分類 | 変数（既定値） |
|---|---|
| OrcaRouter | `ORCAROUTER_BASE_URL`、`ORCAROUTER_API_KEY`、`ORCA_MODEL`（コード既定 `orcarouter/auto`。運用では `.env` で指定）、`ORCA_FALLBACK_MODELS`、`ORCA_PAID_FALLBACK_MODEL`、`ORCA_FIREWALL_KEY` |
| 安全 | `ALLOWED_HOSTS`（`127.0.0.1:8765`）、`TEST_MODE`（0）、`L4_MAX_COUNT`（10）、`L4_MIN_INTERVAL_MS`（100） |
| エージェント | `AGENT_MAX_STEPS`（40）、`AGENT_MAX_DURATION_SEC`（600）、`AGENT_MAX_COST_USD`（1.0）、`AGENT_STEP_TIMEOUT_SEC`（30）、`AGENT_STUCK_THRESHOLD`（3）、`AGENT_HEADLESS`（1）、`AGENT_MACRO_CODE_FASTPATH`（0）、`AGENT_COMPACT_TOOL_RESULTS`（0） |
| LLM | `LLM_MAX_RETRIES`（4）、`LLM_RETRY_BASE_WAIT_SEC`（2）、`LLM_MAX_RETRY_AFTER_SEC`（20）、`LLM_TIMEOUT_SEC`（30） |
| 生成 | `SPEC_MAX_CHUNKS`（40）、`RECON_MAX_PAGES`（12）、`TESTCASE_MAX_CASES`（40）、`TESTCASE_MAX_STEPS`（8）、`EXPLORATORY_MAX_STEPS`（30） |
| 予算 | `LLM_BUDGET_PER_RUN_USD`（0.5）、`LLM_BUDGET_PER_DAY_USD`（2.0） |
| 障害注入 | `FAULT_INJECTION`（0）、`FAULT_INJECTION_TYPES`（429,5xx,timeout）、`FAULT_INJECTION_RATE`（0.3） |
| サーバー | `WORKER_HOST`（127.0.0.1）、`WORKER_PORT`（8770）。Java層: `WORKER_BASE_URL` |
- **環境変数は起動時に一度だけ読まれる。** `.env` を変更したら、ワーカーの再起動が必要。

## 15. 起動方法（ローカル）
```bash
# 準備
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env            # ORCAROUTER_API_KEY などを設定（コミットしない）

# 1) デモサイト
TEST_MODE=1 python3 demo-site/server.py --port 8765
# 2) ワーカー
python3 -m agent.server
# 3) Java層
cd web && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn spring-boot:run
# ブラウザで http://localhost:8080/
```

## 16. 制約・未実装・既知の課題（正直な一覧）
- **販売できる状態ではない**: 認証・組織・課金・DB・所有確認（模擬）・規約・同意・SSRF対策・実行環境の分離・秘密情報の暗号化・データ削除とバックアップは、未実装（v0.6）。ワーカーAPIは認証なし（ローカルのみ）。
- **検出の限界**: 仕込み14件中9件（L4は3/6）。重複の統合は完全一致のみ。探索的テストの成果は、限定的。ログインが必要な画面は非対応。単一ページのSPA・iframe・モーダルの対応は確認していない。
- **OrcaRouter**: Named Router／autoでコストは下がらなかった（実測）。Firewall評価API・Guardrails は未接続（自前で代替）。
- **AIの限界**: 実行ごとに、AIの進め方がばらつく（非決定的）。合否の一致率90〜100%。
- **個人情報マスク**: 正規表現ベースで不完全。
- **仕様書**: OCR非対応。表・図の読み取りは限定的。曖昧・矛盾した仕様は、`needs_review` に倒す。
- **テスト**: 自動テストは、Java層の起動テスト1件のみ。Pythonの単体テストは、リポジトリに見当たらない。検証は、擬似応答（fake client）と、実LLMでの通しで行った。
- **再テスト差分・操作列の再生**: 未実装（記録の土台のみ）。
- **コストの記録**: 修正前のコスト記録に漏れがあった（仕様書抽出・項目書生成）。修正済み（`ledger.py`）。OrcaRouterの実際の使用額との突き合わせは、人が監視中。
- **法務**: 他人のサイトや本番環境に、無断で能動テストを行うと、不正アクセス禁止法・業務妨害のリスクがある。そのため、対象を「所有者本人の開発中・リリース前のテスト環境」に限定し、所有確認・テスト環境の宣言・承認・回数上限で技術的にも制限する。利用規約・プライバシーポリシーは案の段階で、法務の確認はしていない（商用化の前に専門家の確認が必要）。

## 17. これから（v0.6。1社が使える規模）
フェーズ P1〜P8 として定義し、開発時に計画を提出して進めた。
- P1 認証・DB・組織／プロジェクト・監査ログ ／ P2 所有確認・同意・能動テストの許可条件・SSRF対策・実行環境の分離 ／ P3 ジョブキュー（同時2件）・組織のコスト上限・使用量 ／ P4 料金プラン・上限の強制・請求書（内部記録）・`docs/pricing.md` ／ P5 秘密情報の暗号化・ログインが必要な画面・自己診断 ／ P6 品質（重複統合、価格改ざんの判定、検出率の3回計測、再テスト差分、操作列の再生、2つ目のデモサイト）／ P7 保存期間・削除・バックアップ・観測性 ／ P8 UIの全面改良（AIっぽさを出さない。実データのスクショと、開発者のレビュー）。
- 対象外（将来のロードマップ）: 招待・多段ロール・APIトークン・管理者画面・決済アダプタ・負荷試験・英語UI・ダークモード。

## 18. 関連文書
`docs/contracts.md`（データ契約・ワーカーAPI）／`docs/design-decisions.md`（設計判断ログ、実測値つき）／`docs/pricing.md`（料金プラン）／`docs/roadmap.md`（将来の拡張）。
