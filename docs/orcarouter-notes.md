# OrcaRouter 疎通確認（WP0）

仕様のまとめは `CLAUDE.md` 第3章。ここには**実際に確認した結果**を書く。結果は別セッション（企画）にも共有される。

## 人がやること（Claude Codeにはできない）
- [ ] OrcaRouterに登録（GitHubサインイン。カード登録は不要）
- [ ] 3,000円分のクレジットを、Day1資料の専用リンクから受け取る
- [ ] APIキーを発行し、`.env` の `ORCAROUTER_API_KEY` に設定（コミットしない）。既存の `agent-ai/.env` に設定済みかもしれない（値は表示しない）
- [ ] コンソールで Named Router を作る（設定値は下の「Named Routerの設定値（案）」を、実測後に更新）
- [ ] Firewall・Guardrails・スコープ付きAPIキーが使えるか確認する（使えなければ、その旨を書く）

## 確認チェックリスト（実装側）
- [ ] `GET /v1/models`: 使えるモデル一覧（無料モデルのID、Union Alphaの有無、Vision対応）
- [ ] 通常の呼び出し（`orcarouter/free` または `-free`）と、`X-Orca-*` ヘッダーの取得（`with_raw_response`）
- [ ] ツール呼び出し（OpenAI形式 `tools`）と、構造化出力（JSON schema）
- [ ] Vision（画像入力）が通るか（無料モデルでも）。通らなければテキスト＋要素一覧を基本にする
- [ ] コスト取得: `X-OrcaRouter-Include-Cost: true` の `usage.cost_usd`、`GET /v1/generation?id=` の `total_cost`
- [ ] 無料モデルの拒否: `err_free_rate`（`retry_after_seconds`の有無）、`err_free_prompt_cap`（スクショ・プロンプトの上限値）、`err_free_access_denied`
- [ ] Fallback: `extra_body` の `models`（最大5）と `route: "fallback"`。`X-Orca-Fallback-Level`／`X-Orca-Fallback-Model` の確認
- [ ] Firewall評価API（`POST /api/v1/firewall/evaluate`）: リクエスト形式、専用キー、使えるプラン。使えない場合は自前 `PolicyGate`
- [ ] Guardrails: PIIマスク（email/phone/ssn）、カスタム正規表現、`llm_judge`、アクション種類（資料は5種、ドキュメントは block/mask/flag）
- [ ] スコープ付きAPIキー（予算上限・有効期限）、OrcaReplay、Request Logs の使い方
- [ ] 実際に選ばれたモデル（`X-Orca-Resolved-Model`）を `orcarouter/auto` で観察（遅延・コスト）

## 結果（ここに書く）
| 項目 | 結果 | 日時 |
|---|---|---|
| エンドポイント疎通(無効キー) | `https://api.orcarouter.ai/v1` へ到達。無効キーでは401が構造化JSONで返る。エンドポイントは生きている | 2026-09-20 |
| **エンドポイント疎通(有効キー)** | 疎通・通常呼び出し・ツール呼び出し・構造化出力・Vision(画像入力)すべて成功(直接モデルID経由)。コスト取得(インライン・`GET /v1/generation`)も成功 | 2026-09-20 |
| `GET /v1/models` | **9件のみ**返る: `orcarouter/free`・`orcarouter/fusion`・`orcarouter/fusion-flash`・`orcarouter/fusion-mini`・`orcarouter/auto`・`deepseek/deepseek-v4-flash-free`・`tencent/hy3-free`・`z-ai/glm-5.3-flash-free`・`orca/orcaverify-text1.0-free`。約200モデルという記載とは規模が異なる(プラン/キーのスコープによる可能性が高い) | 2026-09-20 |
| **⚠️ このキーのスコープ制限** | `orcarouter/auto`・`orcarouter/fusion*`・有料直接モデル(`google/gemini-2.5-flash`等)、および`orcarouter/free`の一部呼び出しが `403 model_access_denied`(`reason: block_key_scope`)で拒否される。**直接の`-free`モデルID(例`deepseek/deepseek-v4-flash-free`)は安定して使える。** manage_url: `https://www.orcarouter.ai/console/token?ref=block_key_scope` → **人に確認・対応を依頼(下記)** | 2026-09-20 |
| 無料モデルのレート制限 | 実際に`err_free_rate`(429)に到達。`retry_after_seconds: 27357`(約7.6時間)という大きな値を確認。エージェント側は待たずに見切りをつけて穏やかに終了(設計通り) | 2026-09-20 |
| 再試行・フォールバック・障害注入のコード | `agent/llm.py`に実装済み。フォールトインジェクション単体テスト合格に加え、**実際の429(err_free_rate)でも正しく動作することを確認**(`docs/design-decisions.md`判断6・9) | 2026-09-20 |
| Firewall評価API | リクエスト/レスポンス形式は未確認のまま(専用キー`ORCA_FIREWALL_KEY`は未発行)。`agent/policy.py`はベストエフォートで呼び、失敗時は自前PolicyGateにフォールバックする実装済み | 2026-09-20 |
| Guardrails | 未接続。自前マスク(`agent/masking.py`: email/phone/氏名/住所の正規表現)をLLM送信直前に適用する実装で代替中 | 2026-09-20 |
| 実LLMでの結合テスト | `deepseek/deepseek-v4-flash-free`でエージェント全体(ブラウザ操作→点検→報告)が動作。詳細は`runs/samples/run-8fff06d407/` | 2026-09-20 |
| **スコープ拡張(有料モデル)** | 人がコンソールで対応後、`google/gemini-3.5-flash`への直接アクセスが成功(ツール呼び出し・コスト取得とも正常、$0.101592/16ステップ)。**ただし`orcarouter/auto`自体は引き続き`block_key_scope`で拒否**(モデル個別の許可リストに追加する形のスコープ設定と思われる)。Named Router作成時も同様に個別モデル指定が必要になる可能性がある | 2026-09-20 |

## 人への依頼(このメモを渡す用。更新: 2026-09-21、キー復旧・Named Router作成ありがとうございました)
0. **[解決済み]** キー復旧・Named Router(`orcarouter/site-inspector`)作成、確認しました。復旧後、直指定/Named Router/orcarouter/autoの3構成比較を実施しました(結果は`docs/design-decisions.md`判断16、`docs/STATUS.md`「ルーター比較の実測結果」)。要点: **現状の使い方(長いツール呼び出し系プロンプト)では、Named Router(`gated_adaptive`)もautoも、直接モデル指定に対してコスト・速度で優位性がありませんでした**(両方とも直接指定よりコスト高・67〜79%遅い。全呼び出しが高難度プールの`google/gemini-3.5-flash`に固定され、簡易プールへの分散が一切起きなかったため)。簡易プールを`google/gemini-2.5-flash-lite`ではなく`google/gemini-2.5-flash`にした設定は今回のテストでは使われませんでした(高難度側にしか振られなかったため)。もし「軽い呼び出しでも簡易プールに落ちやすくする」チューニング項目がコンソール側にあれば教えていただけると、再検証できます。
1. **[任意・急がない]** `orcarouter/auto` はまだ `block_key_scope` で拒否されたままです。Named Routerを作る際も同じ制限に当たる可能性があるので、コンソール(`https://www.orcarouter.ai/console/token?ref=block_key_scope`)で「個別モデルの許可」ではなく「全モデル許可」または「Named Routerを許可」に変更できる設定がないか、余裕があるときに見てもらえると助かります。
2. コンソールで Named Router を作成する(急ぎではありません)。**提案する設定値は下記の通り**(観察前の初期案。`orcarouter/auto`が使えるようになり次第、観察してから絞り込む)。
3. Firewall・Guardrails・スコープ付きAPIキーが使えるプランか確認する(使えなければ、その旨を一言で教えてほしい。使えない場合は自前実装のままレポート・ピッチで明記する)。
4. スコープを広げられたら「広げた」とだけ教えてほしい。`orcarouter/auto`の観察とベンチマーク(`bench/run_bench.py`)はこちらで実行する。

## 検証用の既定(安く小さく試したいとき)
ライブLLM呼び出しを伴う動作確認は、次の組み合わせでコストを抑えられる(いずれも環境変数。コード変更不要):
- `ORCA_MODEL=deepseek/deepseek-v4-flash-free`(無料モデル。判断11の比較実測では有料モデルより指摘が粗いが、配線の確認には十分)
- `TESTCASE_MAX_CASES=10`(項目書生成の上限件数を絞る。既定40)、`RECON_MAX_PAGES=5`(下見のページ数を絞る。既定12)
- `LLM_BUDGET_PER_RUN_USD=0.1`・`LLM_BUDGET_PER_DAY_USD=0.5`(2026-09-21追加。想定外のコスト超過を防ぐ歯止め。超えるとその実行/生成は打ち切られ、部分結果のまま終了する。`docs/contracts.md`の「コスト台帳と予算上限」参照)
実測コストは `python -m agent.cost_report` で確認できる(`runs/ledger.jsonl`が正。すべてのLLM呼び出しが成功・失敗を問わず記録される)。

## 入力削減・自律性とコストのバランスの実験(2026-09-21、既定はすべてOFF)
- `AGENT_COMPACT_TOOL_RESULTS=1`(+`AGENT_COMPACT_KEEP_RECENT=2`): 会話が長くなるほど古い画面状態が
  再送され続ける問題への対処。**実測ではコスト・呼び出し回数とも削減効果が確認できなかった**
  (`docs/design-decisions.md`判断20)。既定OFFのまま。
- `AGENT_MACRO_CODE_FASTPATH=1`: macroが決まっている項目(rapid_click/navigate_direct)をLLM無しで
  コード実行する。**検出品質を落とさずLLM呼び出し・コストを100%削減できた**(同上、判断21)。
  対象はrapid_click/navigate_directのみ(override_param/fill_abnormalは対象外)。既定OFF。

## Named Routerの設定値(実測済み。2026-09-21)
- 名前: `site-inspector`(おのゆー作成)
- Strategy: `gated_adaptive`
- 許可モデル: `google/gemini-2.5-flash-lite`, `google/gemini-2.5-flash`, `google/gemini-3.5-flash`
- 簡易(Mundane)プール: `google/gemini-2.5-flash`(当初案の`flash-lite`ではなくこちらに設定されている)
- 高難度(Hard)プール: `google/gemini-3.5-flash`
- 既定(フォールバック): `openai/gpt-4o`
- `prefer_free`: 未確認(今回の実測では無料モデルへの振り分けは0件だった)
- **実測結果(bench比較、10項目・約240呼び出し)**: 簡易プールへの振り分けは**0件**。全呼び出しが高難度プールの`google/gemini-3.5-flash`に解決された。直接指定と比べてコスト+9%・所要時間+79%(詳細は`docs/design-decisions.md`判断16)。原因は仮説段階だが、このエージェントの呼び出しが常にツール呼び出しを伴い、ページ状態を含む長いプロンプトになるため、難易度判定で一律Hardと判定されている可能性が高い

## 実装側の準備状況(参考)
- モデル名は環境変数のみで扱う: `ORCA_MODEL`(既定`orcarouter/auto`。**現状はスコープ制限のため暫定的に`google/gemini-3.5-flash`に設定して動作確認中**。`deepseek/deepseek-v4-flash-free`は無料の比較用として引き続き使える)、用途別に`ORCA_MODEL_L1_TEXT`等の上書きも可(`agent/config.py`の`model_for_purpose()`)。Named Router作成後は`ORCA_MODEL=orcarouter/site-inspector`に変更するだけでコード変更不要(AC-19)。
- Fallbackモデル一覧は`ORCA_FALLBACK_MODELS`(カンマ区切り、最大5)。無料枠拒否時の有料切替先は`ORCA_PAID_FALLBACK_MODEL`。
- Firewall用キーは`ORCA_FIREWALL_KEY`(未設定なら自前PolicyGateのみで動く)。
