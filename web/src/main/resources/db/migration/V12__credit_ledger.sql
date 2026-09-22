-- v0.8第2章・第6章: クレジット制(USDはユーザー向け画面から全廃)。
-- 残高は保持せず、常にこの台帳(credit_ledger)のamount_creditsの合計から計算する
-- (イベントソーシング。可変な残高列を持たないため、更新の競合・ズレが起きない)。
-- kind: charge(チャージ。amount_creditsは正)／consume(LLM呼び出し1回分の消費。amount_creditsは負)／
-- refund(返金。正)。
-- 二重課金の防止(v0.8第6章): 同じ(run_id, request_id)の組み合わせでconsumeを二重に記録しない
-- ようUNIQUE制約を張る(charge/refundはrun_id・request_idともNULLのままでよく、
-- 標準SQLの挙動によりNULL同士は一意制約に抵触しない)。
CREATE TABLE credit_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  kind VARCHAR(20) NOT NULL,
  amount_credits BIGINT NOT NULL,
  run_id VARCHAR(64),
  request_id VARCHAR(128),
  payment_reference VARCHAR(64),
  detail VARCHAR(500),
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_credit_ledger_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT uq_credit_ledger_consume UNIQUE (run_id, request_id)
);
CREATE INDEX idx_credit_ledger_org_created ON credit_ledger(organization_id, created_at);
