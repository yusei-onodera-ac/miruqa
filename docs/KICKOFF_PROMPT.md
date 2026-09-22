# ワーカー（Python）担当のClaude Codeへの最初の指示（この内容をそのまま貼り付ける）

起動: 開発用アカウントのターミナルで `cd /Users/onoderayusei/AI_HACK_2026 && claude` を実行し、下のプロンプトを貼る。
（Java層担当は別のセッションで `docs/KICKOFF_PROMPT_WEB.md` を貼る。2つを並行して動かす）

事前に人（おのゆー）がやること: OrcaRouterのキーが有効か確認（`agent-ai` の `.env` に設定済みかもしれない）、クレジットの受け取り、Named Routerの作成。

```
あなたはこのリポジトリ（AI_HACK_2026）の「ワーカー（Python）」の実装担当です。Java層（web/）は別のセッションが担当します。

1. まず CLAUDE.md を最後まで読み、続けて docs/requirements.md、docs/contracts.md（特に「ワーカーAPI」）、docs/pitch.md、docs/orcarouter-notes.md、
   移植元の reference/agent-ai/ のコードを読んでください。
2. 最初のターンでは実装せず、次の3つだけを出して、私の確認を待ってください。
   (1) 読んだ内容の要約（5行程度） (2) 疑問点 (3) 実装計画（作業パッケージの順序と、最初の30分でやること）
3. 確認後は CLAUDE.md 第12章の順（WP-1 → WP0 → …）で進めてください。
   作業は `git worktree add ../AI_HACK_2026-worker -b dev/worker` で作った別フォルダで行い、動く状態のときだけ main にマージしてください。
   編集してよいのは、ワーカー担当のディレクトリ（agent/、checks/、bench/、report/、demo-site/、runs/、requirements.txt、reference/）と docs/STATUS.md・docs/QUESTIONS.md・docs/design-decisions.md・docs/orcarouter-notes.md だけです。web/ には触らないでください。
   ワーカーAPI（docs/contracts.md）は、Java層が使う境界です。早い段階で動く形（POST /api/runs、GET /api/runs/{id}、承認API、/api/health）にしてください。
   各WPが終わったら docs/STATUS.md を更新し、疑問は docs/QUESTIONS.md に、設計判断は docs/design-decisions.md（実測値つき）に書いてください。

守ること:
- LLM呼び出しはすべてOrcaRouter経由（ワーカーの責務）。診断対象は自作デモと自分たちのサイトだけ。他社サイト（審査員・協賛企業を含む）には実行しない。
- 秘密情報（.env、APIキー）は表示・コミットしない。/Users/onoderayusei/agent-ai/.env は開かない。
- 測っていない数字（コスト削減率など）は、画面にも資料にも出さない。
- 要件の意味を変える判断は勝手にしない。要件の正はNotionの要件定義で、docs/requirements.md はその写し。データ契約（docs/contracts.md）を変えるときは、両方の実装セッションへの影響を書き、人経由で相手に伝える。
- モデルIDをコードに固定しない。ストリーミングは使わない。
- L4（連打などの能動テスト）は、承認済みのテスト計画があるときだけ、テスト環境で、上限つきで実行する。
```
