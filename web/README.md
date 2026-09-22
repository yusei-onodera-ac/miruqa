# サイト点検エージェント — Web層（Java / Spring Boot）

ジョブ作成・実行状況・承認UI・レポート表示・履歴一覧を提供する画面。LLMは直接呼ばず、
ワーカー（`agent/server.py`、Python）をHTTPで呼ぶだけ（`docs/contracts.md`の「ワーカーAPI」）。
秘密情報は持たない（`.env`・APIキー不要）。

## 起動

前提: このリポジトリのワーカーが `http://127.0.0.1:8770`（既定）で起動していること。

```bash
# 1. デモサイト
python3 demo-site/server.py --port 8765

# 2. ワーカー(別ターミナル。実LLMを使うなら .env の ORCAROUTER_API_KEY が必要)
python3 -m agent.server

# 3. このJava層(別ターミナル)
cd web
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn spring-boot:run
```

`http://127.0.0.1:8080/` を開く。ワーカーのURLを変えたい場合は環境変数 `WORKER_BASE_URL` を設定する。

## 構成
- `JobController` — `/`(ジョブ作成フォーム)、`POST /jobs`
- `RunController` — `/runs/{id}`(実行状況)、`/runs/{id}/status.json`(ポーリング用JSON)、`/runs/{id}/report`(レポート中継)
- `ApprovalController` — `POST /runs/{id}/approvals/{approvalId}`
- `HistoryController` — `/history`
- `worker.WorkerClient` — ワーカーAPIを呼ぶ唯一の窓口。JDK標準 `HttpClient` + Jackson 2で実装(理由は`docs/STATUS-web.md`参照)
- `GlobalExceptionHandler` — ワーカーが止まっている・エラーを返すときも画面を落とさない(評価③)

## バージョン
Spring Boot 4.0.0 / Java 21ターゲット(実行環境: Java 25 Temurin) / Maven。詳細と実装中に踏んだ
問題は `docs/STATUS-web.md` を参照。
