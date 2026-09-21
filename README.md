# MiruQA — AI HACK 2026

URLと（あれば）仕様書を渡すと、AIエージェントが**実際にサイトを操作**して点検し、証拠付きレポートを出す。
連打や手順スキップ・価格改ざんなどの想定外の操作も、**承認済みのテスト項目書の範囲でだけ**実際に試す（脆弱性診断）。
ログインが必要な画面も、暗号化保管したテスト用アカウントで点検できる。

> 言わずに去るユーザーの代わりに、先に使ってみるAI。

- 大会: AI HACK 2026 第2回（東京）。テーマ「業務を自律化するAIエージェント」。ピッチは 2026-09-23（水）10:00〜16:00、東京会場。
- 評価基準（主催）: ①セキュリティ ②コストパフォーマンス ③信頼性・堅牢性 ④自律性 ⑤アイデア・独創性（事業性を含む）。
- LLM呼び出しはすべて [OrcaRouter](https://docs.orcarouter.ai/) 経由（OpenAI互換）。

## 構成（2層）
- **ワーカー**（`agent/`・`checks/`・`bench/`・`report/`・`demo-site/`・`demo-site2/`。Python 3.9）: ブラウザ操作（Playwright、手元のGoogle Chrome）・AIとの対話・ポリシーゲート・コスト台帳。HTTP APIとCLIのみ、UIは持たない。
- **Web層**（`web/`。Java 21＋Spring Boot、Thymeleaf）: 利用者管理・プロジェクト・承認UI・レポート表示。LLMは直接呼ばず、ワーカーをHTTPで呼ぶ。

| パス | 内容 |
|---|---|
| `docs/contracts.md` | データ契約（ワーカーAPI、`Action`／`PolicyVerdict`／`LlmCall`／`Finding`／`Plan`／`Run`） |
| `docs/design-decisions.md` | 設計判断ログ（観察→判断→固定。実測値つき。OrcaRouター賞の根拠） |
| `demo-site/` | 点検対象1（架空ECサイト、ポート8765。仕込み不具合14件） |
| `demo-site2/` | 点検対象2（社内備品貸出予約システム、ポート8766。ログインあり、仕込み不具合10件） |
| `web/` | Java層（Spring Boot） |
| `landing-app/` | 製品紹介LP（別アプリ。ピッチ用途、点検対象ではない） |

## ローカルでの始め方

### 1. 準備（初回のみ）
```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
playwright install chrome   # 未導入の場合のみ（手元のGoogle Chromeを直接使うため通常は不要）
cp .env.example .env        # ORCAROUTER_API_KEY 等を設定（.envはコミットしない）
```
`web/` 側はJava 21以上とMavenが必要（`java -version`／`mvn -version`で確認）。

### 2. 起動（1コマンド）
```bash
scripts/start-local.sh
```
デモサイト（8765・8766）・ワーカー（8770）・Web層（8080）をまとめて起動する。Ctrl-Cで全部止まる。
デモサイトを起動しない場合は `scripts/start-local.sh --no-demo`。

`.env`はワーカー（Python、`agent/config.py`の`load_dotenv()`）は自動で読むが、Web層（`mvn spring-boot:run`）は読まないため、このスクリプトが起動前に`.env`をシェルの環境変数として読み込んでからJavaプロセスを起動する。

起動後、`http://localhost:8080/` を開き、サインアップ（またはシード済みデモアカウント。`SEED_DEMO_DATA=true scripts/start-local.sh`で作成）でログインする。

### 3. 手動で起動する場合（個別に確認したいとき）
```bash
# デモサイト
TEST_MODE=1 python3 demo-site/server.py --port 8765
TEST_MODE=1 python3 demo-site2/server.py --port 8766

# ワーカー（.envの値を読み込んでから）
python3 -m agent.server

# Web層（別ターミナル。WORKER_SHARED_SECRETはワーカー側と同じ値にする）
cd web && WORKER_SHARED_SECRET=<同じ値> mvn spring-boot:run
```

### 重要: コードを変更したら、ワーカーの再起動が必要
**ワーカー（Python、`agent.server`）にはホットリロードが無い。** `agent/`・`checks/`配下のファイルを編集しても、既に起動中のワーカープロセスには反映されない。編集後は必ずワーカーを再起動すること（Web層はThymeleafテンプレート・`spring.thymeleaf.cache=false`により大半の変更が再起動なしで反映されるが、Javaのソースコード自体を変えた場合は`mvn -q -o compile`後にWeb層も再起動が必要）。

古いワーカーのままAPIを叩くと、追加したはずのエンドポイントが`404`になったり、修正したはずのバグが再現し続けたりする（このプロジェクトで実際に何度も踏んだ問題）。動作確認の前に、必ず起動中のプロセスが最新のコードを読み込んでいるか（＝直近の変更後に再起動したか）を確認すること。

### テストの実行
```bash
python3 -m unittest discover -s agent/tests -p "test_*.py"   # ワーカー
cd web && mvn -q -o test                                      # Web層
```

## 安全上の約束
- 診断対象は**自作のデモサイト（`demo-site/`・`demo-site2/`）と、利用者が所有確認・宣言した自分のサイトだけ**。他社サイト（審査員・協賛企業を含む）には実行しない。
- 能動的なテスト（連打・手順スキップ・価格改ざん等）は、テスト環境の宣言と、テスト項目書の承認が揃わないと実行できない。
- 秘密情報（`.env`、暗号化鍵、パスワード）はコミットしない。テスト用アカウントのパスワードは暗号化して保管し、実行時だけ復号して使う（保存はしない）。
- 個人情報はLLMに送る前にマスクする。
