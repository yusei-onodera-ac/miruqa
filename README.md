# MiruQA — AI HACK 2026

URLと（あれば）仕様書を渡すと、AIエージェントが**実際にサイトを操作**して点検し、証拠付きレポートを出す。
連打や手順スキップ・価格改ざんなどの想定外の操作も、**承認済みのテスト項目書の範囲でだけ**実際に試す（脆弱性診断）。
ログインが必要な画面も、暗号化保管したテスト用アカウントで点検できる。

> UIテストやE2Eテストを、格安に、安全に、爆速に。

- 大会: AI HACK 2026 第2回（東京）。テーマ「業務を自律化するAIエージェント」。ピッチは 2026-09-23（水）10:00〜16:00、東京会場。
- 評価基準（主催）: ①セキュリティ ②コストパフォーマンス ③信頼性・堅牢性 ④自律性 ⑤アイデア・独創性（事業性を含む）。
- LLM呼び出しはすべて [OrcaRouter](https://docs.orcarouter.ai/) 経由（OpenAI互換）。

## できること
URL（と、任意の仕様書）から、下見→テスト項目書の生成→自然言語での項目追加→承認→実行→証拠付きレポート、までを一気通貫で行う。

課金は、クレジット制（画面にUSDは出さない）。使った分だけ、実行中に残高が減っていく。デモ用の架空決済（テストカード `4242 4242 4242 4242` のみ成功。実際の課金はしない）でチャージする。

残高不足・通信断・LLM障害・ワーカー再起動などで途中で止まっても、失敗にはせず「中断」として確定する。完了済みの項目は再実行せず、続きから再開できる（二重課金なし）。同じプロジェクトを再テストするときは、前回の結果を引き継ぎ、前回との差分（新規／修正済み／継続中）を出せる（組織スコープ内のみ）。

アカウント・残高・利用履歴・プロジェクト一覧は、マイページに集約している。

## 構成（2層）
- **ワーカー**（`agent/`・`checks/`・`bench/`・`report/`・`demo-site/`・`demo-site2/`。Python 3.9）: ブラウザ操作（Playwright、手元のGoogle Chrome）・AIとの対話・ポリシーゲート・コスト台帳。HTTP APIとCLIのみ、UIは持たない。
- **Web層**（`web/`。Java 21＋Spring Boot、Thymeleaf）: 利用者管理・プロジェクト・承認UI・レポート表示。LLMは直接呼ばず、ワーカーをHTTPで呼ぶ。

審査・レビューでは、上から順に読むと理解しやすい（概要→仕様→セキュリティ→技術→設計判断→料金→今後、の順）。

| ドキュメント | 内容 |
|---|---|
| [`docs/SERVICE-OVERVIEW.md`](docs/SERVICE-OVERVIEW.md) | サービス概要（機能・技術の網羅版。実装状況の記号つき） |
| [`docs/MIRUQA-FULL-SPEC.md`](docs/MIRUQA-FULL-SPEC.md) | 機能・サービスロジックの完全版仕様（実装状況つき） |
| [`docs/SECURITY-PAPER.html`](docs/SECURITY-PAPER.html) | セキュリティの設計・脅威モデル・実測結果・既知の限界 |
| [`docs/TECHNICAL-GUIDE.html`](docs/TECHNICAL-GUIDE.html) | 内部の技術解説（アーキテクチャ・シーケンス・ER・コストの流れ） |
| [`docs/design-decisions.md`](docs/design-decisions.md) | OrcaRouterの設計判断ログ（観察→判断→固定。実測値つき。OrcaRouter賞の根拠） |
| [`docs/pricing.md`](docs/pricing.md) | 料金設計の根拠（実測ベース。クレジット制導入前の試算のため、現行の料金案とは一部ずれる） |
| [`docs/roadmap.md`](docs/roadmap.md) | 今回作らなかったもの（次の段階で必要になる項目） |
| [`docs/contracts.md`](docs/contracts.md) | データ契約（ワーカーAPI、`Action`／`PolicyVerdict`／`LlmCall`／`Finding`／`Plan`／`Run`） |
| [`docs/qiita-article.md`](docs/qiita-article.md) | 解説記事（提出用） |

| パス | 内容 |
|---|---|
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
- チャージのカード情報（番号・期限・名義）は保存もログ出力もしない。決済はデモ実装（`FakePaymentGateway`）で、実際の課金は発生しない。

## 既知の限界（正直な一覧。詳細は `docs/SECURITY-PAPER.html` 第8章）
- 氏名のマスクは正規表現ベースで、姓と名の間にスペースがないと検出できない（実測）。
- OrcaRouter Guardrails側の電話番号マスクは、ハイフンなし11桁・国際表記（`+81…`）を通す（アプリ側のマスクは効くため二重防御ではある）。
- 緊急停止は協調的で、最大30秒以上かかる場合がある。
- コストのルーター比較は n=1〜2 と小さく、参考値。
