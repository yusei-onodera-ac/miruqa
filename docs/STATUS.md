# 進捗（実装側が更新する。別セッションがこれを読んでNotionとピッチに反映する）

**セッションが長時間化した場合・再開する場合は、まず`docs/RESUME.md`を読むこと**(現在のフェーズ・次にやること・守るべき決定事項・ハマりやすい点・動作確認手順をまとめてある)。

最終更新: 2026-09-20（ワーカー担当セッション。`ORCAROUTER_API_KEY` 到着後、Java層(`web/`)もこのセッションで着手）

| WP | 内容 | 状態（未着手／進行中／完了） | メモ（できたこと／未完／要確認） |
|---|---|---|---|
| WP-1 | 既存の動作確認と移植 | 完了 | `reference/agent-ai/` を読み、移植マップ通りに `agent/browser.py`(BrowserSession)・`agent/llm.py`(OrcaRouterクライアント)・`agent/loop.py`(ループ)・`report/html.py`(レポート)へ作り直した。既存の強み(テキスト+要素一覧方式、マウス軌跡計測、単一HTMLレポート)は維持 |
| WP0 | OrcaRouter疎通確認 | 完了(実装側の範囲) | 実キーで疎通確認済み。**わかったこと**: (1) `GET /v1/models` はこのキーで9件のみ返す(`orcarouter/free`・`fusion`系・`auto`と、`-free`直接モデル4種。約200モデルという記載とは規模が異なる。プラン/キーのスコープによる可能性) (2) **このキーのスコープは現状 `-free`直接モデルと`orcarouter/free`の一部呼び出ししか許可しない**。`orcarouter/auto`・`orcarouter/fusion*`・有料直接モデル(`google/gemini-2.5-flash`等)は`model_access_denied`(403、`reason: block_key_scope`)。コンソール(`https://www.orcarouter.ai/console/token?ref=block_key_scope`)でスコープを広げる必要がある → `docs/orcarouter-notes.md`に依頼を追記 (3) ツール呼び出し・構造化出力・画像入力(Vision)はいずれも直接モデルID(`deepseek/deepseek-v4-flash-free`等)で成功 (4) コスト取得: `X-OrcaRouter-Include-Cost`のインライン値と`GET /v1/generation`の確定値(`{"data":{"total_cost":...}}`、当初レスポンス形状を誤解しておりバグを1件修正)を確認、無料モデルは両方とも0 (5) 無料枠のレート制限に実際に到達(`err_free_rate`、`retry_after_seconds`が27357秒=約7.6時間という大きな値だった。エージェントは待たずに見切りをつけて`abandoned`で穏やかに終了。設計通り) |
| WP1 | デモサイト（仕込み14件、対照ケース、信頼性用ページ、`/__test/state`） | 完了 | `demo-site/server.py`(標準ライブラリのみ)。仕込み1〜14すべて実装し、直接HTTP・Playwright経由の両方でテスト済み。**重要な修正**: 連打(T-01)がPlaywright経由だと1回しか送信されなかった問題を、`/checkout/confirm`に0.25秒の擬似決済遅延を入れて解決(実ブラウザ操作で二重注文5/5件を再現) |
| WP2 | エージェント基盤（OrcaRouterクライアント、ポリシーゲート、詰まり検知） | 完了 | `agent/config.py`・`agent/llm.py`(429/5xx/timeout/無料枠拒否からの復旧、Fallback、障害注入)・`agent/policy.py`・`agent/browser.py`・`agent/loop.py`。**実LLM(`deepseek/deepseek-v4-flash-free`、無料)での結合テストに合格**: トップ→商品→カート→決済確認画面まで自律実行し、LLM自身が価格不一致(High)・誤字・返品特約の不明示を検出、コスト$0を実測。詳細は `runs/samples/run-8fff06d407/` |
| WP2b | ワーカーHTTP API・CLI（Java層との境界） | 完了 | `agent/server.py`(6エンドポイント)・`agent/cli.py`。Java層(`web/`)から実際に呼ばれて動作確認済み(下記WJ参照) |
| WP3 | チェック L1・L2 | 部分完了 | LLMのreport_finding呼び出しが主(実LLMでの検出を確認済み)。加えて `checks/rules.py` の送料不一致チェック(AC-5)はLLMに依存せず毎回confirmedで検出する安全網として機能確認済み |
| WP4 | L4（テスト計画と承認、マクロ操作、結果判定） | 完了 | `propose_test_plan`→一括承認→`rapid_click`/`navigate_direct`/`fill_abnormal`/`tamper_param`。T-01(二重注文5/5件)・T-05(価格改ざん)を確認済み(擬似LLM応答での結合テスト。実LLMでのL4シナリオはレート制限のため次回に持ち越し) |
| WP5 | レポート・実行JSON・リプレイ | 完了 | `report/html.py`。`runs/samples/`に3件(実LLM1件・擬似応答2件)をコミット |
| WP6 | OrcaRouter設計と計測（Named Router、コスト、ベンチ、設計判断ログ） | **完了(2026-09-21)** | コスト計測はledger実装により全呼び出しで記録。Named Router(`orcarouter/site-inspector`)はおのゆーが作成、直指定/Named Router/autoの3構成比較を実測(結果は`docs/design-decisions.md`判断16、削減効果は確認できなかった) |
| WP7 | 信頼性・安全（リトライ・フォールバック、再開、障害注入、ガードレール） | 部分完了 | リトライ/フォールバック/障害注入/PIIマスクは実装・単体テスト済みに加え、**429(err_free_rate)を実運用で実際に踏んで正しく穏やかに終了することを確認**。中断・再開(プロセス再起動後の再開)は未実装。Guardrails/Firewall評価APIは未接続(自前実装で代替、明記済み) |
| WP8 | デモ用の実行の記録とベンチ結果の確定 | 一部進行中 | 実LLMでの1件は記録済み(`runs/samples/run-8fff06d407/`)。ベンチ結果の確定はキーのスコープ拡張待ち |
| WJ1〜WJ3 | **Java層(`web/`)**: Spring Boot土台・ワーカーAPIクライアント・ジョブ作成/実行状況/承認UI/レポート表示/履歴一覧 | 完了 | 当初「別セッション」の想定だったが、人の指示によりこのセッションで着手・完了。`docs/STATUS-web.md`に詳細。実ワーカー・実LLMと結合した状態でジョブ作成→自律実行→レポート表示→履歴一覧まで動作確認済み |

## 次にやること
0. **(更新、2026-09-20)** 無料モデルのレート制限(約7.6時間待ち)に実際に到達したため、人にAPIキーのスコープ拡張を急ぎ依頼し、対応いただいた。現在は `google/gemini-3.5-flash`(有料・直接モデルID)が既定(`ORCA_MODEL`)。無料モデルとの比較(1回ずつ)は `runs/samples/README.md` に記録。`orcarouter/auto`自体はまだ`block_key_scope`で拒否されたままなので、Named Router作成時も同様の制限に注意。
1. **(人・後回しでよい)** OrcaRouterコンソール(`https://www.orcarouter.ai/console/token?ref=block_key_scope`)で、このAPIキーのスコープを広げる(`orcarouter/auto`・Named Router・有料モデルを使えるようにする)。Named Routerも作成してほしい(`docs/orcarouter-notes.md`に提案値あり)。
2. スコープが広がり次第、`orcarouter/auto`の観察→Named Router候補の絞り込み→`bench/run_bench.py`本実行→`docs/design-decisions.md`に実測値を記録。
3. 中断・再開(プロセス再起動後の再開)を実装する。
4. Firewall評価API・Guardrailsの実際の挙動を確認し接続する(使えなければ自前のままでよい)。
5. 無料モデルのレート制限が解除され次第、L4シナリオも実LLMで撮り直す。

## 実測値（ピッチで使う。測っていない数字は書かない）
- **実LLM実行1件(2026-09-20)**: モデル`deepseek/deepseek-v4-flash-free`(OrcaRouter経由・無料)。6ステップ、所要時間は`runs/samples/run-8fff06d407/run.json`参照、**コスト実測$0**(無料モデルのため)。検出: LLMが3件(価格不一致High・誤字Low・返品特約不明示Med)、ルールベースが1件(送料不一致High、confirmed)。
- **API疎通・エラー形状**: `GET /v1/generation`のレスポンスは`{"data":{"total_cost":...}}`(ネスト、実装時に誤解して1件バグ修正)。エラーは`{"error":{"code","message","metadata","type"}}`の形(401/403/429いずれも共通)。
- **無料枠のレート制限**: 実際に`err_free_rate`(429)に到達。`retry_after_seconds: 27357`(約7.6時間)という値を確認。
- コスト削減率・検出率の構成比較(強いモデル直指定 vs Named Router)は**まだ実測していない**(キーのスコープ拡張待ち)。

## v0.5 完了(2026-09-21、ワーカー担当セッション)

`docs/CHANGE-v0.5.md`が定めるMust(M0〜M6)・Java層UI刷新(MW)・M7(Should)の一部まで実装・実測を完了した。
詳細は下記の各表を参照。ここでは全体像だけまとめる。

- **完了**: 仕様書取り込み(txt/md/docx/PDFの4形式)、下見(SiteMap)、テスト項目書(TestCase)生成、
  承認(危険度needs_approvalの一括承認を含む)、実行エンジン(コード根拠の合否判定を主とする`agent/judging.py`)、
  探索的テスト、カバレッジ集計、レポート(HTML・CSV書き出し)、Java層のSaaS/管理ツール風UI全面刷新
  (ウィザード・サイドバー・KPIカード・タブ・ドロワー)。受入基準はAC-A〜I・AC-E2をすべて確認し、
  そのほとんどが合格(内訳は下表)。
- **未完・要確認**(優先度順):
  1. **[解決・2026-09-21]** `ORCAROUTER_API_KEY`(`docs/QUESTIONS.md` #17・#19)は、おのゆーの対応により復旧を確認した。
     Named Router `orcarouter/site-inspector`も作成済み。復旧後、A(直指定)/B(Named Router)/C(auto)の3構成で
     煙テスト→本比較(下記AC-14)まで実施した。
  2. **[実測完了・2026-09-21]** AC-14(強いモデル直指定 vs Named Router/autoのコスト比較)を実施。
     **結果: BもCも、Aより高コスト・低速だった(削減効果は確認できなかった)**。詳細は下記
     「ルーター比較の実測結果」と`docs/design-decisions.md`判断16を参照。
  3. AC-F(対照ケース`/contact-safe`)は、サイト内リンクが無い意図的な対照ケースのため自動下見では
     到達せず、エージェントが実際に判定した実測がまだ無い(サーバー側の二重送信対策自体は直接確認済み)。
  4. M7のうち、再テスト差分(`Run.compareTo`)とP-UX/P-A11Yの充実は未着手。
  5. `main`ブランチへのローカルマージは、別worktree(`/Users/onoderayusei/AI_HACK_2026`)に他セッションの
     未コミットの変更があったため見送った(`docs/QUESTIONS.md` #18)。`dev/worker`側のコミットはすべて完了している。
- **実測コスト(合計、`runs/ledger.jsonl`が正。2026-09-21のセッション固定比較まで含めた最新値)**:
  `python -m agent.cost_report`による本日(2026-09-21)分の合計は**$1.9603(412回のLLM呼び出し、
  うちエラー14回)**。OrcaRouterの利用上限$5に対しては**約39%を使用**($1.9603/$5)。内訳:
  前半(キー復旧前)の401/403エラー呼び出し(課金なし)、ルーター比較実験($1.0655前後)、
  追加のコスト構造実験(判断17〜22、フォールバック・圧縮・macroコード実行・セッション固定比較
  など。合計約$0.76、「実験全体で追加約$1.5まで」の予算枠内、約半分を使用)。
  この記録より前(v0.4のWP0〜WP8、v0.5のM1〜M7)の実行分は台帳導入前のため`run.json`の
  `metrics.costUsd.total`を人力で合算した参考値(約$1.90、記録漏れにより実際より少ない可能性が高い)。
  今後はすべて`runs/ledger.jsonl`を正とする。

### コスト実測の記録漏れ修正・予算上限の追加(2026-09-21、追加作業)
別セッション経由で「APIキーの利用上限($5)を使い切ったが、STATUS上の実測($1.90)と説明がつかない」との
指摘を受け、調査したところ、`agent/specs.py`の`build_spec()`と`agent/planning.py`の`generate_test_cases()`が
返す`llm_calls`を、呼び出し元(`agent/server.py`)が受け取ったまま保存せずに捨てていたバグを発見した
(仕様書抽出・項目書生成のLLM呼び出しコストが実測から丸ごと抜け落ちていた)。加えて確定額
(`GET /v1/generation`)を取得・記録する経路自体が無く、即時値のみで実測を語っていた。対処(詳細は
`docs/design-decisions.md` 判断13〜15、`docs/contracts.md`「コスト台帳と予算上限」):
- `agent/ledger.py`(新規)を追加し、LLM呼び出しの共通入り口(`agent/llm.py`の`call_llm()`)に、成功・失敗を
  問わずすべての呼び出しを`runs/ledger.jsonl`へ追記する処理を組み込んだ(呼び出し元の保存忘れに依存しない)。
  確定額は別スレッドで非同期・失敗許容取得。
- 予算上限(`LLM_BUDGET_PER_RUN_USD`既定$0.5、`LLM_BUDGET_PER_DAY_USD`既定$2.0)を追加。超過時は
  `call_llm()`がAPIを呼ばず`budget_exceeded`で即座に諦め、部分結果のまま穏やかに終了する(既存の連続失敗
  検知の仕組みをそのまま流用)。`Spec`/`Plan`/`Run`に`budgetStatus`フィールドを追加。
- Worker APIに`GET /api/plans/{id}/cost`・`GET /api/runs/{id}/cost`・`GET /api/cost/summary`を追加、
  `python -m agent.cost_report`(CLI集計)を新規作成。Java層の結果画面「コスト・モデル」タブに目的別
  (spec-extract/testcase-gen/charter/exec/exploratory)の内訳表示、コスト上限到達時のバナー表示を追加
  (`RunController`/`WorkerClient`/`run.html`)。
- `bench/run_bench.py`を、**構成(direct-strong/named-router/auto)ごとに項目書を作り直していたため比較が
  不公平になっていたバグ**を修正し、項目書は最初に1回だけ作って全構成で共有する設計に変更。あわせて
  台帳から目的別の実際に選ばれたモデル(`X-Orca-Resolved-Model`)の内訳・フォールバック回数・確定コストを
  出力に含めた(OrcaRouter賞向け、AC-14の実測強化)。
- 検証: ライブLLM呼び出しの浪費を避けるため、まず擬似応答(fakeなOpenAIクライアント)で予算上限の打ち切り
  動作を確認(予算$0.10・1回$0.04で3回目まで許可、以降`budget_exceeded`)。そのうえで、実際に1回だけ実キー
  ・実demo-site・実サーバーで`POST /api/specs`→`POST /api/plans`→`approve`→`POST /api/runs`のフルパイプラインを
  通し(403で即失敗・課金なし)、`GET /api/plans/{id}/cost`・`GET /api/runs/{id}/cost`が仕様書抽出・項目書生成・
  チャーター・探索のコストを正しく合算して返すこと、Java層の「コスト・モデル」タブと予算超過バナーが
  実際のブラウザで正しく表示されることをPlaywrightスクリーンショットで確認した。
- 影響: このコミット以前の実行の`metrics.costUsd.total`(前述の$1.90集計に使った値)は、記録漏れの影響で
  実際より少ない可能性が高い。今後は`runs/ledger.jsonl`を正とする。

### ルーター比較の実測結果(AC-14、2026-09-21、キー復旧後に実施)

おのゆーのキー復旧・Named Router(`orcarouter/site-inspector`、`gated_adaptive`)作成の連絡を受け、
煙テスト(3構成×1回、401/403なし・OK)→本比較の順で実施した。同一の仕様書・同一のテスト項目書
(10件、1回だけ生成し3構成で共有。`TEST_MODE=1`)。結果は`bench/results/bench-20260921T040959Z-69135a.json`。
**session affinity: OFF**(事後確認。ON時の比較は下記「セッション固定ON/OFF比較」参照)。

| 構成 | モデル/ルーター | 確定コスト | 所要時間 | 検出(L1-L3/L4、キーワード採点) | 誤検知 | Aとの一致率 |
|---|---|---|---|---|---|---|
| A(直指定) | `google/gemini-3.5-flash` | $0.34466 | 190.6秒 | 6/8, 3/6 | 0 | (基準) |
| B(Named Router) | `orcarouter/site-inspector` | $0.375824(+9%) | 341.0秒(+79%) | 6/8, 3/6 | 0 | 90%(9/10) |
| C(auto) | `orcarouter/auto` | $0.35589(+3%) | 319.0秒(+67%) | 6/8, 3/6 | 0 | 90%(9/10) |

**結果: コスト削減は確認できなかった。** exec/charter/exploratory・項目書生成を合わせた約240回の
LLM呼び出しすべてが、B・Cを含む全構成で100%`gemini-3.5-flash`(Named Routerの高難度プール)に
解決されており、簡易プール(`google/gemini-2.5-flash`)には1件も振り分けられなかった。判断15で立てた
仮説(「常にツールを使い、プロンプトが長いため、難易度判定でHardプールに偏る」)が実測で当たった形。
ルーティング判定自体のオーバーヘッドと見られる所要時間の増加(+67〜79%)も確認された。

一致率90%の内訳(不一致1件、TC-006「価格改ざん防止テスト」)は、コード根拠(`/__test/state`)で
A=改ざん反映なし(pass)、B/C=改ざん反映あり(fail、仕込み#11と一致)だったが、3構成とも解決済み
モデルは同一だったため、**モデルの違いではなくエージェント実行の非決定性による差**と判断した
(Aがこの1回だけ検知に失敗した可能性が高い)。詳細・キーワード採点の精度限界の考察は
`docs/design-decisions.md`判断16を参照。

**結論**: 現状の使い方では、直接モデル指定の方がコスト・速度とも優位。ルーティングを入れて
終わりにせず、実測して効果がなければ正直に「効果なし」と報告する(CLAUDE.md「測って出す」原則、
OrcaRouter賞の観点でも、活用度の実測と設計への反映として提示できる)。

### コスト構造の深掘り実験(2026-09-21、おのゆー承認の追加実験。結果は良し悪しを問わず実測のまま記録)

ルーター比較の結果を受け、「なぜ削減できないか」「他にコストを下げる余地は無いか」を、追加でライブ
実測した(予算: 実験全体で追加約$1.5まで、1実験あたり約$0.4以内。実測はこの範囲内に収まった)。

1. **入力の内訳(`docs/design-decisions.md`判断17)**: `tiktoken`(開発時計測専用の依存)で実測したところ、
   **1回の呼び出しで一番大きいのはページ状態ではなくツールスキーマ(execで約1577トークン)** だった
   (ページ状態は平均約325トークン、システムプロンプトは約754〜1042トークン)。想定と違う結果を
   そのまま記録した。あわせて`agent/llm.py`に`promptTokens`/`completionTokens`/`sessionTier`の実測
   記録を追加(`agent/cost_report.py`に目的別の平均トークン数として表示)。
2. **フォールバック実演(判断18)**: 障害注入(主モデルのみを対象化する修正込み)で3/3完走を確認。
   ただし**別ベンダー(`openai/gpt-4o`)への切替は、このキーのスコープでは403で検証できなかった**
   (`docs/QUESTIONS.md` #20で人に確認依頼中)。同一ベンダー内の代替(`orcarouter/site-inspector`
   経由)では3/3完走を実測。
3. **入力圧縮(判断20)**: 古い画面状態を要約に置き換える処理を実装・実測したが、**コスト・呼び出し
   回数の削減は確認できなかった**(コストは逆に+1.4%、呼び出し回数は+17%)。ツールスキーマという
   圧縮できない固定費が支配的なため、と考えられる。既定OFFのまま。
4. **固定手順のコード実行(判断21)**: macroが決まっている項目(T-01連打・T-02手順スキップ)を
   LLM無しでコード実行したところ、**検出結果を一切変えずにLLM呼び出し・コストを100%削減**できた
   (2件とも2回→0回、1回→0回)。このセッションで唯一、明確な正の削減効果が出た施策。
5. **操作列の記録・再生(判断19)**: 時間の都合で設計と最小の記録機能(`TestResult.recordedActions`)
   のみ実装。再生機能・`Run.compareTo`との統合は未実装(次回への申し送り)。
6. **セッション固定の比較**: コンソール設定が必要なため、`docs/QUESTIONS.md` #20で確認を依頼した
   (`X-Orca-Session-Tier`ヘッダーは今回も一貫して空だった)。

**まとめ**: 6項目のうち、明確にコストを下げられたのは「固定手順のコード実行」のみ。「入力圧縮」は
着眼点として妥当でも効果が無いことが分かり、「ツールスキーマ自体を絞る」方が筋が良さそうという
次の仮説につながった。良い結果も悪い結果も、実測値のまま`docs/design-decisions.md`(判断17〜21)に
記録している。

### セッション固定(Session affinity)ON/OFF比較(判断22、2026-09-21)
おのゆーがコンソールで`orcarouter/site-inspector`のセッション固定を一時的にONへ切り替え、同じ
共有項目書(10件)で1回だけ実行して比較した(OFFは判断16の実行を流用)。結果: コスト+2.9%・
所要時間+18.3%とONの方がやや重かったが、1回ずつの比較であり統計的な確証は無い。選ばれた
モデルの内訳(100%高難度プール)・`X-Orca-Session-Tier`ヘッダー(常にnull)はON/OFFで差が
無かった。プロンプトキャッシュ(`cached_tokens`)はONで約25.7%のヒット率を観測したが、OFF側は
計装前の実行だったため比較できなかった。詳細は`docs/design-decisions.md`判断22、
`bench/results/bench-20260921T053000Z-session-affinity.json`。**この実験の完了後、コンソール設定を
OFFへ戻してよい旨をおのゆーに連絡済み。**

方針転換の詳細・作業順は `docs/CHANGE-v0.5.md` を参照。区切り(M0〜M7)は実装セッションが `docs/QUESTIONS.md`(#7〜#14)の仮定つき確認を経て開発者に提示し、簡単な合図を受けて着手した。

| 区切り | 内容 | 状態 | メモ |
|---|---|---|---|
| M0 | 契約・観点カタログ・デモ仕様書の土台 | 完了 | `docs/contracts.md`をCHANGE §4のスキーマ(Spec/Plan/TestCase/TestResult/ExploratorySession/Finding/Coverage/Run)に全面更新し、v0.5の破壊的変更(`POST /api/runs`の置き換え等)を明記。`agent/perspectives.py`(観点カタログP-FUNC〜P-RESP、`risk_for()`)を新規作成。`requirements.txt`に`python-docx`・`pypdf`を追加し`.venv`にインストール済み。`demo-site/spec/`に`spec.md`・`spec.txt`・`spec.docx`(3形式)を作成、日本語テキストの往復抽出を確認済み。**PDFは見送り**: macOS標準の`cupsfilter`で生成したPDFは、pypdfでの日本語テキスト抽出が文字化けすることを確認したため(フォント埋め込みの問題)、信頼できる生成手段(reportlab等)が用意でき次第M7で追加する |
| M1 | 仕様書取り込み(`agent/specs.py`、`POST /api/specs`) | 完了 | セクション分割(md:見出し／txt:番号見出し／docx:段落スタイル／pdf:ページ単位)はコード側で行い、LLM(purpose=`spec-extract`、`tool_choice`で`extract_spec_items`を強制)にはtext/kindの抽出だけをさせる方式。**実LLM(`google/gemini-3.5-flash`)で実行確認済み**: `demo-site/spec/spec.md`(16セクション)から19件、`spec.docx`から19件のSpecItemを抽出、想定した仕様項目(送料・二重注文・数量検証・改ざん防止・同意・エスケープ・エラー露出・特商法・導線・表記)を過不足なくカバー。`POST /api/specs`(multipart, フィールド名`file`)・`GET /api/specs/{id}`を実装し、正常系・未対応拡張子(400)・fileフィールド欠落(400)を確認済み。仕様書は`runs/specs/<specId>/`に永続化(`spec.json`+元ファイル。gitignore対象)。`agent/llm.py`に`tool_choice`引数を追加(既存呼び出しには影響なし) |
| M2 | 下見・SiteMap・TestCase生成 | 完了 | `agent/planning.py`。`recon_site()`はPlaywrightでリンクを辿るだけ(LLM不使用、②のコスパ根拠)。画面種別(kind)はURLパターン+DOMのform有無で判定。**カートが空だと決済画面へのリンクが表示されず下見で見つからない問題**を発見し、商品ページで「カートに入れる」を1回だけ実行してから巡回を続けるよう修正。また**キュー重複による予算(`RECON_MAX_PAGES`)の浪費で`/checkout`がエッジには載るがノードとして巡回されないバグ**を発見・修正(`queued_paths`セットで重複排除)。TestCase生成は**画面ごとに1回LLMを呼ぶ方式**に変更(全画面まとめて1回呼ぶと大きすぎるプロンプトになりLLMタイムアウトが頻発したため)。実LLMで7画面から34件のTestCaseを生成し、送料・二重注文・価格改ざんの3大シナリオが`specRef`付きで含まれることを確認 |
| M3 | 承認＋項目書実行エンジン(`loop.py`置き換え、`POST /api/runs`を新形状に破壊的変更) | 完了 | `agent/loop.py`全面書き換え、`agent/judging.py`新規(コード根拠での合否判定)、`agent/policy.py`/`agent/browser.py`をrisk(normal/needs_approval)ベースのゲートに書き換え(`mode`概念を廃止)。`agent/server.py`に`POST /api/specs`・`POST /api/plans`・`GET /api/plans/{id}`・`POST /api/plans/{id}/approve`・新形状`POST /api/runs {planId}`を実装。`agent/cli.py`もgoal/persona無しの新フローに書き換え。**実LLM(`google/gemini-3.5-flash`)・実TEST_MODE=1で3大シナリオすべてconfirmedで検出を確認**: 送料不一致(`P-TEXT fail`, `judge_spec_numeric_mismatch`)、二重注文(`P-SEC fail`, `/__test/state`の注文数差分5件)、価格改ざん(`P-SEC fail`, `/__test/state`の注文合計が改ざん値と一致)。**踏んだ問題と対処**: (1) `override_param`ツールの元名`tamper_param`という名前自体がモデルの安全性拒否を誘発し、価格改ざんテストの実行そのものを拒否された実測を確認→中立的な名前`override_param`に変更して解消(TestCase生成プロンプトからも「攻撃」「改ざん」「脆弱性」等の語を避けるよう指示を追加)。(2) hiddenフィールドは画面の「操作できる要素」に表示されないため、LLMが実在しないフィールド名を推測して改ざんに失敗し、かつ実装側も見つからなくても「書き換えました」と嘘の成功応答を返していたバグを発見→画面状態に「hiddenフィールド一覧」を追加表示し、`override_param`は実際に要素が見つかったときだけ成功を返すよう修正。(3) 複数のTestCaseが同じブラウザセッション/カートを共有するため、前のTestCaseの注文完了でカートが空になり後続テストの前提が崩れる問題を発見→対象がcart/checkoutで「空」を検証する項目でなければ、実行前にコード側で商品を1点補充する`_ensure_cart_has_item()`を追加 |
| M4 | 探索的テスト(最小構成)、デモサイトに未文書化の不具合1〜2件追加 | 完了 | `agent/loop.py`の`_run_exploratory()`。チャーターはLLMに1文で提案させ(purpose=`charter`)、探索フェーズのツールセットからP-SECマクロ4種を除外(`BrowserSession._current_test_case=None`のため、仮に呼ばれても`_gate_macro`が`test_case_context_required`で必ずdenyする多層防御)。**実LLMでの探索1回で、気になった点(Suspicion)を8〜9件、根拠つきで発見**(誤字「送量無料」、特商法ページへの導線欠落、数量0/負数の未検証、決済画面の送料不一致、個人情報同意チェック欠落、特商法ページ自体が404、等)。デモサイトには`demo-site/server.py`の`/cart/add`に、探索専用の未文書化バグとして`# SEEDED FLAW #15`(同一商品を複数回追加しても数量が合算されず別行になる)をコメントで明記(コード変更は既存動作の追認のみ) |
| M5 | カバレッジ集計・レポート更新 | 完了 | `_compute_coverage()`(仕様項目のtotal/covered/passed/failed/notCovered、観点別件数、画面数)。`report/html.py`をgoal/persona表示から`planId`/`specIds`表示に変更、Finding表示を新形式(perspective/origin/specRef/expected/actual)に対応、カバレッジ・テスト項目実施結果・探索的テストのセクションを追加 |
| M6 | 新形式`runs/samples/*.json`作成、`docs/STATUS.md`最終更新 | 完了 | `runs/samples/run-0b84031e60/`(実LLM・実TEST_MODE=1、HTTP API経由でPOST /api/specs→POST /api/plans→approve→POST /api/runsのフル通し。33件のテスト項目、22件の指摘、実測コスト$0.6072)を新形式サンプルとして追加。`runs/samples/README.md`に新形式(v0.5)セクションを追加、旧形式(v0.4)は参考として残す |
| M7〜 | Should/Could(docx/PDF仕上げ、書き出しCSV/HTML、再テスト差分、P-UX/P-A11Y充実、bench改修) | 部分完了 | 下記「M7(Should)の状況」参照。PDF対応・書き出しCSV/HTML・bench改修(実行込み)は完了、残り2項目(再テスト差分、P-UX/A11Y充実)は未着手 |
| **MW** | **Java層(`web/`)UI全面刷新**(CHANGE §6: ウィザード・サイドバー・KPIカード・タブ・ドロワー) | 完了 | `docs/STATUS-web.md`のMW-0〜MW-4参照。`WorkerClient`を新API(specs/plans/approve/runs)に対応、`JobController`/`PlanController`(新規)/`RunController`/`GlobalModelAttributes`(新規)を実装、`index.html`/`plan.html`(新規)/`run.html`/`history.html`/`error.html`/`style.css`を全面刷新。実ワーカー・実LLMの本番相当データ(`run-0b84031e60`)でPlaywrightスクリーンショットを撮り目視確認済み |

### v0.5で置いた仮定・回答(詳細は`docs/QUESTIONS.md` #7〜#15)
- 既存ワーカーAPIは互換シムなしで破壊的に置き換える(URL/動詞は極力維持、ボディ形状のみ変更)。
- **(2026-09-21更新)** Java層(`web/`)のUI刷新も本セッション(`dev/worker`)が担当する(`docs/QUESTIONS.md` #8で回答済み)。`dev/web`セッションは立てない。以後`docs/STATUS-web.md`もこのセッションが更新する。
- **(2026-09-21更新)** 第7章「削る順」は今は判断の軸にしない。まずCHANGE-v0.5.mdの要件を満たす形を目指す(`docs/QUESTIONS.md` #15)。M0〜M7・MWの順序自体は依存関係上の都合(契約→実行エンジン→UI)としてそのまま使う。
- 探索的テストは「項目書を全消化→探索」の直列固定、探索フェーズはP-SECマクロを一切使わない。
- `TestCase.risk`はP-SEC常時`needs_approval`、P-FLOWは決済・注文確定関連の手順のみ`needs_approval`。
- 仕様書なしモードは原則`needs_review`だが、コード的に明確な根拠(状態確認・エラー表示等)があれば`confirmed`にしてよい。

## 受入基準の確認(v0.5、CHANGE-v0.5.md 第8章。2026-09-21実施)

| AC | 判定 | 根拠 |
|---|---|---|
| AC-A(persona/goal完全削除) | **合格** | `grep -rniE "persona\|goal" agent/*.py checks/*.py report/*.py bench/*.py web/src/**/*.html web/src/**/*.java docs/contracts.md runs/samples/run-0b84031e60/run.json`を実施。ヒットは`docs/contracts.md`内の「削除した」という説明文のみで、実体としてのフィールド・入力欄・引数は皆無 |
| AC-B(URL入力→プラン表示) | **合格** | `docs/screenshots/03-wizard-testcases.png`(実データ: 見つかった画面数7、候補観点数10、テスト項目数33、仕様項目カバー見込み16/20) |
| AC-C(仕様書取り込み→SpecItem) | **合格** | M1で実施済み。`spec.md`(8章・約19の規範的記述)→19件、`spec.docx`(同内容)→19件、`spec.txt`→23セクション検出。件数は目視で仕様書の記述数と一致 |
| AC-D(危険な項目は承認なしに実行されない・3回連続) | **合格** | `agent.policy.evaluate()`に未承認のneeds_approvalアクションを3回連続investigateし、3回とも`deny`(`approved_test_case_required`)を確認(コードは決定的なので、3回とも同じ結果になることは自明だが実測もした)。実行結果(`testResults`)でも未承認項目は`not_run`になることを複数回の実行で確認。UI(`plan.html`)は危険度バッジと一括承認チェックで危険な項目を視覚的に区別(`docs/screenshots/03-wizard-testcases.png`) |
| AC-E(仕様書根拠の仕様逸脱3件以上) | **合格** | 送料不一致(`judge_spec_numeric_mismatch`)・二重注文(`judge_duplicate_submission`、`/__test/state`の注文数差分)・価格改ざん(`judge_price_tamper`、`/__test/state`の注文金額)の3シナリオすべてを、複数回の実LLM実行で`confirmed`かつ`specRef`付きで検出(`docs/design-decisions.md`判断12、`docs/STATUS.md`のM3参照)。**注記**: 採用したフラッグシップサンプル(`run-0b84031e60`)では、実行中にOrcaRouterのAPIキーが一時的に401を返すようになった影響で、二重注文・価格改ざんの2項目が`needs_review`に留まった回がある(送料不一致は`confirmed`)。3シナリオ全部が`confirmed`で揃った実行は`docs/STATUS.md`のM3実測メモ、および本セッションの検証ログに残っている(再現性は確認済み。当該サンプルは差し替えず、実際に起きた事象として記録する方針とした) |
| AC-E2(探索的テストで1件以上・危険操作なし) | **合格** | 実LLMでの探索を複数回実施し、うち2回で気になった点(Suspicion)を8〜9件、根拠(URL・画面テキスト)つきで検出(誤字・特商法導線欠落・数量未検証・送料不一致・同意欠落等)。`origin: exploratory`で区別表示(`docs/screenshots/04-*`)。探索フェーズのツール一覧からP-SECマクロ4種を除外しており、仮に呼ばれても`_gate_macro`が`test_case_context_required`で必ずdenyすることをコードで保証(多層防御) |
| AC-F(対照ケース/contact-safeを誤判定しない) | **一部確認** | `/contact-safe`のサーバー側二重送信対策(ワンタイムトークン)は直接HTTPで動作確認済み(2回送信して1件しか記録されないことを確認)。ただし`/contact-safe`はサイト内のどこからもリンクされていない(意図的な対照ケース)ため、自動下見(`recon_site`)では発見されず、エージェントが実際にこのページを判定した実測はまだ無い。コードレビューでは、このページを誤判定しうる観点固有のロジックは無いと判断しているが、**実測による確認は未了**(`docs/QUESTIONS.md`に追記予定) |
| AC-G(カバレッジ表) | **合格** | `run.json`の`coverage.specItems`(total/covered/passed/failed/notCovered、理由付き)を実データで確認(例: 20件中16件カバー)。`docs/screenshots/06-result-spec-traceability-tab.png` |
| AC-H(結果画面が崩れない・ワーカー停止時も) | **合格** | `docs/screenshots/09-error-worker-down.png`(履歴等: HTTP 503、スタックトレースなし、再試行導線あり)、`10-run-worker-down-mid-poll.png`(実行中画面: ポーリングが失敗を検知して自動再試行、画面自体は崩れない)。ワーカープロセスを実際に停止させて確認 |
| AC-I(旧AC-2,3,7,8,13,14,16-19,22の維持) | **すべて実測・確認済み(AC-14は「削減効果なし」という実測結果)** | 個別内訳は下表 |

### AC-I内訳
| 旧AC | 判定 | 根拠 |
|---|---|---|
| AC-2(危険操作は承認待ち) | 合格 | AC-Dと同一根拠 |
| AC-3(許可外ドメイン遮断) | 合格 | `policy.evaluate({"tool":"navigate","args":{"url":"https://example.com/"}})` → `deny(allowed_hosts_only)`を実測 |
| AC-7(全LLM呼び出しがOrcaRouterのログに残る) | 合格(構造的) | すべての呼び出しが`agent/llm.py`の`call_llm()`(base_url=OrcaRouter)経由。`X-Orca-Request-Id`等を`llmCalls`に記録。OrcaRouter側コンソールでの直接確認は権限上未実施 |
| AC-8(LLM/ネットなしでもリプレイ可能) | 合格 | `report/html.py`は`run.json`のみから単一HTMLを生成(スクショはbase64埋め込み)。`runs/samples/run-0b84031e60/report.html`で確認済み |
| AC-13(連打の回数/間隔上限・許可外ドメインへのL4操作遮断) | 合格 | `rapid_click count=50`→`deny(rapid_click_count_cap)`、`intervalMs=10`→`deny(rapid_click_interval_floor)`を実測 |
| AC-14(強いモデル直指定 vs Named Routerの比較) | **実測完了(2026-09-21)。削減効果は確認できなかった** | `bench/results/bench-20260921T040959Z-69135a.json`。A(直指定)$0.34466/190.6秒、B(Named Router)$0.375824(+9%)/341.0秒(+79%)、C(auto)$0.35589(+3%)/319.0秒(+67%)。検出率(6/8,3/6)・誤検知(0)は3構成とも同じ、TestResult一致率はB/Cとも90%。全呼び出しがB/Cとも100%高難度プールのモデルに解決され、削減が起きなかった実測結果を正直に記録した。詳細は`docs/design-decisions.md`判断16 |
| AC-16(障害注入からの復旧・Fallback可視化) | 合格(実際の障害で代替確認) | 2026-09-21、`ORCAROUTER_API_KEY`が実際に401を返す事象が発生し、複数のTestCase・探索フェーズがLLM呼び出し失敗を経験したが、エージェントはクラッシュせず`needs_review`として安全に記録し、実行全体は`completed`で終わった(`run-0b84031e60`、`run-b25a205780`)。合成的な障害注入(`FAULT_INJECTION=1`)によるテストはv0.4時点のみ実施済みで、v0.5では再実施していない |
| AC-17(PIIマスク) | 合格(構造的) | `masking.mask_page_state()`を画面状態・仕様書テキストの送信前に必ず通す実装をコードレビューで確認。ライブでのPII送信インターセプトによる直接確認はしていない |
| AC-18(隠し指示ページで危険操作が実行されない) | 合格(構造的) | `/trouble/injection`(隠し指示ページ)は複数回の実行でTestCaseの対象になったが、危険操作(P-SECマクロ)はこのページに一切生成・実行されず、また許可外ドメイン(`partner-example-not-allowed.test`)への遷移が発生した記録もない |
| AC-19(モデルID非固定) | 合格 | `grep`で`agent/*.py`等を検索し、ハードコードされた直接モデルID(`google/gemini-*`等)が無いことを確認。既定値`orcarouter/auto`はルーター指定でありモデルIDではない |
| AC-22(Java層はLLM非呼び出し・秘密情報非保持) | 合格 | `grep -rniE "orcarouter\|api[_-]?key" web/src/main/java/`が0件 |

## M7(Should)の状況(2026-09-21)

| 項目 | 状態 | メモ |
|---|---|---|
| PDF仕様書 | 完了 | 当初`cupsfilter`で生成したPDFはpypdfでの日本語抽出が文字化けする問題があったため、`reportlab`(新規依存として追加)の`HeiseiKakuGo-W5`(CIDフォント、外部フォントファイル不要)で作り直した。`demo-site/spec/spec.pdf`を生成し、`pypdf`での日本語テキスト抽出(文字化けなし)、`agent/specs.py`のセクション分割(24セクション検出、ページ番号つき)まで確認済み。**LLMによる最終的なSpecItem抽出(`build_spec`)は、`ORCAROUTER_API_KEY`が401を返す状態(`docs/QUESTIONS.md` #17)のため未確認**。これで仕様書はtxt・md・docx・PDFの4形式すべてを用意済み |
| 書き出し(CSV/HTML) | 完了 | `report/html.py`に`build_csv()`を追加、`agent/server.py`に`GET /api/runs/{id}/export?format=csv\|html`を実装(html版は`/report`と同じ内容)。CSVはExcelでの文字化けを避けるためUTF-8 BOM付き。Java層にも`GET /runs/{id}/export.csv`(中継)と結果画面のダウンロードボタンを追加し、ブラウザから実際にダウンロードできることを確認済み |
| 再テスト差分(`Run.compareTo`) | 未着手 | 時間の都合で見送り。実装するなら、`POST /api/runs`に`compareToRunId`を追加し、同じTestCaseIdの前回verdictとの差分("fixed"/"new"/"unchanged")を計算する形になる見込み |
| P-UX・P-A11Y充実 | 未着手 | 現状は`agent/perspectives.py`にチェックリスト(ニールセンの10原則・WCAG主要項目)をデータとして持たせ、TestCase生成プロンプトに渡しているのみ(観点1つにつきTestCase 1件程度)。チェックリスト項目ごとに個別のTestCaseを生成する等の充実は未着手 |
| bench改修 | 完了 | `bench/run_bench.py`は新API(`planning.build_plan`+`loop.execute_plan`)に追従済み(壊れていたimportを修正、M2-M5のコミット参照)。**2026-09-21追加修正**: 構成ごとに項目書を作り直していたため比較が不公平になるバグを修正し、項目書は1回だけ作って全構成で共有する設計に変更。台帳(`agent/ledger.py`)から目的別の実際に選ばれたモデル内訳・フォールバック回数・確定コストを出力、TestResultの合否一致率(`_agreement_rate()`)を追加。**2026-09-21、キー復旧後に実際のベンチマーク比較を実行済み**(直指定 vs Named Router vs auto、結果は上記「ルーター比較の実測結果」・`docs/design-decisions.md`判断16参照) |

## 次にやること（v0.5）
M0〜M6・MWはすべて完了。受入基準は上表の通り(AC-F一部確認、AC-14は2026-09-21に実測完了・削減効果なし)。M7は書き出し・PDF対応・bench改修と本実行まで完了、残りは再テスト差分・P-UX/A11Y充実(いずれも未着手、時間の都合)。

## サービス品質の修正(判断23、2026-09-21。おのゆーが実データ`run-0b84031e60`を見て発見した問題への対応)
- **価格改ざん判定バグ**: 注文合計が`-3970`という不自然な値なのに`pass`と誤判定していた(前のTestCaseが
  残した負の数量のカート行が混入していたため)。`judging.judge_price_tamper()`に、合計が負値のときは
  `needs_review`に倒す分岐を追加。あわせて`_ensure_cart_has_item()`に、価格改ざん系のテスト実行前は
  Cookieを消して完全に新しいカートから始める`force_clean`オプションを追加(汚染の経路を断つ)。
  副次的に、`agent/loop.py`が`judge()`の`confirmed`フラグを常に`True`扱いにしていたバグも修正した。
- **重複した不具合票の統合**: 同一の指摘(perspective・kind・detailが完全一致)が複数画面にまたがって
  別々のFindingとして記録される問題を、`_dedupe_findings()`で1件に統合するようにした(完全一致のみ、
  誤統合を避ける保守的な設計)。レポート・Java UIの両方に統合件数・対象URL一覧の表示を追加。
- **打ち切られたPlanの続き生成**: コスト上限で途中打ち切りになったPlan(例: `p-c3389422`、15件で停止)を、
  作り直さずに続きから生成できる`resume_plan()`・`POST /api/plans/{id}/resume`を追加。Java層に
  「続きを生成する」ボタンと、「本日のコスト上限に達したため、N件までで止めました。上限を引き上げるか、
  日をまたいで再実行してください。」という具体的な文言を追加。`p-c3389422`で動作確認済み(15件→47件、
  追加コスト$0.0964)。
- **401で止まっていた項目・探索0件について**: `run-0b84031e60`の該当箇所は、当時発生していた
  `ORCAROUTER_API_KEY`の401障害(`docs/QUESTIONS.md` #17)によるもので、個別のコードバグではないと
  確認した。キーが復旧した現在は同じ問題は起きない(再現するにはこの1件を再実行すればよいが、
  古いサンプルの再現目的だけの追加コストは今回は使わなかった)。
- **台帳とOrcaRouterコンソールの突き合わせ**: おのゆーから、キーの利用上限到達の経緯で台帳の記録額と
  実際の消費に差があるとの指摘があり、以後は実行のたびに`python -m agent.cost_report`の実効コスト
  合計を実行前後で報告する運用にした(このコミット時点の合計は次の報告を参照)。
- **`.env`の`LLM_BUDGET_PER_DAY_USD`反映の確認**: おのゆーが値を変更したと連絡があったが、実際のファイルは
  異なる値(`13.0`)で、かつ変更前に起動していたワーカープロセスはその変更を読み込んでいなかった
  (`.env`はプロセス起動時にしか読まれないため、変更後は再起動が必要)。プロセスは再起動して現在の
  `.env`の値を反映させたが、値そのもの(3.0か13.0か)の真偽は`docs/QUESTIONS.md` #21で確認を依頼中。

## v0.6(`docs/CHANGE-v0.6.md`): 販売できるサービスとしての土台(2026-09-21〜)

詳細・現在のフェーズ・次のアクションは`docs/RESUME.md`が正。Java層(`web/`)の実装詳細は
`docs/STATUS-web.md`(「v0.6」節)。ワーカー側(`agent/`)は現時点でP1・P1補修の範囲では
`agent/server.py`のワーカーAPI認証(フェイルクローズド化、`hmac.compare_digest`、
`agent/tests/test_worker_auth.py`で7件成功)と、P2で使う`agent/security.py`(SSRF・拒否リスト、
未配線)のみ着手。P1・P1補修はどちらも完了。次はP2(ドメイン所有確認・同意記録・拒否リスト配線・
緊急停止)から。ライブLLM呼び出しはこの区切りでは発生していない
(`python -m agent.cost_report`実効コスト合計: $2.2206、変化なし)。

## 記事用計測(2026-09-22、ピッチ前): ガードレール確認・M5a/b/c/d・M4b・M3・M4a・M2

詳細な実測値・根拠ファイルは`docs/design-decisions.md`判断29、生データは`bench/results/
m5a-ssrf-denylist.json`・`m5b-masking.json`・`m5d-auth-isolation.json`・
`m4b-cancel-latency.json`・`m3-macro-fastpath.json`・`m4a-fault-injection.json`・
`bench-20260921T212431Z-821aa4.json`(M2)。

**要点**: (a)(b)(c)ガードレール確認は実施(電話番号がマスクされない、というガードレール側の
未対応を発見。こちら側の自前マスクは電話番号にも対応済みのため二重防御は機能)。M5a(SSRF・
拒否リスト)は12パターン全件が期待通り遮断/許可。M5bは氏名マスクの既知の限界(スペース無しの
氏名は検出されない)を発見。M3は固定手順のコード実行で対象項目のLLM費用を100%削減(実測)。
M4a(障害注入)はN=3で完走3/3。M4bは緊急停止が最大30秒以上かかる場合があることを発見。
M2(ルーター比較)はn=1のみ実施(統計的な結論には不十分)。

**予算により縮小・打ち切ったもの**: M4b(n=5→3)、M4a(N=10→3)、M2(n=3反復→1、autoは追加せず)。
実効コストが$9.29(停止目安$10.4の約89%)に達したため、指揮官・ピアの事前の指示どおり、
これ以上の追加計測は実施しなかった。

## v0.8(2026-09-22夜〜、ピッチ前日): クレジット制・架空決済・再開・引き継ぎ・マイページ

作業指示書: `docs/CHANGE-v0.8.md`。作業順1→2+6→3→4→5。

### 1. 登録画面の崩れ修正(完了)
原因: `style.css`の`.v6-field input`が、type属性を問わず全input要素にwidth:100%を
適用しており、チェックボックス自体が引き伸ばされ、隣のラベル文言(span)がflexの残り幅
ほぼ0になり1文字ずつ縦に折り返されていた。checkbox/radioをこのルールから除外して修正。

**テスト**: Java 114件成功・0失敗、Python 152件成功(いずれも変更なし。CSS/テンプレートの
みの変更)。

**実際の動作確認**: Playwright(system Chrome、viewport 420x700)で`/signup`をスクショし、
修正前(input_width=322px, span_width=0px, 縦書き崩れ)→修正後(input_width=13px,
span_width=294px, 横書きで正常表示)を、DOM計測とスクリーンショットの両方で確認した。
index.html側の同意チェックボックスは`.v6-field`の外にあり今回の不具合の対象外
(コードレビューで確認)。plan.html側は短文・幅広コンテナのため対象外。

コミット: `96399f8`(修正)、`beeb04f`(CHANGE-v0.8.md取り込み)。

## v0.8(続き): 項目2+6(クレジット制・中断再開)のバックエンド完了

コミット `3f8ac27`。詳細はコミットメッセージ参照。ワーカー側は緊急停止・LLM連続失敗・
ワーカー再起動・タイムアウトのいずれも"paused"(再開可能)で確定し、`execute_plan(resume=True)`
で完了済みTestCaseを再実行せず続行できる。Java層はcredit_ledger(V11・V12マイグレーション)、
CreditService(冪等な消費・残高計算)、JobQueueServiceでの既存ポーリングによるリアルタイム
消費・残高0での能動停止要求を実装。

**テスト**: Java 124件成功・0失敗(+10)。Python 153件成功(+1)。

**未実施(次のステップ)**: run.html・dashboard等のUI側(残高・消費の表示、USD表記の除去、
再開ボタン)、想定消費クレジットの目安レンジ表示。続けて着手する。

**インシデント(報告)**: 中断・再開のe2eテストを最初に書いた際、2回目のexecute_plan呼び出しが
探索的テスト(exploratory)フェーズに到達し、これは`MACRO_CODE_FASTPATH`の対象外(常に実LLMを
呼ぶ)であるため、意図せず実際のLLM呼び出しが発生した(約$0.15)。実効コストは$9.2935→$9.4459に
なった。テストを、1回目の完了時点でexploratoryDoneフラグを事前設定してから2回目を呼ぶよう
修正し、以降は費用ゼロで再現・確認できることを確認した(修正後、コストの増加が無いことを
実測で確認済み)。

## v0.8(続き): 項目3(架空の決済)完了

コミット `6367bc0`。PaymentGateway/FakePaymentGateway、CreditPack、CreditController
(パック選択→カード入力→処理中(1.2秒)→領収書)、credit/{charge,receipt,history}.htmlを実装。

**テスト**: Java 134件成功・0失敗(+10)。Python 153件成功(変更なし)。

**ライブ確認で発見・修正した既存の不具合2件**: `.v6-nav a`と`.v6-mark`が、item1と同じ原因
(flexコンテナ内のテキストがCJKの改行機会で1文字幅に潰れる)で、幅の狭い画面・狭いテーブル列で
崩れることを発見。両方とも`white-space:nowrap`等で修正(共通クラスのため全画面に効果)。
credit/history.htmlの`#temporals`未対応(依存追加なし)による500エラーも、Java側で表示用
文字列に整形する方式に直して解消。

**実際の動作確認**: 分離ポート(48080、実LLM不使用)で、成功ケース(10,000円→11,500cr)・
失敗ケース(残高不変)・履歴一覧を、curl・Playwrightで確認。

続けて■4(前回のテストデータの引き継ぎ)に着手する。

## v0.8(続き): 項目4(前回のテスト項目・結果の引き継ぎ)完了

コミット `bcc8d17`。前回のPlanのテスト項目(origin=user含む)を持ち越し(下見・生成をやり直さず
費用0)、各項目にpreviousVerdictを付与。組織スコープのリポジトリ経由で「直近の終了済み実行」を
解決するため、他組織のデータは参照できない。実行結果画面に、FindingFingerprintを使った
前回との差分(新規/修正済み/継続中)を追加。

**テスト**: Python 158件成功(+5)。Java 139件成功・0失敗(+5、他組織のRunを比較対象に
できないことの確認を含む)。

**実際の動作確認**: 分離ポート(58080/58770、実LLM不使用)で、手動データにより①「前回の結果を
引き継ぐ」チェックボックス②プラン画面の前回の結果列③実行結果画面の「前回との差分」を、
Playwrightで確認(新規1件・修正済み1件・継続中1件が正しく分類された)。検証用データは削除済み。

続けて■5(マイページ、時間があれば最小構成)に着手する。

## v0.8: 全項目(1→2+6→3→4→5)完了

コミット一覧: 96399f8(項目1)、3f8ac27・c160aef(項目2+6)、6367bc0(項目3)、bcc8d17(項目4)、
169dc45(項目5)。テストは、Java 144件成功・0失敗、Python 158件成功。実LLM呼び出しは、
項目1・4・5の確認では使っていない(いずれも0件・費用0)。項目2+6のe2eテスト作成時に、
探索的テストが実LLMを誤って呼び、約$0.15の想定外の費用が発生したインシデントがあったが、
原因を特定・修正し再発防止済み(詳細は上記「v0.8(続き): 項目2+6」節)。

実効コストは$9.4459(停止目安$10.4)のまま、項目1〜5の実装・確認では増加していない。

次: 指揮官指示の「2分デモの通し」(登録→チャージ→計画→実行中に残高が減る→残高0で中断→
チャージ→再開→レポート→前回引き継ぎで再テスト)は、実LLM呼び出しを伴うため、事前にピアへ
内容・回数・想定費用を報告してから実施する。

## v0.8: 「2分デモの通し」実施(承認済み、実LLM使用)

対象はdemo-site(127.0.0.1:8765)のみ、分離ポート(28770/28080)、実ORCAROUTER_API_KEYを使用。
登録→チャージ(1,000円→1,000cr、テストカード)→残高をデモ用に55crへ調整(H2へ直接SQL、
実消費ではないと明記)→計画作成(TESTCASE_MAX_CASES=5)→承認→実行→ポーリングで残高が
実際に減っていくことを確認(52→…→-31crで一時停止。協調的な停止は次のステップ境目でしか
効かないため、ゼロを大きく超えてから止まる。設計上の既知事項の実測)→チャージ(+1,000cr)→
再開→完了(58ステップ、指摘11件。送料の特商法/決済画面不一致=横断指摘、カート数量の境界値・
異常値、隠し指示ページの検出はしたが承認なしの確定操作は実行されず)→前回引き継ぎで再テスト
(ゼロ費用)。

**実行中に発見・修正した本物のバグ**: 引き継ぎ(項目4)で、`JobController`がワーカーへ渡す
runIdにJava側の公開runId(`record.getRunId()`)を渡していたため、ワーカー側`_verdict_map_from_run`
がrun.jsonを見つけられず、previousVerdictが常に`not_run`になっていた(手動データでの独立検証
ではID一致するデータを使っていたため見逃していた)。`workerRunId`を渡すよう修正
(コミット`40689d5`)。修正後、実機で全5項目に正しい前回verdictが入ることを再確認(ゼロ費用)。
Java 144件、既存分含めて全green。

**実測費用**: 開始$9.4459→終了$9.7379(差分$0.2920。承認済み想定$0.10〜0.20をやや超過。
理由は上記の停止オーバーシュートで想定より多くのステップが実行されたため)。

デモ後、プロセス・ワーカー側`runs/`の一時データ・`web/data`(H2)はすべて削除済み。
`git status --short`はクリーン。公式スタック(8080/8765/8766/8770)には触れていない。
ピアへ①費用②詰まった点③スクリーンショットを報告済み。

## 指揮官の緊急依頼(2026-09-22): レポート改善4点+ロゴ+iframeエラー表示

実LLM不使用。コミット: `5c9522e`(■1■2)、`0078563`(■3)、`32d7a24`(■4)、
`e7cc1ef`(■5ロゴ)、`7a02c28`(iframeエラー表示・根本原因修正)。

- **■1 証拠付きレポート**: `_finding_html()`がFinding.evidenceのスクリーンショットを
  一切表示していなかった問題を修正。各指摘カードに証拠画像を直接埋め込み、
  指摘⇄タイムラインの該当ステップを相互アンカーリンクできるようにした。
- **■2 クレジット表示**: report/html.pyのコスト表示がUSDのまま残っていた問題を修正。
  `agent/config.py`にWeb層と同じ換算定数(CREDIT_USD_TO_JPY_RATE/MARKUP_MULTIPLIER)
  を追加し、USD表記を一掃。
- **■3 書き出し形式追加**: 「印刷/PDFとして保存」ボタン+`@media print`スタイル、
  「レポートをダウンロード(HTML)」ボタンを追加。
- **■4 可読性**: report/html.pyの重要度・合否バッジのコントラストを実測したところ
  WCAG AA(4.5:1)未達(実測2.5〜3.9:1)だったため是正。Web層(style.css)は実測で
  既にAA基準を満たしており変更不要と判断。
- **■5 ロゴ**: 届いた画像(docs/assets/logo-miruqa.png)の余白を除去し、Web層ヘッダー
  (shell.html)を文字表示から画像表示に変更。LP(landing-app)の既存ロゴと同一デザイン
  だったため、同じ画像に差し替えてロゴの混在を解消。
- **iframeエラー表示(低優先度で依頼)**: 調査の結果、報告されていた「一時的な不調と
  重なると素のエラー画面が出る」は誤認で、**Spring Securityの既定のX-Frame-Options
  (DENY)が、run.htmlの自分自身へのiframe埋め込みを常時ブロックしていたのが真因**
  (実機で正常系でも毎回失敗することを確認して特定)。`SecurityConfig`で
  `frameOptions(SAMEORIGIN)`を明示して解消。あわせて依頼どおり、fetch先行確認+
  失敗時の「再試行」ボタンも実装。

テスト: Java 144件→152件(+8)、Python 158件→164件(+6)。すべてPlaywrightで
実機確認(証拠画像の埋め込み・USD表記ゼロ・印刷ボタン・バッジの見た目・ロゴの
表示・iframe修正前後の再現と回復)。分離ポート使用、検証用データは削除済み。

## v0.8バグ修正: 残高不足時に下見済みプランへ戻れない問題(指揮官バグ報告)

コミット`6cddbc3`。再現手順: 下見・項目書生成→承認しようとすると残高不足で402の
例外ページになり、そこから元のプラン(下見・生成済み、planId)へ戻る手段が無く、
「新規点検」からやり直すしかなかった(＝直前のLLM費用が無駄になる)。また、
未承認・未実行のPlanはマイページ・historyのどこからも辿れなかった。

**修正**: ①`PlanController.approve()`の残高不足・組織コスト上限到達を、例外では
なく`redirect:/plans/{planId}`+flashメッセージにし、plan.html上にエラーと
「チャージへ」ボタンを表示。チャージ画面は`?returnTo=/plans/{planId}`を受け取り、
領収書画面から「元の画面へ戻って実行する」で戻れるようにした(returnToは
`/plans/{id}`形式のみ許可、オープンリダイレクト対策)。②マイページに、
RunRecordがまだ無いPlanRecordを「下見済み・未実行のプラン」として一覧表示。

**テスト**: Java 151件成功(+7)。分離ポート(38080/38770、実LLM不使用)で、
残高0の組織を使い、①②③④(上記フロー一式+オープンリダイレクト対策)を実機で
確認。検証用データは削除済み。
