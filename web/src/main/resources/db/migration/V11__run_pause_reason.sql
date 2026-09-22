-- v0.8第6章: 中断(paused)の理由を保存する列。status=pausedのときだけ意味を持つ。
ALTER TABLE run_records ADD COLUMN pause_reason VARCHAR(64);
