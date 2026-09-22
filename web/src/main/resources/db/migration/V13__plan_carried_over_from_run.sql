-- v0.8第4章: 「前回の結果を引き継ぐ」を選んだときの、比較対象の前回Run(Java側のrunId)。
ALTER TABLE plan_records ADD COLUMN carried_over_from_run_id VARCHAR(64);
