# Java層（web/）担当のClaude Codeへの最初の指示（この内容をそのまま貼り付ける）

起動: もう1つの開発用アカウント（または別のターミナル）で `cd /Users/onoderayusei/AI_HACK_2026 && claude` を実行し、下のプロンプトを貼る。
（ワーカー担当は別のセッションで `docs/KICKOFF_PROMPT.md` を貼る。2つを並行して動かす）

```
あなたはこのリポジトリ（AI_HACK_2026）の「Java層（web/）」の実装担当です。ワーカー（Python）は別のセッションが担当します。

1. まず CLAUDE.md を最後まで読み（特に第4章、第5a章、第5c章、第12章のWJ1〜WJ3、第13章のAC-20〜22）、続けて docs/contracts.md（特に「ワーカーAPI」）、docs/requirements.md（FR-36〜41、NFR-13）、docs/pitch.md を読んでください。
2. 最初のターンでは実装せず、次の4つだけを出して、私の確認を待ってください。
   (1) 読んだ内容の要約（5行程度） (2) 疑問点 (3) 実装計画（WJ1〜WJ3の順序と、最初の30分でやること） (4) 使うバージョンの提案（この環境は Java 25（Temurin）と Maven 3.9 が入っています。Spring Boot のバージョンは Java 25 で動くものを確認して選んでください）
3. 確認後は WJ1 → WJ2 → WJ3 の順で進めてください。
   作業は `git worktree add ../AI_HACK_2026-web -b dev/web` で作った別フォルダで行い、動く状態のときだけ main にマージしてください。
   編集してよいのは web/ と docs/STATUS-web.md・docs/QUESTIONS-web.md だけです。ワーカー側のディレクトリ（agent/、checks/ など）には触らないでください。
   ワーカーの完成を待たず、docs/contracts.md のワーカーAPIと、runs/samples/ のサンプル run.json、モックワーカー（web/mock-worker/）で先に進めてください（サンプルが無ければ、契約に従って自分で作ってよい）。
   ワーカーが止まっている・遅い・エラーを返すときも、画面に表示して落ちないようにしてください（評価③）。各WJが終わったら docs/STATUS-web.md を更新してください。

守ること:
- LLM呼び出しはすべてOrcaRouter経由（ワーカーの責務）。診断対象は自作デモと自分たちのサイトだけ。他社サイト（審査員・協賛企業を含む）には実行しない。
- 秘密情報（.env、APIキー）は表示・コミットしない。/Users/onoderayusei/agent-ai/.env は開かない。
- 測っていない数字（コスト削減率など）は、画面にも資料にも出さない。
- 要件の意味を変える判断は勝手にしない。要件の正はNotionの要件定義で、docs/requirements.md はその写し。データ契約（docs/contracts.md）を変えるときは、両方の実装セッションへの影響を書き、人経由で相手に伝える。
- Java層はLLMを直接呼ばず、APIキー等の秘密情報を持たない。ワーカーのURLだけを設定（WORKER_BASE_URL）に持つ。
- 認証・課金・DBは作らない（ハッカソンの範囲外）。
```
