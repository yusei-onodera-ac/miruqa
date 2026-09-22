-- v0.6 P1補修(指揮官レビュー): メール確認・パスワード再設定のワンタイムトークン。
-- トークン自体はDBに保存せず、ハッシュ(SHA-256)だけを保存する(漏洩時の悪用を防ぐ)。

CREATE TABLE auth_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  purpose VARCHAR(30) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT uq_auth_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id);
