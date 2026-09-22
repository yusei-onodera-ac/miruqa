# データ契約（並行開発のためのインターフェース）

このファイルが正。変更したら、ここに変更内容と理由を追記する（`CLAUDE.md` 第6章・`docs/CHANGE-v0.5.md` 第4章は初期案）。

## v0.5での破壊的変更（重要・要周知）

`docs/CHANGE-v0.5.md`（2026-09-21決定）により、**ペルソナ・ゴールを契約・API・UI・CLI・プロンプトから完全に削除**し、
「仕様書取り込み → プラン → テスト項目書 → 承認 → 実行(項目書＋探索) → 結果」の工程に置き換えた（詳細はCHANGE本文）。

- `Run` から `goal` / `persona` フィールドを削除。`mode: "inspect"|"l4"` を廃止し、`TestCase.risk`（`normal`/`needs_approval`）に統合。
- `Finding` から `lens` / `testId` を削除し、`perspective`（P-…）・`origin`・`kind`・`specRef`・`expected`・`actual`・`testCaseId` を追加。
- ワーカーAPIの `POST /api/runs` は **後方互換なしで置き換え**る（`{url,goal,persona,mode}` → `{planId}`）。旧形状のリクエスト(`planId`が無い)は `400 missing_fields` を返す(実装: `agent/server.py`)。
- **この変更はJava層(`web/`)の `JobController`／`WorkerClient`／`index.html` を壊す。** 影響は人（おのゆー）経由で `dev/web` セッションに伝える予定だったが、`docs/QUESTIONS.md` #8で「Java層もこのセッション(dev/worker)が引き続き担当する」と回答されたため、実際にはこのセッション自身がJava層(`web/`)側も追従済み(MW区切り、`docs/STATUS-web.md`参照)。
- 旧形式の `runs/samples/*.json`（`goal`/`persona`/`mode`/`lens` を含む）は、リプレイ対象としては「旧形式」であることが分かるよう別ディレクトリ・別READMEに残す。新形式のサンプルを別途追加する。

## v0.6での変更(2026-09-21〜、`docs/CHANGE-v0.6.md`。1組織=1顧客規模の販売可能なサービス化)

- **Java層(`web/`)がシステムの記録の正になる。** DB(H2ファイルモード、Flyway管理)に
  users/organizations/memberships/projects/audit_logsを追加(P1。`web/src/main/resources/db/migration/V1__init.sql`)。
  0a章の規模のため、招待リンク・APIトークンのテーブルは作らない(`docs/QUESTIONS.md` #22)。
- **テナント分離(U-4)**: `Project`等の取得は必ず`organizationId`で絞り込む
  (`ProjectRepository.findByIdAndOrganizationId`のように、idだけで取得するメソッドを用意しない設計)。
  自動テスト`TenantIsolationTest`(AC-U1)で、他組織のプロジェクトIDを直接指定すると404になることを確認済み。
- **ワーカーAPIの認証**: 上記「ワーカーAPI」節に追記の通り、共有秘密ヘッダーを必須にした(破壊的変更)。
  P1補修でフェイルクローズドに変更済み: `WORKER_SHARED_SECRET`が空のまま起動するのは、
  `WORKER_AUTH_DISABLED=1`を明示しない限り拒否される(無検査での後方互換動作は廃止)。
- **既存の点検フロー(`JobController`/`PlanController`/`RunController`)は、まだProject/組織に
  紐付けていない**(ログインは必須になったが、Plan/Runの実データは引き続き`runs/`直下のJSONファイルの
  まま)。この統合はP2以降で行う予定(`docs/STATUS-web.md`に申し送り)。
- 認証: サインアップ・ログイン・ログアウト(Spring Security、BCrypt)。ログイン試行5回失敗で15分ロック
  (U-5)。CSRF有効(既定)。メール認証・パスワード再設定は模擬送信(`outbox/`にファイル出力)だが、
  トークン自体は本物(SHA-256ハッシュ保存・ワンタイム・期限つき。P1補修、`AuthToken`/`TokenService`)。
- **ドメイン所有確認(P2、L-1)**: `domains`テーブル(組織+ホスト名の組で1件。`V3__domains.sql`)。
  `/.well-known/<サービス名>-verification.txt`方式のみ実装(サービス名は未定のためプレースホルダー
  設定値`domain-verification.service-name`、既定`site-inspector`)。フローは
  `POST /projects/{id}/domain/start`(トークン発行)→ 所有者がそのファイルを対象サイトに設置 →
  `POST /projects/{id}/domain/check`(実際にHTTPで取得して照合)。確認先へのHTTP取得は、
  `DomainSafetyChecker`が名前解決した先のIP(ループバック・プライベート・リンクローカル・
  クラウドのメタデータアドレス)を事前にチェックしてから行う(SSRF対策。
  `domain-verification.sandbox-hosts`に完全一致するホストだけは自作デモサイト用の明示的な例外)。
  L-2(能動テストP-SECの許可条件)は`Domain.isActiveTestingAllowed()`
  (状態が`verified`、かつ所有者が`POST /projects/{id}/domain/declare-test-environment`で
  「テスト環境である」と宣言済み)で表現しているが、**ワーカー側の`TEST_MODE`・`ALLOWED_HOSTS`との
  連携はまだ未実装**(Java層のDB状態と、ワーカー側の環境変数が別々のまま。次の課題)。

```jsonc
// Action: エージェントが出す1操作（変更なし。マクロ系ツールの testId 引数は、
// v0.5では TestPlan.items[].testId ではなく TestCase.id(例 "TC-014")を指す）
{ "id": "a-012", "step": 12,
  "tool": "click|type|select|navigate|scroll|back|rapid_click|navigate_direct|fill_abnormal|tamper_param",
  "args": { }, "reason": "なぜこの操作をするか" }

// PolicyVerdict: 実行前の評価結果（変更なし）
{ "verdict": "allow|audit|deny|sanitize|cap_cost|pending_approval",
  "approvalId": "（pending_approval のとき）", "reason": "…", "rule": "…", "source": "firewall|local" }

// LlmCall: 1回のLLM呼び出しの記録（コスト実測の根拠）
// purpose を新しい工程に合わせて変更: spec-extract(仕様書→SpecItem) / recon(下見・SiteMap) /
// testcase-gen(仕様項目×観点→TestCase) / exec(項目書の実行) / judge(合否判定の根拠づけ) /
// charter(探索方針の提案) / exploratory(探索実行) / verify(自己検証・再現確認)
{ "requestId": "X-Orca-Request-Id", "router": "orcarouter/site-inspector",
  "resolvedModel": "X-Orca-Resolved-Model", "fallbackLevel": 0, "fallbackModel": null,
  "purpose": "spec-extract|recon|testcase-gen|exec|judge|charter|exploratory|verify", "latencyMs": 0,
  "costUsdInline": 0.0, "costUsdSettled": 0.0,      // settled は GET /v1/generation の total_cost
  "retries": 0, "error": null,
  "sessionTier": null,             // 実装追加(2026-09-21)。X-Orca-Session-Tierをそのまま転記(未観測なら常にnull)
  "promptTokens": 0, "completionTokens": 0 }  // 実装追加(2026-09-21)。API応答のusageをそのまま転記(取れなければnull)

// Spec: 取り込んだ仕様書
{ "specId": "s-001", "filename": "spec.md", "type": "md|txt|docx|pdf", "uploadedAt": "…",
  "items": [ { "id": "SPEC-001", "text": "送料は全国一律800円とする", "section": "3.1 送料の金額", "page": null,
               "kind": "rule|flow|ui|validation|constraint|other" } ],
  "warnings": [],
  "budgetStatus": { "exceeded": false, "reason": null } }   // 実装追加(2026-09-21)。下記「コスト台帳」参照
  // warningsの例: "表の一部を読み取れなかった"、"スキャンPDFのためOCR非対応"

// Plan: URL入力後に表示するプラン
{ "planId": "p-001", "target": "…", "status": "recon|ready|approved|failed",  // failedは実装追加(下見/生成の失敗)
  "note": null,  // 実装追加。画面は見つかったがLLM呼び出し失敗等でテスト項目が0件のときの理由(通常はnull)
  "siteMap": { "nodes": [ { "url": "/", "title": "…", "kind": "top|list|detail|form|cart|checkout|static" } ],
               "edges": [ { "from": "/", "to": "/products/1" } ] },
  "perspectives": ["P-FUNC", "P-TEXT"], "specIds": ["s-001"],
  "testCases": [ /* TestCase */ ],
  "estimate": { "cases": 0, "durationSec": null, "costUsd": null },   // 推定。測っていない値は null
  "coverageForecast": { "specItemsTotal": 0, "specItemsCovered": 0 },
  "budgetStatus": { "exceeded": false, "reason": null } }   // 実装追加(2026-09-21)。下記「コスト台帳」参照

// TestCase: テスト項目書の1行
{ "id": "TC-014", "perspective": "P-SEC", "title": "注文確定の連打で二重注文にならない",
  "target": "/checkout", "specRef": ["SPEC-012"],            // 空なら「仕様外の一般観点」
  "precondition": "カートに商品が1点ある",
  "steps": ["注文確定ボタンを100ms間隔で5回押す"],
  "macro": { "tool": "rapid_click", "args": { "count": 5, "intervalMs": 100 } },   // 任意
  "expected": "注文は1件だけ作られる（SPEC-012）",
  "risk": "normal|needs_approval", "enabled": true, "approved": false,
  "origin": "scripted|exploratory" }   // exploratory: 探索で見つかった案から人が承認して追加されたもの

// TestResult: 1項目の実行結果
{ "testCaseId": "TC-014", "verdict": "pass|fail|blocked|not_run|needs_review",
  "actual": "注文が5件作られた", "evidence": [ { "type": "screenshot|request_log|response_excerpt", "ref": "…" } ],
  "findingId": "f-003", "durationSec": 0,
  "recordedActions": [ { "tool": "click", "args": { "index": 4 } } ] }  // 実装追加(2026-09-21、判断19)。
  // 実行時に行った操作列(get_page_state除く)。再テスト時にPlaywrightだけで再生する構想の記録のみ
  // (再生自体は未実装。下記「再テスト・再生の設計」参照)

// ExploratorySession: 探索的テストの記録（Run 内。項目書の実行後に直列で行う。Mustの最小構成）
{ "charter": "カート〜決済の一連で、仕様と食い違う表示や想定外の挙動を探す",
  "budget": { "maxSteps": 30, "maxSec": null, "maxCostUsd": null },
  "suspicions": [ { "id": "sus-001", "text": "決済画面の送料表示が、特商法ページと違う", "url": "/checkout",
                    "evidence": [ { "type": "screenshot", "ref": "…" } ], "reproduced": false,
                    "promotedToFinding": "f-004", "proposedTestCase": null } ],
  "visitedNew": 0 }
// reproduced=false のときは Finding化しても confidence は needs_review 固定(Mustの最小構成)。
// 自己検証による reproduced=true → confirmed への昇格はShould(時間があれば実装)。

// Finding（変更）: 不具合票の形
{ "id": "f-001", "perspective": "P-FUNC|P-FLOW|P-INPUT|P-TEXT|P-LINK|P-UX|P-A11Y|P-SEC|P-PERF|P-RESP",
  "origin": "scripted|exploratory",
  "kind": "deviation|out_of_spec|unreachable|robustness|text|ux",   // 仕様逸脱／仕様外の挙動／仕様にあるが到達できない／堅牢性／文言／UX
  "severity": "High|Med|Low", "confidence": "confirmed|needs_review",
  "title": "…", "detail": "…", "url": "…",
  "specRef": ["SPEC-012"], "expected": "…", "actual": "…", "testCaseId": "TC-014",
  "evidence": [ { "type": "screenshot|request_log|response_excerpt", "ref": "runs/<runId>/step-12.png" } ],
  "repro": ["手順1", "手順2"], "fix": "修正案",
  "silentChurnRisk": true, "crossLens": [],
  "duplicateCount": 1, "duplicateUrls": [], "duplicateDetails": [] }  // 実装追加(2026-09-21、判断23)。同一指摘(perspective/kind/detail完全一致)が
  // 複数画面で検出されたときに1件へ統合した数と、統合元のURL一覧(1件のみなら省略時と同じ意味)。
  // v0.7 P6中核(2026-09-22)で統合条件を拡張: 完全一致に加え、specRefが同じ・観点/種別/正規化した
  // 題名が同じ指摘も1件に統合する(数値だけ異なる文言を取り逃さないため)。duplicateDetailsには、
  // 統合で消えたdetail文言のうち代表と異なるものを保持する(情報を失わないため)。
// lens / persona / testId(旧L4テストID) は廃止。lens は perspective に置き換え。

// Coverage: カバレッジ表
{ "specItems": { "total": 0, "covered": 0, "passed": 0, "failed": 0,
                  "notCovered": [ { "id": "SPEC-020", "reason": "対象画面に到達できなかった" } ] },
  "perspectives": [ { "id": "P-SEC", "cases": 0, "pass": 0, "fail": 0, "notRun": 0 } ],
  "screens": { "discovered": 0, "visited": 0 } }

// Run: runs/<runId>/run.json（v0.5で goal/persona/mode を削除）
{ "runId": "…", "startedAt": "…", "target": "…", "planId": "p-001", "specIds": ["s-001"],
  "status": "queued|running|waiting_approval|completed|failed",
  "config": { "router": "orcarouter/site-inspector", "faultInjection": false },
  "metrics": { "steps": 0, "humanInterventions": 0, "durationSec": 0,
               "costUsd": { "total": 0, "byPerspective": {}, "byModel": {} },
               "recoveries": { "retries": 0, "fallbacks": 0, "degraded": 0, "resumed": 0 } },
  "steps": [ { "n": 1, "phase": "recon|scripted|exploratory", "testCaseId": null,
               "action": { }, "verdict": { }, "observation": { "url": "…", "title": "…", "screenshot": "…" },
               "llmCalls": [ ] } ],
  "testResults": [ /* TestResult */ ],
  "exploratory": { /* ExploratorySession */ },
  "coverage": { /* Coverage */ },
  "budgetStatus": { "exceeded": false, "reason": null },  // 実装追加(2026-09-21)。下記「コスト台帳」参照
  "findings": [ ],
  "compareTo": null }   // 再テスト(S)。前回runIdとの差分("fixed"/"new"/"unchanged")。未実装ならnull

// Benchmark: bench/results/<benchId>.json（低優先度。実装は後回し。
// perspectiveベースの検出件数に置き換える想定だが、bench/run_bench.py改修まではスキーマ未確定）
{ "benchId": "…", "site": "demo-site@<commit>",
  "configs": [ { "name": "direct-strong|auto|named-router", "model": "…",
                 "detection": { }, "falsePositives": 0, "costUsd": 0, "durationSec": 0 } ] }
```

- **判定の原則**（変更なし）: 二重注文などの合否は、**リクエスト数・状態確認・画面差分のコード判定**を主とし、LLMの感想だけで `confirmed` にしない。仕様との比較（文言・数値・挙動の一致）は、LLMが根拠（仕様項目ID＋実際の観測）を示したときだけ `fail`。根拠が弱い場合は `needs_review`。仕様書がない場合は原則 `needs_review` だが、500エラーや `/__test/state` の確認など**コード的に明確な根拠**が取れるものは `confirmed` にしてよい（`docs/QUESTIONS.md` #12）。

## ワーカーAPI（Java層との境界。ハッカソンで実装する）

Java層（`web/`）が、ワーカー（Python）を呼び出し、結果を読み、承認を出すための境界。データ形式は上のJSON契約と同じ。**変更したら、ここに追記し、影響を書く（人が両方の実装セッションに伝える）。**

- ワーカーのURL: Java層は設定 `WORKER_BASE_URL`（既定 `http://127.0.0.1:8770`）で呼ぶ。ワーカーは標準ライブラリの `http.server` ベースで足りる。
- **認証(v0.6 P1追加、破壊的変更)**: すべてのエンドポイントで、リクエストヘッダー `X-Worker-Auth` に
  共有秘密(環境変数 `WORKER_SHARED_SECRET`。ワーカー側は`agent/config.py`、Java層は
  `application.properties`の`worker.shared-secret`)を要求する。一致しないと`401 unauthorized`。
  **P1補修でフェイルクローズドに変更**: `WORKER_SHARED_SECRET`が空のまま起動するのは、
  `WORKER_AUTH_DISABLED=1`を明示しない限り拒否される(無検査での後方互換動作は廃止)。
  ワーカーは引き続き127.0.0.1のみで待ち受ける(多層防御)。
- 実行状態 `status`: `queued`／`running`／`waiting_approval`／`completed`／`failed`／`aborted`。
- HTTP（**v0.5で全面置き換え**。旧 `POST /api/runs {url,goal,persona,mode}` は廃止）:
  - `POST /api/specs` … 仕様書ファイル（multipart、フィールド名 `file`）→ `Spec`（項目分解済み）。対応形式: `txt`／`md`（必須）、`docx`／`pdf`（時間があれば）。未対応拡張子は `400 unsupported_spec_type`。
  - `POST /api/plans` … `{"url", "specIds": [], "authorization": {...}}`（`specIds` は空配列可＝仕様書なしモード）→ 事前調査と項目書生成を非同期開始。`{"planId", "status": "recon"}` を返す。許可ドメイン外は `400 domain_not_allowed`。
    **`authorization`(v0.6 P2 L-2、任意)**: Java層が組み立てる`{host, verified, testEnvDeclared, consentId, grantedAt}`。所有確認済み・同意記録済みかどうかをワーカーに伝える。`authorization.host`が対象URLのホストと不一致なら`400 authorization_mismatch`。省略時は空dict扱い(=`testEnvDeclared`は常にfalse。P-SEC=危険度`needs_approval`の能動テストは実行できない。一般の点検はauthorizationの有無に関わらず従来通り進む)。Planに保存され、`POST /api/runs`実行時に`agent/browser.py`の`BrowserSession`へ引き継がれ、`agent/policy.py`のP-SECゲートが`TEST_MODE`・`testCaseApproved`に加えてこの`testEnvDeclared`も必須条件にする(3条件すべて揃わないと`deny`)。
    **`testAccount`(v0.7 P5、任意、`authorization`とは別の最上位フィールド)**: `{"username","password"}`。ログインが必要な画面を下見の対象にするため、`recon_site()`の巡回を始める前に一度だけログインを試みる(`agent/browser.py`の`attempt_login()`)。**Planには一切保存しない**(`authorization`と違いplan.jsonに残らない。実行時だけ使う設計。Java層は暗号化保管したパスワードを、この呼び出しのたびに復号して渡す)。`mode="readonly"`では、Java層がそもそもこのフィールドを付けない(認証を回避しない方針)。
  - `GET  /api/plans/{planId}` … `Plan`（生成中は `status:"recon"`、完了で `status:"ready"`）
  - `POST /api/plans/{planId}/approve` … `{"testCaseIds": [...]}`（有効化かつ承認する項目のID一覧。`risk:"needs_approval"` の項目を含めるときは、そのIDをここに含めることで一括承認したことになる。含めなかった項目は `enabled:false` 扱いで実行されない）。`{"planId","status":"approved"}` を返す
  - `POST /api/plans/{planId}/resume` … コスト上限等で`budgetStatus.exceeded`のまま止まった項目書生成の続きを行う(既存のテスト項目がある画面を除いた、未処理のSiteMapノードだけを対象に生成し追記。下見はやり直さない)。非同期、`{"planId","status":"recon"}` を返す。`status`が`ready`/`approved`/`failed`以外だと`400 plan_not_resumable`。**追加(2026-09-21。非破壊)**
  - `POST /api/runs` … `{"planId", "testAccount": {...}}`(`testAccount`は任意。v0.7 P5、上記参照。run.jsonには保存しない) → 実行開始(項目書の実行→探索の順)。`{"runId","status":"queued"}` を返す。`planId` が `approved` でなければ `400 plan_not_approved`
  - `GET  /api/runs` … 実行の一覧（`runId`、`startedAt`、`target`、`planId`、`status`、`metrics` の要約）
  - `GET  /api/runs/{runId}` … `Run` に `status` を加えたもの（途中経過もここで読める。ポーリング）。`status`に`interrupted`(ワーカー再起動により中断)・`cancelled`(緊急停止により打ち切り)を追加(v0.6 P2)
  - `POST /api/runs/{runId}/cancel` … **緊急停止(v0.6 P2、追加のみ・非破壊)**。実行中のRunにキャンセルを要求する(協調的: 次のステップ境界(TestCase間・探索ステップ間・LLM呼び出し前)で検出され、ブラウザを閉じて`status:"cancelled"`として終了する。即座には止まらない)。存在しない`runId`は`404 run_not_found`。`{"runId","status":"cancel_requested"}`を返す(実際に停止したかは`GET /api/runs/{runId}`のポーリングで確認する)。Java側は`POST /runs/{runId}/stop`(監査ログに記録)から呼ぶ。ワーカー起動時、`running`/`waiting_approval`/`queued`のまま残っているRunは`interrupted`に、`recon`のまま残っているPlanは`failed`に、それぞれ自動的に整理される(`agent/server.py`の`_cleanup_orphaned_state()`。P1補修中に発生した「再起動でreconのまま孤立」の再発防止)
  - `GET  /api/approvals?status=pending` … 承認待ち（`runId`、`approvalId`、`PolicyVerdict`、または実行中に単発で発生する追加承認）。**項目書自体の承認は `POST /api/plans/{planId}/approve` で行うため、ここには出てこない**（実行中に想定外に発生する個別の危険操作のみ、従来通りここに出る）
  - `POST /api/approvals/{approvalId}` … 本文 `{"decision": "approve" | "reject"}`。承認は1回限り
  - `GET  /api/runs/{runId}/report` … 単一HTMLレポート（`text/html`）
  - `GET  /api/runs/{runId}/export?format=csv|html` … 項目書・結果の書き出し（S。実装済み。`csv`はテスト項目書×結果の一覧、`html`は`/report`と同じ単一HTML）
  - `GET  /api/health` … 死活確認。`{"status":"ok","testMode":bool,"allowedHosts":[...]}`(Java層のトップバーの環境バッジ表示に使う。追加のみ・非破壊)
  - `GET  /api/plans/{planId}/cost` … このPlanの下見・項目書生成(spec-extract/testcase-gen)にかかった実測コスト内訳(`CostSummary`、下記)。**追加(2026-09-21。FR-28の記録漏れ修正に伴う。非破壊)**
  - `GET  /api/runs/{runId}/cost` … このRun自身の実行分(exec/exploratory/charter)に加え、元になったPlanの下見・項目書生成分も合算した実測コスト内訳(`CostSummary`)。ワーカー未対応の古いバージョンとの互換のため、Java層はこの呼び出しの失敗を許容し、失敗時は空のコスト表示に倒す(`RunController`)。**追加(2026-09-21。非破壊)**
  - `GET  /api/cost/summary` … `runs/ledger.jsonl` 全体の集計(`CostSummary`)。診断横断の合計を見るための補助エンドポイント(Java層からは現状未使用。`python -m agent.cost_report`と同じ集計)。**追加(2026-09-21。非破壊)**
- CLI（フォールバック）: `python -m agent.cli run --url <URL> [--spec <PATH> ...] [--approve-cli] [--json]`。`--spec` は複数指定可（省略時は仕様書なしモード）。`--approve-cli` は、項目書の一括承認・実行中の個別承認をターミナルでy/nで受け付ける。結果は `runs/<runId>/run.json`（`Run` 契約）。**`--goal`／`--persona` は廃止**。
- エラー形式: `{"error": "<コード>", "message": "<説明>"}`（HTTP 4xx/5xx）。
- 認証・ユーザー管理・課金は作らない（ハッカソンの範囲外）。ワーカーは許可ドメイン（`ALLOWED_HOSTS`）と `TEST_MODE` だけを強制する。Java層はLLMを直接呼ばず、秘密情報を持たない。

## 実装状況

- **v0.4までの実装(2026-09-20時点)**: 上記の旧6エンドポイント(`POST/GET /api/runs`等)はすべて動作確認済み。`agent/server.py`参照。v0.5では、この上に `specs`/`plans`/`plans/{id}/approve` を追加し、`POST /api/runs` を新形状に置き換える(実装は `docs/STATUS.md` のv0.5節を参照)。
- `GET /api/runs/{runId}` は `runs/<runId>/run.json` をそのまま返す(実行中でも各ステップ後の最新状態が読める)。承認は `threading.Event` で待つ実装(タイムアウト=`AGENT_MAX_DURATION_SEC`、既定600秒。タイムアウトすると既定拒否)。この仕組み自体はv0.5でも流用する。

## コスト台帳(`runs/ledger.jsonl`)と予算上限(2026-09-21追加。FR-28の記録漏れ修正)

背景: `Spec`/`Plan`生成中のLLM呼び出し(spec-extract/testcase-gen)のコストが、呼び出し元で捨てられて
どこにも記録されない実装バグがあった(実測コスト合計が実際の課金より少なく見えていた)。これを
Spec/Planのスキーマを肥大化させる形(各所に`llmCalls`配列を持たせる)ではなく、LLM呼び出しの
共通入り口(`agent/llm.py`の`call_llm()`)に台帳を1本化することで解消した(`agent/ledger.py`)。

```jsonc
// runs/ledger.jsonl(追記のみ・1行1JSON。.gitignoreの`runs/*`でコミット対象外)
{ "type": "call", "ts": "…", "requestId": "…", "purpose": "spec-extract|testcase-gen|charter|exec|exploratory",
  "router": "…", "resolvedModel": "…", "fallbackLevel": 0,
  "costUsdInline": 0.0, "costUsdSettled": null, "retries": 0, "error": null,
  "runId": null, "planId": "p-001", "specId": null }
// 確定額(GET /v1/generation)が取れたときだけ、別スレッドが同じrequestIdで追記する行(既存行は書き換えない)
{ "type": "settlement", "ts": "…", "requestId": "…", "costUsdSettled": 0.0031, "runId": null, "planId": "p-001", "specId": null }

// CostSummary: GET /api/{plans,runs}/{id}/cost, GET /api/cost/summary の応答(agent.ledger.aggregate())
{ "callCount": 0, "errorCount": 0,
  "totalCostUsdInline": 0.0, "totalCostUsdSettled": 0.0, "totalCostUsdEffective": 0.0,  // effective = 確定額があればそれ、無ければ即時値
  "byPurpose": { "spec-extract": { "count": 0, "costUsdInline": 0.0, "costUsdSettled": 0.0, "errors": 0 } },
  "byModel": { "google/gemini-3.5-flash": { "count": 0, "costUsdInline": 0.0, "costUsdSettled": 0.0 } },
  "byDay": { "2026-09-21": { "count": 0, "costUsdInline": 0.0, "costUsdSettled": 0.0 } } }
```

- 予算上限: 環境変数 `LLM_BUDGET_PER_RUN_USD`(既定0.5)・`LLM_BUDGET_PER_DAY_USD`(既定2.0)。超過すると
  `call_llm()`はAPIを呼ばずに`degraded=True`・`error:"budget_exceeded: …"`を返す(呼び出し元の既存の
  連続失敗検知がそのまま働き、残り作業を打ち切って部分結果のまま終了する)。「1件」のスコープは
  `context`の`runId`→`planId`→`specId`の優先順位(`agent/ledger.py`の`_scope_key`)。
- `Spec`・`Plan`・`Run`にはそれぞれ `budgetStatus: { "exceeded": bool, "reason": string|null }` を追加した
  (非破壊の追加フィールド。`llm_calls`の中に`budget_exceeded`で始まる`error`があるかどうかから機械的に判定)。
- **二重計上に注意**: 1回のLLM呼び出しにつき、上記の`type:"call"`行と`type:"settlement"`行の
  **2行**がファイルに存在しうる(同じ`requestId`)。`ledger.jsonl`を直接合計すると二重計上になるため、
  必ず`agent.ledger.merged_calls()`/`aggregate()`(`requestId`単位で1件に合流させてから集計)を
  経由すること。`agent/cost_report.py`・Worker APIのコスト内訳エンドポイントはすべてこの経由。
- `budgetStatus.exceeded`のPlanは、`POST /api/plans/{planId}/resume`で続きを生成できる(下記「ワーカーAPI」参照)。
- 集計コマンド: `python -m agent.cost_report [--plan <id>|--run <id>|--spec <id>] [--json]`。

## Run契約の補足
- `steps[].verdict` は「そのフェーズ内の1操作、または1LLMターン中に発生した最後のPolicyVerdict」。**全アクションの個別のPolicyVerdictは、`run.json` 直下の `policyLog`(配列、要素は`{"action","verdict"}`)に残る。** 概要は`steps[].verdict`、監査ログの全件は`policyLog`を見る。
- `Run` には契約になかった `outcome`("success"|"abandoned")、`summary`、`networkLog`(直近200件)、`blockedRequests`(許可外ホストへブロックしたリクエスト)、`mouseMetrics`(`clicks`/`distancePx`/`trace`)も含む(実装が先行したため追記)。v0.4の `testPlans` はv0.5で `testResults`(TestCase実行結果)に置き換わる。
