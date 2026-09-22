# サイト点検エージェント（仮称）— 実装ブリーフ v0.3（Claude Code向け）

AI HACK 2026（第2回・東京）の作品。ピッチは 2026-09-23（水）10:00〜16:00、東京会場（オフライン環境の可能性あり）。
このファイルは、実装を担当するClaude Codeが単独で動けるようにまとめた自己完結のブリーフ。リポジトリのルートに `CLAUDE.md` として置くことを想定している。
v0.3: 評価基準の変更（Day1）に合わせて、OrcaRouterの設計・コスト実測・エージェント自身の信頼性・安全を追加し、優先度を組み替えた。
v0.3.1: 既存プロジェクト（`/Users/onoderayusei/agent-ai`、Python 3.9）の流用を前提にした。実装は**新規リポジトリ**（推奨: `AI_HACK_2026`）で行い、既存の3ファイルだけを移植する（第5章の移植マップ）。
v0.4: **Java層（Spring Boot）もハッカソンで作る**（最小構成）。実装セッションは2つ（ワーカー／Java層）に分け、担当ディレクトリで衝突を避ける（第5a章）。

## 0. 役割分担と並行作業のルール
- **実装担当は2つのセッション**: ①ワーカー（Python: デモサイト、エージェント、チェック、レポート、リプレイ、OrcaRouter連携、計測）②Java層（`web/`: Spring Boot）。**自分の担当ディレクトリだけを編集する**（第5a章）。
- **既存プロジェクト `/Users/onoderayusei/agent-ai` は参照専用。変更しない。**（動く元のデモとして残す。`.env`・`.venv`・`logs/`・`workspace/` の中身は新リポジトリにコピーしない。`.env` の値を表示・コミットしない）
- **別セッション（要件・Notion・ピッチ担当）**: 要件定義（Notion）、ピッチ台本・スライド、競合・法務・事業性の整理、レビュー。**コードには触らない。**
- **人（開発者おのゆー）がやること**（Claude Codeにはできない）: OrcaRouterへの登録（GitHubサインイン）、クレジット3,000円分の受け取り、APIキー発行、コンソールでの Named Router・Guardrails・スコープ付きキーの作成。あなたは、その手順と設定値を `docs/orcarouter-notes.md` に書いて渡す。
- 要件の意味を変える判断は勝手にしない。曖昧・矛盾は `docs/QUESTIONS.md` に書いて、開発者に確認する。
- **同じ作業ツリーを2つのセッションで同時に編集しない。** 実装担当は専用ブランチ（または git worktree）で作業する。コミットは小さく、動く状態のときだけ `main` にマージする。
- **秘密情報（APIキー等）は絶対にコミットしない。** `.env` は git 管理外、`.env.example` だけ置く。

## 1. ゴールと評価基準
- 一言: URLとゴールを渡すと、AIエージェントが自律的にサイトを実際に操作して点検し、証拠付きレポートを出す。連打や想定外の操作も実際に試して堅牢性を確かめる（脆弱性診断）。危険な操作は実行前に統制され、承認済みのテスト計画の範囲でだけ実行される。
- 打ち出し: 「言わずに去るユーザーの代わりに、先に使ってみるAI」。ユーザーが報告してくれない小さな不具合・使いにくさ（声なき離脱の原因）を、先回りして見つける。
- **評価基準（主催の第2回基準）**: ①セキュリティ（情報漏洩・不正操作への対策が設計されているか）／②コストパフォーマンス（OrcaRouterを活用し、品質と利用コストを最適化できているか）／③信頼性・堅牢性（想定外の入力やエラーでも安定して処理・復旧できるか）／④自律性（状況を判断し、必要なツールを選んで遂行できるか）／⑤アイデア・独創性（新しさ＋サービス化できるか。事業性も含まれると運営が回答）。
- **OrcaRouter賞**（10万円・決勝進出チーム）: OrcaRouterの活用度を最重視。「入れておくだけ」より、**実測して設計に反映している**ことを見る。
- 重要な読み方: ③は「診断対象のサイト」ではなく、**このエージェント自身**が想定外の入力・エラー（LLMの失敗、変なページ、不正なJSONなど）で落ちず復旧できるか。L4（対象サイトへの連打など）は別の機能。
- 「測って出す」: コスト削減率などの数字は、必ず実測値だけを出す。測っていない数字は出さない。

## 2. 絶対条件（Non-negotiables）
1. **LLM呼び出しはすべてOrcaRouter経由。** OpenAI互換の `base_url=https://api.orcarouter.ai/v1`、キーは環境変数 `ORCAROUTER_API_KEY`（既存プロジェクトと同じ名前。値は agent-ai の `.env` に設定済みの可能性があるが未確認。新リポジトリでは `.env` を作り直し、`.env.example` だけコミットする）。プロバイダーへの直接呼び出しはしない。
2. **モデルIDをコードに固定しない。** アプリが知るのは Named Router 名（`orcarouter/<名前>`）だけ。モデルの切り替えは設定とルーター側で行う（自律性の評価点）。
3. **ストリーミングは使わない**（最初のバイト送信後はフォールバックできないため）。
4. **診断対象は許可ドメインのみ**（`ALLOWED_HOSTS`）。デモサイトと自分たちのサイトだけ。他社サイト、審査員・協賛企業（CyberACE、OrcaRouter）のサイトは対象にしない。許可外ホストへのナビゲーション・リクエストは実行前に遮断する。
5. **L4（能動テスト）は、承認済みのテスト計画があるときだけ実行する。** テスト環境フラグ（`TEST_MODE=1`）のある対象だけ。非破壊、無害なマーカー文字列のみ、回数・間隔に上限（1テストあたり最大10回・100ms間隔）。攻撃の連鎖、認証回避、データ持ち出しは実装しない。
6. **すべてのアクションは実行前にポリシーゲートを通す**（OrcaRouter Firewall の評価API、またはそれが使えない場合の自前 `PolicyGate`。同じインターフェース。自前で代替した場合は、それを隠さずレポート・ピッチで示す）。
7. **LLMに送る前に個人情報をマスクする**（こちら側でのマスク＋OrcaRouter Guardrails）。テストデータのみ使う。
8. **デモは通信・LLMが不調でも動く。** 保存済みの実行結果を再生する「リプレイモード」は必須。
9. 秘密情報・個人情報をコード、ログ、レポート、スクショに残さない。

## 3. OrcaRouterの仕様（ドキュメントとDay1資料で確認した範囲）
- エンドポイント: `https://api.orcarouter.ai/v1`（OpenAI互換。Chat Completions／Responses／Embeddings等）。SDKは `base_url` と `api_key` を変えるだけ。約200モデル（2026-09-17時点で198）。上流と同じ単価で、上乗せ0%。
- **モデル指定**: 直指定（例 `google/gemini-2.5-flash`）／`orcarouter/auto`（毎回、難易度を判定して選ぶ。最安でも最速とは限らない。観察してから固定する）／`orcarouter/free`（無料モデルのプールから選ぶ）／`fusion`系／**Named Router**（`orcarouter/<名前>`。自分で候補と振り分けを定義）。使えるモデルは `GET /v1/models` で確認する。
- **Named Router**（コンソールの Routing で作成）: Name（小文字・数字・`_`・`-`、1〜50文字）、Allowed models（グロブ）、Strategy（cheapest／quality／balanced／linucb／gated_adaptive／dsl）、Mundane models・Hard models（gated_adaptive用）、Default model、`prefer_free`（既定OFF。dsl戦略では無効）。**最初に決めること**: 直指定かautoか（迷ったらautoで観察→固定）／候補モデルを2〜4本に絞る（ワイルドカードは高額モデルまで入る）／Fallback（別ベンダー）を1本置く。
- **Fallback**: `extra_body={"models": [...最大5], "route": "fallback"}`。トリガーは 5xx・429・ネットワークエラー（タイムアウト等は記載なし）。実際に応答したモデルは `X-Orca-Fallback-Level`／`X-Orca-Fallback-Model`。無料モデルからは有料へ自動フォールバックせず、無料IDはチェーンの後ろに置けない → **無料枠の拒否は自前のコードで有料モデルへ切り替える。**
- **レスポンスヘッダー**: `X-Orca-Request-Id`、`X-Orca-Resolved-Model`（実際に選ばれたモデル）、`X-Orca-Router`、`X-Orca-Fallback-Level`、`X-Orca-Fallback-Model`、`X-Orca-Session-Tier`、`Retry-After`。
- **コスト**: リクエストに `X-OrcaRouter-Include-Cost: true` を付けると `usage.cost_usd` に即時の額が入る（ストリーミングでは最終フレーム）。**確定額は `GET /v1/generation?id=<X-Orca-Request-Id>` の `total_cost`（USD）が根拠。** 即時値とはずれることがある。
- **無料モデル**: `orcarouter/free` または `-free` サフィックス。Union Alpha（回転式。Day1資料には、テキスト＋画像入力、262Kコンテキスト、ツール呼び出し・構造化出力に対応とある。能力・可用性は予告なく変わる）。制限は、分・日あたりのリクエスト数、支払い額によるティア、低ティアのプロンプトトークン上限（非公開）。拒否は4種: 429 `err_free_rate`（`retry_after_seconds` あり＝待って再試行／なし＝上流タイムアウト）、400 `err_free_prompt_cap`（プロンプトを短縮。再試行不可）、429 `err_free_access_denied`（GitHub連携またはクレジット不足）。
- **レート制限**: 429＋`Retry-After`。OpenAI SDKは既定で `max_retries=2`（同じモデルへ再試行する）。**429は別プロバイダーのモデルへ逃がす方が安定する**（Day1資料）。
- **Guardrails**: ルール種別は keyword／regex／pii／max_chars／external／llm_judge／grounding。アクションは block／mask／flag（Day1資料では「5種」とあるので要確認）。入力・出力の両側に適用できる。ブロックは HTTP 400 `guardrail_blocked`（入力段のブロックは課金なし）。PII Shield は既定でマスク（email/phone/ssn → `[EMAIL]`等）。**氏名・住所は既定では検出されない** → カスタム正規表現（最大25）で補う。コンソールまたはAPIで作成し、APIキーの `guardrail_id` にアタッチ。`llm_judge` でプロンプトインジェクション検査ができる。
- **Firewall**（ツール呼び出しのポリシー）: 面は inbound／response／mcp／egress。判定は allow／audit／deny／sanitize／pending_approval／cap_cost。ルールは `priority`（昇順・最初にマッチしたものが判定）、`tool_name_glob`、`skill_name_glob`、`args_match`（JSONPath条件。演算子 eq／contains／regex／in／cidr_match／gt／lt）、`cap_cost_cents`、`default_verdict`。`deny` は 400 `firewall_blocked`、`pending_approval` は 400 `firewall_approval_pending`＋承認ID → 承認後、承認IDを `X-OrcaRouter-Firewall-Approval` ヘッダーに付けて **1回だけ** 再送できる（単一用途）。**Playwrightはゲートウェイを通らないため、実行前に `POST /api/v1/firewall/evaluate` を呼んで判定に従う**（専用のFirewall用キーが必要。**リクエスト形式の詳細は未確認 → WP0で確認**）。連打は「1回のマクロ操作（`rapid_click`）」として評価・承認する。
- Day1資料に記載があるが、ドキュメントで詳細を確認できていないもの: スコープ付きAPIキー（予算上限・有効期限）、OrcaReplay（実行の記録・再生・比較）、Request Logs（コンソール）、ゼロリテンション（プロンプトと出力は保持せず、学習にも使わない）。
- ドキュメントはAIに読ませやすい形式で取得できる: `https://docs.orcarouter.ai/llms.txt`（一覧）、各ページは `.md`。サポートはDiscord。

## 4. 技術方針
- **アーキテクチャ方針（決定 2026-09-20、v0.4で更新）**: 2層構成。**ワーカー**（ブラウザ操作エージェント。Python）と、**Webアプリ層**（`web/`。Java／Spring Boot）。**ハッカソンでも両方作る**（Java層は最小構成: ジョブ作成・実行状況・承認・レポート表示。認証・課金・DBは作らない）。Java層はLLMを直接呼ばず、ワーカーをHTTP（`docs/contracts.md` の「ワーカーAPI」）で呼ぶ。ワーカーはHTTP APIとCLIだけを持ち、UIは持たない（フォールバックとして、承認をCLIで受け付ける `--approve-cli` を用意）。2つのセッションは、データ契約とワーカーAPIだけを境界に並行開発する（Java側は、ワーカーのモックとサンプル `run.json` で先に進められる）。
- 言語: **Python 3.9**（既存資産を流用するため）。`match` 文や `X | Y` 型ヒントは使えない。Playwright（手元の Google Chrome を `channel="chrome"` で操作。ブラウザの追加ダウンロード不要）＋ `openai` SDK（OrcaRouter用）。依存は `requirements.txt` に明記する（既存は `playwright` が抜けている）。デモサイトも標準の `http.server` ベースで足りる（依存を増やさない）。
- Java層: **Java 17以上＋Spring Boot 3.x以降（Maven）**。この環境は Java 25（Temurin）と Maven 3.9 が入っている。Spring Boot のバージョンは、Java 25 で動く最新の安定版を最初に確認して決める。画面は Thymeleaf＋素のJavaScript（ポーリング）で足りる。DBなし。ワーカーのURLは設定 `WORKER_BASE_URL`（既定 `http://127.0.0.1:8770`）。
- 操作の構造化: OpenAI形式のツール呼び出し（`tools`）。レポートは構造化出力（JSON schema）。Geminiの予約名（`googleSearch`／`codeExecution`／`urlContext`）はツール名に使わない。
- DBなし。状態はメモリと `runs/*.json`。単一プロセス。依存は最小。

## 5. リポジトリ構成（案）
```
/demo-site   架空ECサイト（軽量サーバー、メモリ上の状態）
/agent       自律ループ、アクション実行、OrcaRouterクライアント、PolicyGate、リトライ/フォールバック
/checks      l1-text, l2-ux, l3-rules, l4-abuse（テストカタログを含む）
/bench       ベンチマーク（同一入力で構成を切り替えて実行し、検出率・誤検知・コスト・時間を比較）
/report      レポートビューア（静的HTML）とリプレイ
/runs        保存された実行結果（デモ用のサンプルはコミットする）
/web         Java層（Spring Boot）: ジョブ作成・実行状況・承認UI・レポート表示
/docs        contracts.md, orcarouter-notes.md, design-decisions.md, STATUS.md, QUESTIONS.md
```

### 5a. 2つの実装セッションと担当ディレクトリ（衝突回避）
- **ワーカー担当**: `agent/`、`checks/`、`bench/`、`report/`、`demo-site/`、`runs/`、`requirements.txt`、`reference/`。worktree: `../AI_HACK_2026-worker`（ブランチ `dev/worker`）。進捗: `docs/STATUS.md`、疑問: `docs/QUESTIONS.md`。
- **Java層担当**: `web/` のみ。worktree: `../AI_HACK_2026-web`（ブランチ `dev/web`）。進捗: `docs/STATUS-web.md`、疑問: `docs/QUESTIONS-web.md`。ワーカーの完成を待たず、`docs/contracts.md` のワーカーAPIと、`runs/samples/` のサンプル `run.json`、モックワーカー（`web/mock-worker/`）で先に進める。
- **共有**: `docs/contracts.md`（契約）は、変更するときに**両方のセッションへの影響を書き**、人（おのゆー）経由で相手に知らせる。`CLAUDE.md`・`docs/requirements.md`・Notion は別セッション（企画）が更新する。

### 5b. 既存資産と移植マップ（`reference/agent-ai/` → このリポジトリ）
- 前提: 既存プロジェクト（`/Users/onoderayusei/agent-ai`）はGit管理外・Python 3.9で、用途の違うコードが混在している。**このリポジトリで作り直し、下記の4ファイルだけを移植する。** 移植元は `reference/agent-ai/` にコピー済み（秘密情報は含まない）。移植が終わったら `reference/` は削除してよい。
- **最初にやること（WP-1）**: 動作確認。`reference/agent-ai/demo_site/index.html` を `python3 -m http.server 8765 --directory reference/agent-ai/demo_site` で配信し、`reference/agent-ai/` のスクリプトで動くか確認する（`ORCAROUTER_API_KEY` が必要。**対象は自作デモのみ**）。動かなくても、移植の手がかりとして読めばよい。
- 移植マップ:

| 移植元 | 移植先 | 変更点 |
|---|---|---|
| `usability_test.py`（`GATHER_JS`、`CURSOR_JS`、`BrowserSession`） | `agent/browser.py` | (1) 許可外ホストの遮断を、事後（`go_back`）から事前（`page.route` でリクエストを中断＋ナビゲーション前の確認）へ。(2) 各アクションの前にポリシーゲートを呼ぶ。(3) `rapid_click`／`navigate_direct`／`fill_abnormal`／`tamper_param` を追加（回数・間隔に上限）。(4) スクショはメモリ保持（base64）をやめ、ファイルに保存して `run.json` から参照。(5) ネットワークのリクエスト/レスポンスを記録（L4の判定用）。(6) ペルソナごとのシステムプロンプトとviewport。(7) 詰まり検知と、CAPTCHA・ログイン要求での中断をコードで実装（現状はプロンプトのみ）。 |
| `usability_test.py`（`main()`、レポート追記） | `agent/cli.py`（薄いCLI） | `write_file` ツールでMarkdownを書かせる方式をやめ、構造化出力（`Finding`）を返させる。マウス計測は `metrics` に入れる。 |
| `orca_demo.py`（`_client`、`_create_with_retry`、`_compact_old_tool_results`、`run_loop`） | `agent/llm.py`、`agent/loop.py` | 生ヘッダーを取得する（`client.chat.completions.with_raw_response.create`）。`X-Orca-Request-Id`／`X-Orca-Resolved-Model`／フォールバック系ヘッダーを記録。`X-OrcaRouter-Include-Cost: true` で即時コスト、`GET /v1/generation` で確定額。再試行を、`err_free_rate` だけでなく 429（`Retry-After`）・5xx・タイムアウト・不正JSON・`err_free_prompt_cap`・`err_free_access_denied` に拡張。フォールバック（`extra_body`）と、無料→有料の自前切替。モデルは環境変数 `ORCA_MODEL`（Named Router名）から。 |
| `report_html.py` | `report/html.py` | `run.json` から生成できるようにする（リプレイ）。指摘（`Finding`）一覧、コスト・回復の指標、L4の証拠（リクエストログ）、横断指摘を追加。既存のマウス軌跡SVG・指標カードは活かす。 |
| `demo_site/index.html` | 参考のみ | 新しいデモサイトは `demo-site/server.py`（標準の `http.server` ベースで足りる）。この「使いにくい登録フォーム」は、L2用のページとして流用してよい。 |

- 移植しないもの（コピーしない）: `agent/`（Claude API直結の汎用エージェント）、`tasks/`、`.env`、`.venv/`、`logs/`、`workspace/`。
- 既存の強みを残す: 画面を「テキスト＋操作可能要素の一覧」で渡す方式（画像不要。無料の画像非対応モデルでも動く＝コスパの根拠）、マウスの実移動と軌跡・移動距離・クリック数の計測（他にない見せ場）、単一HTMLレポート。
- 既存の注意点: 過去の実行に `amazon.co.jp` が含まれていた。**今後の実行は自作デモと自分たちのサイトだけ**（許可ドメインの強制）。

### 5c. Java層（`web/`）の最小仕様
- 画面（Thymeleaf＋素のJS）: ①ジョブ作成（URL・ゴール・ペルソナ・モード inspect/l4。許可ドメイン外はエラー表示。「ドメイン所有確認」は模擬） ②実行状況（ポーリング。ステップ、人の介入回数、コスト、使用モデル、回復の回数） ③承認（`pending_approval` と `TestPlan` の一覧。承認/却下。L4は計画単位の一括承認） ④レポート（ワーカーの単一HTMLレポートを埋め込み／リンク。指摘一覧・コストの要約も表示） ⑤履歴一覧（C）。
- ワーカーAPIのエラー（停止・タイムアウト・400）でも落ちない。画面にわかりやすく表示し、再試行できる（評価③）。
- LLMを直接呼ばない。秘密情報（APIキー）を持たない。ワーカーのURLだけを設定に持つ（評価①）。
- ピッチで見せる: ジョブ作成→自走の様子→承認→レポートまでを、この画面で一気通貫に見せる。

## 6. データ契約（初期案。以降の正は `docs/contracts.md`）
```jsonc
// Action: エージェントが出す1操作
{ "id": "a-012", "step": 12,
  "tool": "click|type|select|navigate|scroll|back|rapid_click|navigate_direct|fill_abnormal|tamper_param",
  "args": { }, "reason": "なぜこの操作をするか" }

// PolicyVerdict: 実行前の評価結果
{ "verdict": "allow|audit|deny|sanitize|cap_cost|pending_approval",
  "approvalId": "（pending_approval のとき）", "reason": "…", "rule": "…", "source": "firewall|local" }

// LlmCall: 1回のLLM呼び出しの記録（コスト実測の根拠）
{ "requestId": "X-Orca-Request-Id", "router": "orcarouter/site-inspector",
  "resolvedModel": "X-Orca-Resolved-Model", "fallbackLevel": 0, "fallbackModel": null,
  "purpose": "l1-text|l2-vision|verify|plan|judge", "latencyMs": 0,
  "costUsdInline": 0.0, "costUsdSettled": 0.0,      // settled は GET /v1/generation の total_cost
  "retries": 0, "error": null }

// Finding: 1件の指摘
{ "id": "f-001", "lens": "L1|L2|L3|L4", "testId": "T-01",
  "severity": "High|Med|Low", "confidence": "confirmed|needs_review",
  "title": "…", "detail": "…", "url": "…",
  "evidence": [ { "type": "screenshot|request_log|response_excerpt", "ref": "runs/<runId>/step-12.png" } ],
  "repro": ["手順1", "手順2"], "fix": "修正案",
  "silentChurnRisk": true, "crossLens": ["f-004"] }

// TestPlan: L4のテスト計画（人が一括承認する）
{ "planId": "p-001", "status": "proposed|approved|rejected",
  "items": [ { "testId": "T-01", "macro": { "tool": "rapid_click", "args": { "count": 10, "intervalMs": 100 } },
               "target": "/checkout の注文確定ボタン", "expected": "注文は1件だけ", "riskNote": "…" } ] }

// Run: runs/<runId>/run.json
{ "runId": "…", "startedAt": "…", "target": "…", "goal": "…", "persona": "…", "mode": "inspect|l4",
  "config": { "router": "orcarouter/site-inspector", "faultInjection": false },
  "metrics": { "steps": 0, "humanInterventions": 0, "durationSec": 0,
               "costUsd": { "total": 0, "byLens": { }, "byModel": { } },
               "recoveries": { "retries": 0, "fallbacks": 0, "degraded": 0, "resumed": 0 } },
  "steps": [ { "n": 1, "action": { }, "verdict": { }, "observation": { "url": "…", "title": "…", "screenshot": "…" },
               "llmCalls": [ ] } ],
  "findings": [ ] }

// Benchmark: bench/results/<benchId>.json（同一デモサイト・同一仕込みで構成を切り替えて比較）
{ "benchId": "…", "site": "demo-site@<commit>", "seededFlaws": 14,
  "configs": [ { "name": "direct-strong|auto|named-router", "model": "…",
                 "detection": { "l1l3": "x/8", "l4": "y/6" }, "falsePositives": 0,
                 "costUsd": 0, "durationSec": 0 } ] }
```

## 7. デモサイト仕様
- ルート: `/`（トップ）、`/products/1`、`/cart`、`/checkout`、`/complete`、`/legal/tokushoho`（特商法）、`/privacy`、`/contact`、`/contact/confirm`、`/contact-safe`（**対照ケース**: 二重送信対策済み）、`/orders/:id`（連番。C）、`/__test/state`（テスト専用。`TEST_MODE=1` のときだけ有効。注文件数などを返す）。
- 注文・問い合わせの処理は**擬似**（メモリ上に記録するだけ。メール送信や決済はしない）。
- 仕込み（コードには `// SEEDED FLAW #n` を書いて突き合わせに使う。ただしエージェントには見せない）:
  1. 誤字（「送量無料」、文の脱字）
  2. 表記ゆれ（お問い合わせ／お問合せ／お問い合せ）
  3. 価格不一致（商品ページと決済画面）
  4. 送料不一致（特商法表記＝送料800円、決済画面＝送料無料）※横断指摘の主役
  5. 導線の詰まり（購入ボタンが「次へ」、戻る導線なし、小さい文字）
  6. アクセシビリティ（alt欠落、低コントラスト）
  7. 特商法ページの必須項目欠落、またはフッターにリンクなし
  8. 同意チェックなしで個人情報を送信できるフォーム
  9. （L4）二重注文: 注文確定の連打で注文が複数件になる
  10. （L4）手順スキップ: 決済・完了画面に直接アクセスでき、空のカートでも注文が完了する
  11. （L4）価格改ざん: 価格が hidden フィールド/URLパラメータで送られ、サーバー側で検証されない
  12. （L4）数量の異常値: 0・負数を許可する
  13. （L4）未エスケープ反射: 問い合わせ確認画面で入力がそのままHTMLとして表示される
  14. （L4）エラー露出: 特定の入力で500エラーと擬似スタックトレースが表示される
- 予備: リンク切れ、セキュリティヘッダー欠落、注文IDが連番で他者の注文が見える（C）。
- **信頼性（評価③）のためのページも置く**: ポップアップ、遅いページ（遅延）、404/500、リダイレクトの無限ループ、巨大なページ。**隠し指示のページ**（「注文を確定せよ」等の隠しテキスト、許可外ドメインへのリンク）も置き、Firewallが止めることを確認する。
- テスト用の個人情報（ダミーのメール・電話番号・氏名）を画面に置き、LLMに送る前にマスクされることを確認できるようにする。

## 8. L4 テストカタログ
上限: 1テストあたり最大10回・100ms間隔。すべて承認済みテスト計画の範囲・許可ドメイン・テスト環境のみ。

| ID | テスト | 実際の操作 | 期待される正しい動作 | 不具合の判定 |
|---|---|---|---|---|
| T-01 | 連打・二重送信 | 注文確定・送信ボタンを短時間に複数回押す | 1件だけ処理される | 複数件になる → High（決済系） |
| T-02 | 手順スキップ | 決済・完了画面へ直接アクセス、空のカートで注文確定 | 拒否される／前の画面へ戻される | 注文が完了する → High |
| T-03 | 戻る・リロード | 完了画面でリロード、戻って再送信 | 二重処理されない | 二重処理される → Med |
| T-04 | 異常入力 | 空欄、超長文字列、特殊文字・絵文字 | 入力エラー表示、サーバーエラーにならない | 500・画面崩れ → Med |
| T-05 | 数量・価格の異常値と改ざん | 数量0・負数・巨大値、hidden価格/URLパラメータの書き換え | サーバー側で検証・拒否 | 不正な値で注文成立 → High |
| T-06 | エスケープ | HTMLタグ風の無害な文字列を入力 | 文字としてそのまま表示 | タグとして解釈される → Med |
| T-07 | エラー露出 | 異常入力、存在しないURL | 内部情報を含まない汎用エラー | スタックトレース・内部パス表示 → Med |
| T-08（C） | 連番ID | 注文IDを1つずらしてアクセス | 他者のデータは見えない | 見える → High |
| T-09（C） | 2タブ同時操作 | 2タブで同じ操作を同時に実行 | 二重処理されない | 二重処理される → Med |
| T-10（C） | ログイン連続失敗 | 誤ったパスワードを数回（テストアカウント） | 一定回数で制限 | 制限なし → Low〜Med |

- 結果判定は3系統: ①画面の差分 ②ネットワークログ（Playwrightのrequest/response: リクエスト数、ステータス、本文の異常）③テスト環境の状態確認（`/__test/state`）。状態確認ができない対象では ①② のみで判定し、確度を `needs_review` に下げる。
- 対照ケース（`/contact-safe`）を不具合と誤判定しないこと（誤検知の検証）。

## 9. 自律ループ仕様
- ループ: Observe（スクショ＋操作可能要素リスト。要素はIDで指定）→ Plan → Act → Judge → 再計画。上限: ステップ数（初期値40）、時間、コスト。
- 詰まり検知: 同じURL・同じ要素集合でK回（初期値3）進まなければ「つまずき」として記録し、再計画または断念。
- ペルソナ: 「ITに弱い60代」（Must）、「スマホ初心者」（C）。
- モード:
  - **inspect**（通常の点検）: 注文確定・送信は `pending_approval`（UIで人が承認/却下）。
  - **l4**: エージェントがテスト計画（`TestPlan`）を作って提示 → 人が一括承認（UIのボタン1回で、計画内の各マクロ操作の承認をまとめて行う）→ 承認範囲内だけ実行。
  - どのテストを計画に入れるかは、**エージェントが画面の状況から選ぶ**（決め打ちにしない。購入画面→T-01/T-02/T-05、入力フォーム→T-04/T-06、など）。
- 自己検証（S）: 各指摘を別ステップで再確認し、再現できないものは `needs_review` に格下げ。
- 声なき離脱リスク（C）: L1/L2の指摘、およびL4のMed以下に `silentChurnRisk: true` を付け、件数を出す。
- レポート: 指摘一覧（観点・重大度・確度・URL・スクショ証拠・再現手順・リクエストログ・修正案）、実行タイムライン、指標（自律ステップ数、人の介入回数、所要時間）、**コスト（1診断あたり／観点別／モデル別）**、回復の回数（リトライ・フォールバック・劣化運用・再開）。

## 10. OrcaRouterの設計と計測（評価②④、OrcaRouter賞）
- **Named Router**（FR-27・M）: 例 `orcarouter/site-inspector`。Strategy は `gated_adaptive` を第一候補（Mundane＝軽量・無料モデル、Hard＝強いモデル）。まず `orcarouter/auto` で観察して遅延・コストを測り、その結果で候補を2〜4本に絞って固定する。別ベンダーのFallbackを1本置く。用途別（`purpose`: L1テキスト／画像判断／自己検証・難所）に、必要ならエスカレーション（回数と支出割合に上限）。
- **無料モデル優先**: まず無料モデル（Union Alpha等）で組み、当たった所だけ強いモデルに上げる（Day1資料の基本戦略）。無料枠の制限（レート、プロンプト上限、回転）に注意し、スクショは縮小する。
- **コスト計測**（FR-28・M）: すべてのLLM呼び出しで `LlmCall` を記録し、確定額を `GET /v1/generation` で取得して集計する。
- **ベンチマーク比較**（FR-29・M）: 同一のデモサイト・同一の仕込みで、（A）強いモデルの直指定、（B）Named Router、（余裕があれば C: `orcarouter/auto`）を実行。検出率・誤検知・コスト・所要時間を `bench/results/*.json` に保存し、表にする。**削減率は実測値のみ。** 品質（検出率・誤検知）を落とさずにコストが下がったかを示す。
- **設計判断ログ**（FR-35・M）: `docs/design-decisions.md` に、「観察（auto の結果）→ 判断 → 固定」の記録を残す（何を測り、何を根拠に、どのモデルに固定したか。Fallbackの設定理由。コスト比較）。ピッチで示す。
- **モデル名を固定しない**: 設定ファイルの Named Router 名だけを使う。

## 11. 信頼性・安全（評価①③）
- **エージェント自身の信頼性**（FR-30・M）: 次の失敗から復旧する。LLM側: 429（`Retry-After` を守る）、5xx、タイムアウト、不正なJSON・ツール引数、無料枠の拒否（`err_free_*`）、`guardrail_blocked`、`firewall_blocked`。ページ側: 404/500、ポップアップ、無限リダイレクト、巨大ページ、遅いページ。手段: リトライ、別ベンダーへのフォールバック、無料→有料の切替（自前）、劣化運用（画像判断が使えなければDOMテキストのみ）。**非ストリーミング。** 各ステップにタイムアウト。
- **中断・再開**（FR-31・S）: 各ステップ終了時に `run.json` を保存し、落ちても続きから再開できる。途中でも部分結果のレポートを出す。
- **障害注入モード**（FR-32・S）: `faultInjection` で、意図的にモデル障害（429/5xx/タイムアウト）や異常ページを注入し、復旧するところを実演・検証する（ピッチで見せる）。余裕があれば、このエージェント自身のUIにL4をかける（自己診断）。
- **ガードレール**（FR-33・M＝PIIマスク／S＝インジェクション検査）: OrcaRouter Guardrails を入力側に適用（email/phone/ssn のマスク、氏名・住所はカスタム正規表現、`llm_judge` でプロンプトインジェクション検査）。こちら側でも、スクショ・DOMのテキストのPIIをマスクしてから送る。
- **権限の最小化**（FR-34・S）: 実行用にスコープ付きAPIキー（予算上限・有効期限）を使う。Firewallの `cap_cost` で1診断あたりの上限。
- **Firewallルールの例**: 許可外ホストへの `navigate` は deny／`rapid_click` の `count` が10超は deny／注文確定は pending_approval／1診断あたりの上限は cap_cost。

## 12. 作業パッケージ（WP）と並行性
| WP | 内容 | 依存 | 目安 |
|---|---|---|---|
| WP0 | OrcaRouter疎通確認（人の登録・クレジット受け取り・キー発行の後）: `GET /v1/models`、Vision、ツール呼び出し、構造化出力、`X-Orca-*` ヘッダーとコスト取得、Named Router作成、Firewall評価APIの可否と仕様、Guardrails、スコープ付きキー、無料モデルの制限 → `docs/orcarouter-notes.md`（**結果を別セッションに共有**） | なし（**最初にやる**） | 1.5h |
| WP1 | デモサイト（軽量サーバー、仕込み14件、対照ケース、信頼性用ページ、隠し指示ページ、`/__test/state`） | なし（WP0と並行可） | 2.5h |
| WP2 | エージェント基盤: 移植（第5章の移植マップ）の上に、OrcaRouterクライアント（`LlmCall`記録）、ポリシーゲート（Firewall/自前の切り替え）、ループの詰まり検知を積む | WP0 | 1.5h |
| WP3 | チェック L1（テキスト・整合）、L2（UI・導線、ペルソナ）。L3（ルールベース）はC | WP2 | 2h |
| WP4 | L4: テスト計画と承認、マクロ操作、結果判定、テストカタログ | WP1, WP2 | 2.5h |
| WP5 | レポートビューア（既存 `report_html.py` を拡張）、実行JSON保存、リプレイモード。コスト・回復の指標表示 | データ契約のみ（サンプル `run.json` で先に着手できる） | 1h |
| WP6 | OrcaRouter設計と計測: Named Router（観察→固定）、コスト取得・記録、ベンチマーク比較、設計判断ログ | WP0, WP2 | 2.5h |
| WP7 | 信頼性・安全: リトライ・フォールバック・劣化運用、中断再開、障害注入、ガードレール、スコープ付きキー、Firewallルール | WP0, WP2 | 2.5h |
| WP8 | デモ用の実行を記録し、リプレイ用データを `runs/` に保存。ベンチ結果の確定 | WP2〜WP7 | 0.5h |
| WJ1 | Java層の土台: Spring Bootプロジェクト、ワーカーAPIクライアント、モックワーカー、ジョブ作成・実行状況の画面 | 契約のみ（モックで進める） | 2h |
| WJ2 | 承認UI（`pending_approval`／`TestPlan`）、レポート表示、エラー表示（ワーカー停止・タイムアウト）、履歴一覧（C） | WJ1 | 2h |
| WJ3 | ワーカーとの統合とデモ通し（モックから本物のワーカーへ切替、2分デモの通し） | WJ2, WP2〜WP5 | 1h |

- **WP-1（最初）**: 既存の動作確認と移植（第5章の移植マップ。約0.5h）。WP2 は移植済みの上に積む。
- 並行できる組み合わせ: WP1 ‖ WP0→WP2 ‖ WP5（サンプル `run.json` を先に作る）。WP6・WP7 は WP2 の後に並行。
- **優先度（削らない）**: Named Router、コスト計測、ベンチマーク比較、エージェントの信頼性、PIIマスク、設計判断ログ、リプレイ、テスト計画と承認、連打（T-01）と手順スキップ（T-02）、横断指摘1件（送料の不一致）、証拠付きレポート。
- **遅れたら削る順**: スコープ付きキー・cap_cost（FR-34）→ 障害注入（FR-32）→ 中断再開（FR-31）→ 自己検証（FR-11）→ L4のC（T-08〜T-10）・エスケープ/エラー露出（T-06/T-07）→ L3・2種目のペルソナ・修正チケット案・ライブ表示・声なき離脱タグ → Java層の履歴一覧・見た目の作り込み。ただし、Java層の最小機能（ジョブ作成・承認・レポート表示）は削らない。
- 評価が変わったため、**L1〜L3の網羅性より、①〜④の設計と実測（特に②③）を優先する。**

## 13. 完成の定義（受入基準。閾値は提案）
- AC-1: デモサイトで、ゴール「購入手前まで進める」を自律実行できる。
- AC-2: 点検モードでは注文確定の直前で必ず停止して承認待ちになる。L4はテスト計画の承認なしには実行されない（各3回連続）。
- AC-3: 許可外ドメインへの遷移が遮断される（テスト1件）。
- AC-4: L1〜L3の仕込み8件のうち6件以上を検出、誤検知2件以内。
- AC-5: 横断指摘（送料の不一致）を1件出す。
- AC-6: すべての指摘にスクショの証拠が付く。
- AC-7: すべてのLLM呼び出しがOrcaRouterのログに残る。
- AC-8: LLM/ネットなしでもリプレイでデモできる。
- AC-9: 2分デモを通しで3回成功させる。
- AC-10: 連打テストで二重注文（仕込み9）を再現し、再現手順とリクエストログ付きで指摘する。
- AC-11: L4の仕込み6件（9〜14）のうち4件以上を検出、誤検知2件以内。
- AC-12: 対照フォームを不具合と誤判定しない。
- AC-13: 連打の回数・間隔が上限を超えない。許可外ドメインへのL4操作は遮断される。
- AC-14（②）: 同一のデモサイト・同一の仕込みで、強いモデル直指定とNamed Routerを実行し、検出率・誤検知を大きく落とさずにコストが下がっていることを、`GET /v1/generation` の実測値で示す。
- AC-15（②）: 各ステップのコストとモデルを記録し、レポートに1診断あたり／観点別／モデル別で表示する。
- AC-16（③）: 障害注入（モデル障害・異常ページ）でも完走し、部分結果でもレポートを出す。フォールバックが働いたことを `X-Orca-Fallback-Model` で確認できる。
- AC-17（①）: スクショ・DOMに含まれる個人情報（テストデータ）がマスクされてからLLMに送られる。
- AC-18（①）: 隠し指示（プロンプトインジェクション）を含むページでも、承認なしの危険操作が実行されない。
- AC-19（④）: モデルIDがアプリのコードに固定されていない（設定またはNamed Router名のみ）。
- AC-20: Java層の画面で、ジョブ作成→自走→承認→レポート表示を通しで行える（3回連続）。
- AC-21（③）: ワーカーが止まっている・遅い・400を返すときも、Java層は落ちず、画面に表示して再試行できる。
- AC-22（①）: Java層はLLMを直接呼ばず、APIキー等の秘密情報を持たない。

## 14. 報告の仕方
- 各WPが終わったら、`docs/STATUS.md`（Java層は `docs/STATUS-web.md`）を更新する（できたこと／未完／要確認／次にやること）。別セッションがこれを読んでNotionとピッチに反映する。
- 不明点は `docs/QUESTIONS.md`（Java層は `docs/QUESTIONS-web.md`）に追記し、作業は止めずに仮定を明記して進める。
- ベンチマーク・コストの数字は、`bench/results/` と `docs/design-decisions.md` に実測値として残す（ピッチで使う）。

## 15. まだ確認できていないこと（最初に確認する）
- `ORCAROUTER_API_KEY` が有効か、クレジットが残っているか（人が確認する。既存の `agent-ai/.env` に設定済みの可能性がある。値は表示・コミットしない）。
- 公式ルールの「OrcaRouter使用必須」の原文（Day1資料に「必須」の明記は見当たらないが、評価②の定義とOrcaRouter賞が前提になっている）。
- **Firewall・Guardrails・スコープ付きAPIキーを使えるプランと権限**、評価APIのリクエスト/レスポンス形式。使えない場合は自前で代替し、その旨を明記する。
- Guardrailsのアクション数（Day1資料は5種、ドキュメントは block・mask・flag）、OrcaReplay と Request Logs の使い方。
- 無料モデル（Union Alpha）の実際の制限値と、画像（スクショ）入力が通るか。
- 開発言語（おのゆーの最速）。
- L4のLLM・実行コスト（テストの数だけ増える）。WP0〜WP2で計測して共有する。
