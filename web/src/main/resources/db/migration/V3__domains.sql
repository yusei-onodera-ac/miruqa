-- v0.6 P2(L-1): 対象ドメインの所有確認。/.well-known/<サービス名>-verification.txt方式のみ実装
-- (DNS TXTは伝播遅延リスクからロードマップ行き。設計上の仮定)。
-- 組織単位で保持する(同じ組織の複数プロジェクトが同じドメインを使い回せるように、
-- プロジェクト単位ではなく組織+ホスト名の組で1レコード)。

CREATE TABLE domains (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  hostname VARCHAR(255) NOT NULL,
  scheme VARCHAR(10) NOT NULL DEFAULT 'http',
  status VARCHAR(20) NOT NULL,
  verification_token VARCHAR(64) NOT NULL,
  is_test_environment BOOLEAN NOT NULL DEFAULT FALSE,
  verified_at TIMESTAMP NULL,
  last_checked_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_domains_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT uq_domains_org_hostname UNIQUE (organization_id, hostname)
);
