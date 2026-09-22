"""v0.6 P2: 拒否リスト・SSRF対策(agent/security.py)のテスト。

実行: python -m unittest agent.tests.test_security -v
(LLM呼び出し・実際の外部ネットワークアクセスはなし。DNS解決はモックする)
"""

import unittest
from unittest.mock import patch

from agent import config, security


class IsBlockedIpTest(unittest.TestCase):
    def test_loopback_is_blocked(self):
        blocked, reason = security._is_blocked_ip("127.0.0.1")
        self.assertTrue(blocked)
        self.assertIn("ループバック", reason)

    def test_private_addresses_are_blocked(self):
        for ip in ("10.0.0.1", "192.168.1.1", "172.16.0.1"):
            with self.subTest(ip=ip):
                blocked, _ = security._is_blocked_ip(ip)
                self.assertTrue(blocked)

    def test_link_local_is_blocked(self):
        # ipaddressモジュールでは169.254.0.0/16はis_privateにも該当するため、
        # is_private判定が先に付き理由文言は「プライベートアドレス」になりうる。
        # ここではblocked=Trueであること(実際に遮断されること)だけを検証する。
        blocked, _reason = security._is_blocked_ip("169.254.1.1")
        self.assertTrue(blocked)

    def test_cloud_metadata_address_is_blocked(self):
        blocked, reason = security._is_blocked_ip("169.254.169.254")
        self.assertTrue(blocked)

    def test_public_address_is_allowed(self):
        blocked, reason = security._is_blocked_ip("8.8.8.8")
        self.assertFalse(blocked)
        self.assertIsNone(reason)

    def test_unparseable_string_is_blocked(self):
        blocked, reason = security._is_blocked_ip("not-an-ip")
        self.assertTrue(blocked)


class CheckNetlocSafeTest(unittest.TestCase):
    def setUp(self):
        self._original_sandbox = config.SANDBOX_HOSTS
        config.SANDBOX_HOSTS = {"127.0.0.1:8765"}

    def tearDown(self):
        config.SANDBOX_HOSTS = self._original_sandbox

    def test_sandbox_host_skips_dns_check(self):
        # SANDBOX_HOSTSに含まれるならgetaddrinfoを呼ばずに許可される(自作デモサイト用の例外)
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            safe, reason = security.check_netloc_safe("127.0.0.1:8765")
        self.assertTrue(safe)
        self.assertIsNone(reason)

    def test_hostname_resolving_to_private_ip_is_blocked(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.1.2.3", 0))]):
            safe, reason = security.check_netloc_safe("internal.example.com")
        self.assertFalse(safe)
        self.assertIn("SSRF対策", reason)

    def test_hostname_resolving_to_metadata_ip_is_blocked(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("169.254.169.254", 0))]):
            safe, reason = security.check_netloc_safe("attacker.example.com")
        self.assertFalse(safe)

    def test_hostname_resolving_to_public_ip_is_allowed(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            safe, reason = security.check_netloc_safe("public.example.com")
        self.assertTrue(safe)
        self.assertIsNone(reason)

    def test_dns_resolution_failure_is_blocked(self):
        import socket as socket_module

        with patch("socket.getaddrinfo", side_effect=socket_module.gaierror("name resolution failed")):
            safe, reason = security.check_netloc_safe("does-not-resolve.invalid")
        self.assertFalse(safe)
        self.assertIn("名前解決", reason)


class HostnameDenylistTest(unittest.TestCase):
    """L-4: 審査員・協賛企業・政府機関等のホスト名の拒否リスト(指揮官指摘、2026-09-21)。"""

    def setUp(self):
        self._original_suffixes = config.DENYLIST_HOSTNAME_SUFFIXES
        self._original_hosts = config.DENYLIST_HOSTNAMES
        self._original_allowed = config.ALLOWED_HOSTS
        self._original_sandbox = config.SANDBOX_HOSTS
        config.DENYLIST_HOSTNAME_SUFFIXES = [".go.jp", ".lg.jp"]
        config.DENYLIST_HOSTNAMES = {"cyberace.co.jp", "orcarouter.ai"}

    def tearDown(self):
        config.DENYLIST_HOSTNAME_SUFFIXES = self._original_suffixes
        config.DENYLIST_HOSTNAMES = self._original_hosts
        config.ALLOWED_HOSTS = self._original_allowed
        config.SANDBOX_HOSTS = self._original_sandbox

    def test_government_suffix_is_denied(self):
        denied, reason = config.is_denied_hostname("www.city.example.lg.jp")
        self.assertTrue(denied)
        self.assertIn("政府・公共機関", reason)

    def test_sponsor_hostname_is_denied(self):
        denied, _reason = config.is_denied_hostname("cyberace.co.jp")
        self.assertTrue(denied)

    def test_sponsor_subdomain_is_denied(self):
        denied, _reason = config.is_denied_hostname("www.orcarouter.ai")
        self.assertTrue(denied)

    def test_unrelated_hostname_is_not_denied(self):
        denied, reason = config.is_denied_hostname("127.0.0.1")
        self.assertFalse(denied)
        self.assertIsNone(reason)

    def test_is_host_allowed_rejects_denylisted_host_even_if_in_allowlist(self):
        # ALLOWED_HOSTSの設定ミスで拒否リスト対象が紛れ込んでも、二重の防御で許可しない
        config.ALLOWED_HOSTS = {"cyberace.co.jp"}
        self.assertFalse(config.is_host_allowed("cyberace.co.jp"))

    def test_check_netloc_safe_denies_denylisted_host_even_in_sandbox(self):
        config.SANDBOX_HOSTS = {"cyberace.co.jp"}
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            safe, reason = security.check_netloc_safe("cyberace.co.jp")
        self.assertFalse(safe)
        self.assertIn("拒否リスト", reason)


class LocalModeTest(unittest.TestCase):
    """v0.7(第1・1a・1b節): モードA(ローカル)では、固定のALLOWED_HOSTSとの完全一致を求めず、
    実際にローカル・プライベートに解決できることだけを確認する。メタデータのアドレス・拒否リストは
    モードに関わらず常に遮断する。"""

    def setUp(self):
        self._original_allowed = config.ALLOWED_HOSTS
        self._original_sandbox = config.SANDBOX_HOSTS
        config.ALLOWED_HOSTS = {"127.0.0.1:8765"}
        config.SANDBOX_HOSTS = {"127.0.0.1:8765"}

    def tearDown(self):
        config.ALLOWED_HOSTS = self._original_allowed
        config.SANDBOX_HOSTS = self._original_sandbox

    def test_is_local_or_private_hostname_true_for_loopback(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 0))]):
            self.assertTrue(security.is_local_or_private_hostname("127.0.0.1:9999"))

    def test_is_local_or_private_hostname_true_for_dot_local_suffix(self):
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            self.assertTrue(security.is_local_or_private_hostname("myapp.local:3000"))

    def test_is_local_or_private_hostname_false_for_public_ip(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            self.assertFalse(security.is_local_or_private_hostname("public.example.com"))

    def test_is_local_or_private_hostname_false_for_metadata_address(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("169.254.169.254", 0))]):
            self.assertFalse(security.is_local_or_private_hostname("attacker.example.com"))

    def test_is_host_allowed_local_mode_permits_arbitrary_local_port(self):
        # ALLOWED_HOSTSには含まれない任意のローカルポートでも、mode="local"なら許可される
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 0))]):
            self.assertTrue(config.is_host_allowed("127.0.0.1:5173", mode="local"))

    def test_is_host_allowed_without_mode_still_requires_allowlist_membership(self):
        # mode未指定(staging/readonly相当)では、従来どおりALLOWED_HOSTSとの完全一致が必要
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 0))]):
            self.assertFalse(config.is_host_allowed("127.0.0.1:5173"))

    def test_is_host_allowed_local_mode_still_rejects_public_host(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            self.assertFalse(config.is_host_allowed("public.example.com", mode="local"))

    def test_check_netloc_safe_local_mode_allows_private_ip(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.1.2.3", 0))]):
            safe, reason = security.check_netloc_safe("internal.example.com", mode="local")
        self.assertTrue(safe)
        self.assertIsNone(reason)

    def test_check_netloc_safe_local_mode_still_blocks_metadata_address(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("169.254.169.254", 0))]):
            safe, reason = security.check_netloc_safe("attacker.example.com", mode="local")
        self.assertFalse(safe)

    def test_check_netloc_safe_local_mode_still_blocks_denylisted_host(self):
        original = config.DENYLIST_HOSTNAMES
        config.DENYLIST_HOSTNAMES = {"cyberace.co.jp"}
        try:
            with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
                safe, reason = security.check_netloc_safe("cyberace.co.jp", mode="local")
            self.assertFalse(safe)
        finally:
            config.DENYLIST_HOSTNAMES = original

    def test_check_netloc_safe_without_mode_still_blocks_private_ip(self):
        # 既存の(staging向けの)挙動が変わっていないことの回帰確認
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.1.2.3", 0))]):
            safe, reason = security.check_netloc_safe("internal.example.com")
        self.assertFalse(safe)


if __name__ == "__main__":
    unittest.main()
