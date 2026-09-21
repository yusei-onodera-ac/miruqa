#!/usr/bin/env bash
# EC2（Amazon Linux 2023, arm64）に、Java 21 + Tomcat 10.1 + Caddy（自動HTTPS）を入れる。root（sudo）で実行する。
# 何度実行しても、同じ状態になるようにしている。事前に、DNSの A レコード（miruqa.com → このサーバーのElastic IP）を設定しておくこと。
set -euo pipefail

DOMAIN="${DOMAIN:-miruqa.com}"
TOMCAT_VERSION="${TOMCAT_VERSION:-10.1.34}"
CADDY_VERSION="${CADDY_VERSION:-2.8.4}"

[ "$(id -u)" -eq 0 ] || { echo "root（sudo）で実行してください" >&2; exit 1; }
ARCH="$(uname -m)"; case "$ARCH" in aarch64) CADDY_ARCH=arm64;; x86_64) CADDY_ARCH=amd64;; *) echo "未対応のCPU: $ARCH" >&2; exit 1;; esac

echo "== Java 21 =="
dnf install -y java-21-amazon-corretto-headless tar gzip   # curl は、標準の curl-minimal を使う（curl の導入は、競合するため、しない）

echo "== Tomcat $TOMCAT_VERSION =="
id tomcat >/dev/null 2>&1 || useradd -r -s /sbin/nologin -d /opt/tomcat tomcat
if [ ! -d "/opt/apache-tomcat-$TOMCAT_VERSION" ]; then
  cd /tmp
  BASE="https://archive.apache.org/dist/tomcat/tomcat-10/v${TOMCAT_VERSION}/bin"
  curl -fsSLO "$BASE/apache-tomcat-${TOMCAT_VERSION}.tar.gz"
  curl -fsSLO "$BASE/apache-tomcat-${TOMCAT_VERSION}.tar.gz.sha512"
  sha512sum -c "apache-tomcat-${TOMCAT_VERSION}.tar.gz.sha512"     # 改ざん・破損の検査
  tar xzf "apache-tomcat-${TOMCAT_VERSION}.tar.gz" -C /opt
  rm -f "apache-tomcat-${TOMCAT_VERSION}.tar.gz"*
fi
ln -sfn "/opt/apache-tomcat-$TOMCAT_VERSION" /opt/tomcat
rm -rf /opt/tomcat/webapps/*                       # 既定のアプリ（manager 等）は、すべて削除
# 接続を、ローカルのみ（127.0.0.1:8080）に限定する。外部からは、Caddy（443）経由だけ
sed -i 's|<Connector port="8080" protocol="HTTP/1.1"|<Connector port="8080" address="127.0.0.1" protocol="HTTP/1.1" server="MiruQA"|' /opt/tomcat/conf/server.xml
# シャットダウンポートを無効化
sed -i 's|<Server port="8005" shutdown="SHUTDOWN">|<Server port="-1" shutdown="SHUTDOWN">|' /opt/tomcat/conf/server.xml
chown -R tomcat:tomcat "/opt/apache-tomcat-$TOMCAT_VERSION"

echo "== 設定ファイル（秘密は、この端末の中で作る）=="
mkdir -p /etc/miruqa
if [ ! -f /etc/miruqa/env ]; then
  ( umask 077   # 秘密のファイルだけ、厳しい権限で作る（他のファイルに、影響させない）
  cat > /etc/miruqa/env <<ENV
CONTACT_ENABLED=false
SITE_URL=https://${DOMAIN}
CSRF_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '=+/\n')
IP_HASH_SALT=$(head -c 24 /dev/urandom | base64 | tr -d '=+/\n')
ENV
  )
fi
chmod 600 /etc/miruqa/env

cat > /etc/systemd/system/tomcat.service <<'UNIT'
[Unit]
Description=MiruQA Landing (Tomcat 10.1)
After=network.target

[Service]
Type=forking
User=tomcat
Group=tomcat
EnvironmentFile=/etc/miruqa/env
Environment=JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
Environment=CATALINA_HOME=/opt/tomcat
Environment=CATALINA_BASE=/opt/tomcat
Environment="CATALINA_OPTS=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Duser.timezone=Asia/Tokyo"
ExecStart=/opt/tomcat/bin/startup.sh
ExecStop=/opt/tomcat/bin/shutdown.sh
PIDFile=/opt/tomcat/temp/tomcat.pid
Environment=CATALINA_PID=/opt/tomcat/temp/tomcat.pid
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable tomcat

echo "== Caddy $CADDY_VERSION（自動HTTPS）=="
if ! [ -x /usr/local/bin/caddy ] || ! /usr/local/bin/caddy version | grep -q "v${CADDY_VERSION}"; then
  cd /tmp
  BASE="https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}"
  TGZ="caddy_${CADDY_VERSION}_linux_${CADDY_ARCH}.tar.gz"
  curl -fsSLO "$BASE/$TGZ"
  curl -fsSLO "$BASE/caddy_${CADDY_VERSION}_checksums.txt"
  grep " ${TGZ}\$" "caddy_${CADDY_VERSION}_checksums.txt" | sha512sum -c -     # 検査
  tar xzf "$TGZ" caddy
  install -m 0755 caddy /usr/local/bin/caddy
  rm -f caddy "$TGZ" "caddy_${CADDY_VERSION}_checksums.txt"
fi
setcap cap_net_bind_service=+ep /usr/local/bin/caddy
id caddy >/dev/null 2>&1 || useradd -r -s /sbin/nologin -d /var/lib/caddy caddy
mkdir -p /etc/caddy /var/lib/caddy /var/log/caddy
chmod 755 /etc/caddy
chown caddy:caddy /var/lib/caddy /var/log/caddy
umask 022
cat > /etc/caddy/Caddyfile <<CADDY
${DOMAIN} {
	encode zstd gzip
	reverse_proxy 127.0.0.1:8080
	log {
		output file /var/log/caddy/access.log
	}
}
www.${DOMAIN} {
	redir https://${DOMAIN}{uri} permanent
}
CADDY
cat > /etc/systemd/system/caddy.service <<'UNIT'
[Unit]
Description=Caddy (HTTPS reverse proxy)
After=network-online.target
Wants=network-online.target

[Service]
User=caddy
Group=caddy
Environment=XDG_DATA_HOME=/var/lib/caddy
ExecStart=/usr/local/bin/caddy run --config /etc/caddy/Caddyfile
ExecReload=/usr/local/bin/caddy reload --config /etc/caddy/Caddyfile
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable caddy

echo "== 完了 =="
echo "次: WARを配置して（deploy.sh）、tomcat と caddy を起動する。"
