#!/usr/bin/env bash
# ローカルで、WARを作って、サーバーへ配置し、Tomcatを再起動して、疎通を確認する。
# 使い方: ./deploy.sh <ホスト または IP> <秘密鍵のパス> [ユーザー(既定 ec2-user)]
set -euo pipefail
HOST="${1:?ホストまたはIPを指定してください}"
KEY="${2:?秘密鍵のパスを指定してください}"
USER_NAME="${3:-ec2-user}"
cd "$(dirname "$0")/../../.."          # landing-app/

echo "== ビルド（テストも実行）=="
mvn -q package
WAR=target/ROOT.war
[ -f "$WAR" ] || { echo "WARがありません" >&2; exit 1; }

SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "${USER_NAME}@${HOST}")
echo "== 配置 =="
scp -i "$KEY" -o StrictHostKeyChecking=accept-new "$WAR" "${USER_NAME}@${HOST}:/tmp/ROOT.war"
"${SSH[@]}" 'sudo systemctl stop tomcat || true
  sudo rm -rf /opt/tomcat/webapps/ROOT /opt/tomcat/webapps/ROOT.war
  sudo install -o tomcat -g tomcat -m 0644 /tmp/ROOT.war /opt/tomcat/webapps/ROOT.war
  rm -f /tmp/ROOT.war
  sudo systemctl start tomcat'

echo "== 疎通確認（サーバー内）=="
for i in $(seq 1 20); do
  if "${SSH[@]}" 'curl -fsS http://127.0.0.1:8080/health' 2>/dev/null; then echo; echo "OK"; exit 0; fi
  sleep 3
done
echo "起動を確認できませんでした。ログ: sudo journalctl -u tomcat / /opt/tomcat/logs/catalina.out" >&2
exit 1
