-- v0.6 P2(L-3): 同意の記録。利用規約・プライバシーポリシーへの同意(サインアップ時)と、
-- 実行ごとの「このドメインをテストする権限がある」旨の同意の両方を、同じテーブルで扱う。

CREATE TABLE consents (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  organization_id BIGINT NULL,
  consent_type VARCHAR(20) NOT NULL,
  policy_version VARCHAR(20) NOT NULL,
  target_hostname VARCHAR(255) NULL,
  ip_hash VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_consents_user FOREIGN KEY (user_id) REFERENCES users(id)
);
