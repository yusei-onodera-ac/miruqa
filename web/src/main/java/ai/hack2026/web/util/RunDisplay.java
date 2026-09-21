package ai.hack2026.web.util;

import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.worker.WorkerClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.6 P3: 画面一覧(ダッシュボード・プロジェクト詳細・不具合一覧・履歴)がRunRecordから
 * 表示用のRunデータを組み立てる際の共通処理。まだワーカーへ依頼していない(キュー待機中)場合は、
 * ワーカーへは問い合わせず、合成の最小限のデータを返す({@code RunController.statusJson()}と
 * 同じ方針)。返す{@code runId}は常にJava側の安定した識別子(URLで使うもの。ワーカー側の
 * 本当のrunIdとは異なる場合がある)。
 */
public final class RunDisplay {

    private RunDisplay() {
    }

    /** ワーカーへの問い合わせが必要な場合は投げる可能性がある例外(WorkerApiException等)は、
     * 呼び出し側で従来どおりcatchすること。 */
    public static Map<String, Object> resolve(WorkerClient workerClient, RunRecord record) {
        if (!record.isDispatched()) {
            Map<String, Object> run = new HashMap<>();
            run.put("runId", record.getRunId());
            run.put("planId", record.getPlanId());
            run.put("status", "queued");
            run.put("target", null);
            run.put("findings", List.of());
            run.put("startedAt", null);
            run.put("metrics", null);
            return run;
        }
        Map<String, Object> run = workerClient.getRun(record.getWorkerRunId());
        run.put("runId", record.getRunId());
        return run;
    }
}
