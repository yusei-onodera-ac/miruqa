# AWSでの運用（案）

> **現在の公開方法は、`ec2/RUNBOOK.md`（EC2 1台 ＋ Caddy ＋ Tomcat。フォーム停止中のLPのみ）。** 以下の ECS Fargate ＋ RDS の構成は、フォームを有効にする段階の案。

**状態**: 設計と、Dockerイメージ・WARの作成までを行った。**AWS上での実際のデプロイと動作確認は、未実施**（アカウント・権限の準備が必要）。

## 構成

```
利用者 ─→ Route 53 (miruqa.com) ─→ CloudFront（任意）─→ ALB (HTTPS, ACM証明書)
                                                          │  ヘルスチェック: GET /health
                                                   ECS Fargate（Tomcat 10.1 / Java 21、2タスク）
                                                          │
                                                   RDS for PostgreSQL（Multi-AZ は任意）
```

| 部品 | 内容 |
|---|---|
| ドメイン | `miruqa.com`（取得済み）。Route 53 にホストゾーンを作り、レジストラのNSを向ける。ALB（またはCloudFront）へAレコード（エイリアス） |
| 証明書 | ACM（東京リージョン、CloudFrontを使う場合は、バージニア北部）。DNS検証 |
| ALB | HTTPSリスナー（443）。HTTP（80）は、HTTPSへリダイレクト。ターゲットグループのヘルスチェックは `/health` |
| ECS Fargate | `Dockerfile` のイメージ（ECRに push）。CPU 0.5 vCPU／メモリ 1GB から。タスク数は、2（AZを分ける） |
| RDS | PostgreSQL 16。プライベートサブネット。セキュリティグループは、ECSタスクからのみ、5432を許可 |
| 秘密情報 | Secrets Manager に `DB_PASSWORD`・`CSRF_SECRET`・`IP_HASH_SALT`。タスク定義の `secrets` で渡す（コードやイメージに含めない） |
| ログ | CloudWatch Logs（awslogs） |
| WAF（任意） | ALBに AWS WAF。マネージドルール（一般的な脅威）と、`/contact` へのレート制限 |

## 環境変数

| 名前 | 内容 | 例 |
|---|---|---|
| `DB_URL` | JDBC URL | `jdbc:postgresql://<RDSエンドポイント>:5432/miruqa` |
| `DB_USER` | DBユーザー | `miruqa_app` |
| `DB_PASSWORD` | （Secrets Manager） | |
| `CSRF_SECRET` | CSRFトークンの署名鍵。**複数タスクで共通にする** | （長いランダム文字列） |
| `IP_HASH_SALT` | IPアドレスをハッシュ化する塩 | （ランダム文字列） |
| `INQUIRY_RATE_LIMIT_PER_HOUR` | 1つのIPが、1時間に送れる件数 | `5` |
| `DB_POOL_SIZE` | 接続プールの大きさ | `5` |

`CSRF_SECRET` が未設定だと、起動ごとに鍵を作る。タスクが複数だと、別のタスクで発行したトークンを検証できず、フォームが失敗する。**本番では、必ず設定する。**

## 手順の概要

1. ECR リポジトリを作り、イメージをビルドして push する。
   ```bash
   docker build -t miruqa-landing .
   ```
2. RDS（PostgreSQL）を作る。スキーマは、起動時に自動で作られる（`schema.sql`。`CREATE TABLE IF NOT EXISTS`）。
3. Secrets Manager に、3つの秘密を登録する。
4. ECS のタスク定義とサービスを作る（ALBのターゲットグループに、ポート8080を登録）。
5. ACM 証明書を発行し、ALB にHTTPSリスナーを追加する。
6. Route 53 で `miruqa.com` を、ALBに向ける。
7. `https://miruqa.com/health` が `{"status":"UP"}` を返すことを、確認する。

## 運用上の注意
- **メールは送っていない**。お問い合わせは、DBに保存するだけ。通知が必要なら、SES（本番アクセスの申請が必要）を、別途つなぐ。
- 個人情報（名前・メール・本文）を保存するため、RDSは、暗号化（KMS）を有効にし、バックアップを取る。保管期間は、プライバシーポリシーの確定後に決める。
- プライバシーポリシーは下書き（要法務確認）。公開の前に、確認する。
- ALBの背後では、`X-Forwarded-For` の最後の値を接続元として使う。ALB以外から直接アクセスできないよう、セキュリティグループを閉じる。
- ローカルの確認: `mvn test`（38件）。ローカルでの起動は、`src/test/java/.../DevServer.java`（H2）。
