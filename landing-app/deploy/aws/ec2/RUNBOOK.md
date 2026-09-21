# LPの公開手順（AWS EC2 ＋ SSH）

**状態**: スクリプトは、構文の確認までを行った。**実際のサーバーでの実行は、未実施**（EC2の作成が、まだのため）。
**構成**: EC2（Amazon Linux 2023）1台 ＋ Elastic IP。サーバーの中で、Caddy（自動HTTPS）→ Tomcat 10.1（`127.0.0.1:8080`）を動かす。
**フォーム**: 運営者情報の記入が済むまで、`CONTACT_ENABLED=false`（既定）で、**個人情報を取得しない**状態で公開する（お問い合わせ・プライバシーポリシーのページは、404）。DB（RDS）は、不要。

## あなたが、AWSコンソールで行うこと（私は、AWSの認証情報を持たない）

1. **EC2インスタンスを起動する**（東京リージョン `ap-northeast-1`）
   - AMI: Amazon Linux 2023（**x86_64**。`t3.micro` はx86のため、「64ビット（x86）」を選ぶ）／タイプ: **`t3.micro`**（メモリ1GB。LPだけなら、足りる。`t2.micro` は旧世代で、`t3` の方が、やや安く、性能も上）。ARMの `t4g.micro`（最も安い）でも動く（その場合は、AMIも「64ビット（Arm）」）。`nano`（0.5GB）は、Java＋Caddyには小さいので、勧めない
   - キーペア: 新規作成し、秘密鍵（`.pem`）を、自分のPCの `~/.ssh/miruqa.pem` に保存し、`chmod 400 ~/.ssh/miruqa.pem`
     - **秘密鍵の中身は、私に貼らないでください。ファイルのパスだけを、教えてください。**
   - セキュリティグループ（インバウンド）:
     - SSH（22）: **自分のIPだけ**（「マイIP」）
     - HTTP（80）と HTTPS（443）: どこからでも（`0.0.0.0/0`）。80は、証明書の発行に必要
   - ストレージ: 8GBのままでよい（暗号化を有効に）
2. **Elastic IP を割り当てて、インスタンスに関連付ける**（再起動しても、IPが変わらないように）
3. **DNS を設定する**（`miruqa.com` を管理している場所。Route 53 か、レジストラ）
   - `A`　`miruqa.com` → Elastic IP
   - `CNAME`　`www.miruqa.com` → `miruqa.com`（または `A` でも同じIP）
   - 反映を待つ（数分〜）。`dig +short miruqa.com` で、Elastic IPが返ること
4. 私に、次の2つを教えてください: **Elastic IP（またはホスト名）** と、**秘密鍵のパス**

## 私が、SSHで行うこと（あなたの承認を取ってから）

1. `remote-setup.sh` をサーバーに送って、`sudo` で実行（Java 21、Tomcat 10.1、Caddy の導入。ダウンロードは、チェックサムを検査）
2. `deploy.sh <IP> <鍵のパス>` で、WARをビルド（テストも実行）して配置
3. `sudo systemctl start tomcat caddy` → `https://miruqa.com/` と `/health` を確認
4. 結果を報告

各操作の前に、内容を伝えて、承認をもらいます。SSHの鍵と、`/etc/miruqa/env` の秘密（CSRF・IPのハッシュ用の塩）は、表示もコミットもしません。

## 公開後に、確認すること

- `https://miruqa.com/` が表示される／`http://` は、HTTPSへ転送される／`www` は、apexへ転送される
- 応答ヘッダーに、`Strict-Transport-Security` と CSP が付いている
- `https://miruqa.com/contact` が 404 になる（フォーム停止中）
- Cookie が設定されない

## フォームを有効にするとき（運営者情報が決まってから）

1. `privacy.jsp` の【　】（運営者名、所在地、代表者、窓口、保管期間、制定日）を記入し、専門家の確認を受ける。
2. データの保存先を決める。今の実装は、DB未指定だと、**メモリ上のDB（再起動で消える）**。本番で保存するには、RDS（PostgreSQL）を作り、`DB_URL`・`DB_USER`・`DB_PASSWORD` を、`/etc/miruqa/env` に設定する。
3. `CONTACT_ENABLED=true` にして、`sudo systemctl restart tomcat`。
4. 送信の確認メールを送るか（SES）は、別途、決める。今は、保存だけ。

## 運用

- 更新: `deploy.sh` を再実行。
- ログ: `sudo journalctl -u tomcat -f`、`/opt/tomcat/logs/`、`/var/log/caddy/access.log`
- OSの更新: `sudo dnf upgrade -y`（定期的に）。Tomcatの更新は、`TOMCAT_VERSION` を変えて、`remote-setup.sh` を再実行。
- バックアップ: 状態を持たない（フォーム停止中）。AMIのスナップショットを、1つ取っておけば、足りる。
- 費用の目安（東京、常時起動。**料金は変わるため、AWSの料金表で確認**）: EC2 `t3.micro` 月あたり約$10前後（`t2.micro` は約$11前後、`t4g.micro` は約$8前後）、パブリックIPv4アドレス（Elastic IPを含む）月あたり約$3.6、EBS 8GB 月あたり約$1。合計、月あたり約$14前後。データ転送は、少量。
- さらに安くする案: Lightsail（AWSの簡易サーバー。固定IP込みで、月あたり$5〜7）。手順は、EC2と同じSSHでの導入で、そのまま使える。
- 監視: 最低限、UptimeRobot などの外形監視（`/health`）を推奨（外部サービスの登録は、あなたが判断）。
