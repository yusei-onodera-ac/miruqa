"""v0.6 P2(L-4・S-2): 拒否リスト・SSRF対策。

診断対象は許可ドメインのみ、という絶対条件は`config.is_host_allowed()`
(ホスト名の文字列一致)で既に担保されている。ここではその一段深く、**ホスト名を実際に名前解決した
IPアドレス**が、プライベートIP・ループバック・リンクローカル・クラウドのメタデータアドレス等
(SSRFの典型的な標的)でないかを確認する。DNSリバインディング(許可したホスト名が後から
異なるIPを指すよう変化する攻撃)への対策として、名前解決とIPチェックを navigate の直前に毎回行う。

`config.SANDBOX_HOSTS`(既定は`ALLOWED_HOSTS`と同じ)に含まれるホストだけは、ループバック
(127.0.0.1等、自作デモサイトが該当)であることを許可する明示的な例外とする。
"""

import ipaddress
import socket

from . import config


def _is_blocked_ip(ip_str):
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True, "IPアドレスとして解釈できない"
    if ip.is_loopback:
        return True, "ループバックアドレス"
    if ip.is_private:
        return True, "プライベートアドレス"
    if ip.is_link_local:
        return True, "リンクローカルアドレス"
    if ip.is_multicast or ip.is_reserved or ip.is_unspecified:
        return True, "予約・特殊用途アドレス"
    # クラウドのメタデータアドレス(AWS/GCP/Azure等が共通で使う169.254.169.254)は
    # is_link_local(169.254.0.0/16)で既に捕捉されるが、明示的にも記す
    if ip_str == "169.254.169.254":
        return True, "クラウドのメタデータアドレス"
    return False, None


def is_local_or_private_hostname(netloc):
    """v0.7(第1節、モードA): 対象がローカル・プライベート(モードA向け)かどうかを判定する。
    Java側のDomainSafetyChecker.classify()と同じ考え方(ループバック・プライベート・
    リンクローカルはローカル扱い。クラウドのメタデータアドレス等は、ここではFalseを返す
    (=常に遮断する側に倒す。呼び出し側でis_denied_hostname等と併用すること)。"""
    hostname = netloc.rsplit(":", 1)[0] if ":" in netloc else netloc
    lower = hostname.lower()
    if lower.endswith(".local") or lower.endswith(".test"):
        return True
    if netloc in config.SANDBOX_HOSTS:
        return True
    try:
        infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror:
        return False
    for info in infos:
        ip_str = info[4][0]
        try:
            ip = ipaddress.ip_address(ip_str)
        except ValueError:
            continue
        if ip.is_loopback or ip.is_private or ip.is_link_local:
            if ip_str == "169.254.169.254":
                return False
            return True
    return False


def is_public_hostname(netloc):
    """v0.7(第1a節、モードC): 対象が公開ホスト(ループバック・プライベート・メタデータでない)か
    どうかを判定する。`config.READONLY_TEST_PUBLIC_HOSTS`に完全一致するホストは、実際には
    ローカル(自作デモサイト)でも、テスト目的でだけ「公開」扱いにする(開発方針: 実在する
    第三者のサイトへはアクセスせず、自作デモサイトでモードCの検証を行うため)。"""
    if netloc in config.READONLY_TEST_PUBLIC_HOSTS:
        return True
    hostname = netloc.rsplit(":", 1)[0] if ":" in netloc else netloc
    try:
        infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror:
        return False
    for info in infos:
        ip_str = info[4][0]
        blocked, _reason = _is_blocked_ip(ip_str)
        if blocked:
            return False
    return True


def check_netloc_safe(netloc, mode=None):
    """(safe: bool, reason: str|None) を返す。netlocは"host:port"または"host"の形式
    (urlparseの結果と同じ)。netlocが`config.SANDBOX_HOSTS`に完全一致する場合は、
    名前解決チェックをスキップして許可する(自作デモサイト用の明示的な例外)。
    ホスト名の拒否リスト(L-4: 審査員・協賛企業・政府機関等)は、サンドボックス例外より
    常に優先して確認する。

    v0.7(第1節、モードA): mode="local"のときは、ループバック・プライベート・リンクローカルへの
    遷移を許可する(利用者の手元の対象が、まさにこれらのアドレスであるため)。クラウドの
    メタデータアドレス・予約/特殊用途アドレスは、モードに関わらず常に遮断する。"""
    hostname = netloc.rsplit(":", 1)[0] if ":" in netloc else netloc
    denied, reason = config.is_denied_hostname(hostname)
    if denied:
        return False, reason
    if netloc in config.SANDBOX_HOSTS:
        return True, None
    try:
        infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror as exc:
        return False, f"名前解決に失敗した({exc})"
    for info in infos:
        ip_str = info[4][0]
        blocked, reason = _is_blocked_ip(ip_str)
        if not blocked:
            continue
        if mode == "local" and not _is_always_blocked_ip(ip_str):
            continue
        return False, f"{hostname} の解決先({ip_str})が{reason}のため遮断した(SSRF対策)"
    return True, None


def _is_always_blocked_ip(ip_str):
    """モードに関わらず常に遮断すべきIPか(クラウドのメタデータアドレス・予約/特殊用途アドレス)。
    理由の文言(_is_blocked_ipの戻り値)には頼らない: 169.254.169.254はPythonのipaddress
    モジュールでis_privateにも該当し、「プライベートアドレス」判定が先に付くことがあるため、
    文言だけで「メタデータアドレスかどうか」を区別すると見落とす(実際に踏んだ不具合)。"""
    if ip_str == "169.254.169.254":
        return True
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True
    return ip.is_multicast or ip.is_reserved or ip.is_unspecified
