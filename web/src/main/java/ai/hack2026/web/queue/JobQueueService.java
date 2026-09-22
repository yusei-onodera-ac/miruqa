package ai.hack2026.web.queue;

import ai.hack2026.web.credential.CredentialEncryptionService;
import ai.hack2026.web.credential.TestCredential;
import ai.hack2026.web.credential.TestCredentialRepository;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerApiException;
import ai.hack2026.web.worker.WorkerClient;
import ai.hack2026.web.worker.WorkerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v0.6 P3(O-1・O-2): DBのジョブキュー。組織ごとの同時実行数を{@code queue.max-concurrent-per-org}
 * (既定2)までに制限する。承認された実行は、必ず{@link #enqueue}でキューに入る(status=queued・
 * workerRunId未設定)。実際にワーカーへ依頼する({@code startRun})のは、空きができてから
 * ({@link #dispatchQueuedIfCapacity}。{@code PlanController.approve()}での即時試行と、
 * {@code JobQueueScheduler}の定期実行の両方から呼ばれる)。
 *
 * 「アクティブ」の判定は、ワーカーへ依頼済みのジョブについては、DBのキャッシュではなく毎回
 * ワーカーへ問い合わせて確定させる({@link #syncActiveRecords}。1組織あたりの規模が小さい前提
 * (CHANGE-v0.6.md第0a章)のため、性能上の問題にはならない)。終了を検知した時点で、使用量を
 * 1回だけ記録する({@link UsageService#recordIfAbsent})。
 */
@Service
public class JobQueueService {

    private static final Logger log = LoggerFactory.getLogger(JobQueueService.class);
    private static final List<String> WORKER_ACTIVE_STATUSES = List.of("queued", "running", "waiting_approval");

    private final RunRecordRepository runRecordRepository;
    private final WorkerClient workerClient;
    private final UsageService usageService;
    private final CreditService creditService;
    private final TestCredentialRepository testCredentialRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final int maxConcurrentPerOrg;
    private final long runTimeoutSec;

    public JobQueueService(
            RunRecordRepository runRecordRepository,
            WorkerClient workerClient,
            UsageService usageService,
            CreditService creditService,
            TestCredentialRepository testCredentialRepository,
            CredentialEncryptionService credentialEncryptionService,
            @Value("${queue.max-concurrent-per-org:2}") int maxConcurrentPerOrg,
            @Value("${queue.run-timeout-sec:900}") long runTimeoutSec) {
        this.runRecordRepository = runRecordRepository;
        this.workerClient = workerClient;
        this.usageService = usageService;
        this.creditService = creditService;
        this.testCredentialRepository = testCredentialRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.maxConcurrentPerOrg = maxConcurrentPerOrg;
        this.runTimeoutSec = runTimeoutSec;
    }

    /** v0.7 P5: プロジェクトに登録済みのテスト用アカウントを復号する(無ければnull)。
     * ワーカーへ渡すためだけに使い、呼び出し元はこれを保存しない。 */
    private Map<String, Object> decryptedTestAccountFor(Long projectId, Long organizationId) {
        TestCredential credential = testCredentialRepository
                .findByProjectIdAndOrganizationId(projectId, organizationId).orElse(null);
        if (credential == null) {
            return null;
        }
        String plainPassword = credentialEncryptionService.decrypt(credential.getEncryptedPassword(), credential.getIv());
        return Map.of("username", credential.getUsername(), "password", plainPassword);
    }

    /** 承認された実行を、キューに入れる(まだワーカーへは依頼しない)。runIdはここで発行する。 */
    public synchronized RunRecord enqueue(String planId, Long projectId, Long organizationId) {
        RunRecord record = new RunRecord();
        record.setRunId(generateRunId());
        record.setPlanId(planId);
        record.setProjectId(projectId);
        record.setOrganizationId(organizationId);
        record.setStatus(RunRecord.STATUS_QUEUED);
        record.setQueuedAt(Instant.now());
        return runRecordRepository.save(record);
    }

    private static String generateRunId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 組織のキューにある(まだ依頼していない)ジョブのうち、指定した1件が何番目に実行されるか(1始まり)。 */
    public int queuePositionOf(RunRecord record) {
        List<RunRecord> queued = runRecordRepository.findByOrganizationIdAndStatusInOrderByCreatedAtAsc(
                record.getOrganizationId(), List.of(RunRecord.STATUS_QUEUED));
        int position = 1;
        for (RunRecord r : queued) {
            if (r.getRunId().equals(record.getRunId())) {
                return position;
            }
            position++;
        }
        return position;
    }

    /** 組織の現在アクティブな実行数(同期後)。 */
    public synchronized int activeCount(Long organizationId) {
        return syncActiveRecords(organizationId).size();
    }

    /** 空きがあれば、キューの先頭からディスパッチする(空きがなくなるまで繰り返す)。 */
    public synchronized void dispatchQueuedIfCapacity(Long organizationId) {
        int active = syncActiveRecords(organizationId).size();
        while (active < maxConcurrentPerOrg) {
            RunRecord next = nextQueued(organizationId);
            if (next == null) {
                return;
            }
            dispatch(next);
            active++;
        }
    }

    private RunRecord nextQueued(Long organizationId) {
        List<RunRecord> queued = runRecordRepository.findByOrganizationIdAndStatusInOrderByCreatedAtAsc(
                organizationId, List.of(RunRecord.STATUS_QUEUED));
        return queued.isEmpty() ? null : queued.get(0);
    }

    private void dispatch(RunRecord record) {
        MDC.put("runId", record.getRunId());
        try {
            // v0.7 P5: テスト用アカウントは、run.jsonへの永続化を避けるため、Planの承認時ではなく
            // 実際にディスパッチする(=実行を開始する)このタイミングで復号して渡す。登録が無い
            // プロジェクトが大多数のため、従来どおりstartRun(planId)を使い分ける。
            Map<String, Object> testAccount = decryptedTestAccountFor(record.getProjectId(), record.getOrganizationId());
            Map<String, Object> run = testAccount == null
                    ? workerClient.startRun(record.getPlanId())
                    : workerClient.startRun(record.getPlanId(), testAccount);
            String workerRunId = String.valueOf(run.get("runId"));
            record.setWorkerRunId(workerRunId);
            record.setStatus(RunRecord.STATUS_RUNNING);
            record.setStartedAt(Instant.now());
            runRecordRepository.save(record);
            log.info("[job-queue] ディスパッチ: runId={} workerRunId={} organizationId={}",
                    record.getRunId(), workerRunId, record.getOrganizationId());
        } catch (WorkerApiException | WorkerUnavailableException e) {
            log.warn("[job-queue] ディスパッチに失敗した(runId={}): {}", record.getRunId(), e.toString());
            record.setStatus(RunRecord.STATUS_FAILED);
            record.setFinishedAt(Instant.now());
            record.setErrorReason(truncate("ワーカーへの依頼に失敗しました: " + e.getMessage()));
            runRecordRepository.save(record);
            usageService.recordIfAbsent(record);
        } finally {
            MDC.remove("runId");
        }
    }

    /** v0.8第6章: 中断(paused)した実行を再開する。まだワーカーへ依頼していなかった(キュー待ちの
     * まま停止した)場合は、単にキューへ戻すだけでよい。既にディスパッチ済みだった場合は、
     * ワーカーの再開API(完了済みのTestCase・探索を再実行しない)を呼ぶ。開始前の残高確認は
     * 呼び出し元(RunController)が行う。 */
    public synchronized void resume(RunRecord record) {
        MDC.put("runId", record.getRunId());
        try {
            if (!record.isDispatched()) {
                record.setStatus(RunRecord.STATUS_QUEUED);
                record.setPauseReason(null);
                record.setFinishedAt(null);
                runRecordRepository.save(record);
                dispatchQueuedIfCapacity(record.getOrganizationId());
                return;
            }
            Map<String, Object> testAccount = decryptedTestAccountFor(record.getProjectId(), record.getOrganizationId());
            workerClient.resumeRun(record.getWorkerRunId(), testAccount);
            record.setStatus(RunRecord.STATUS_RUNNING);
            record.setPauseReason(null);
            record.setFinishedAt(null);
            runRecordRepository.save(record);
            log.info("[job-queue] 再開: runId={} workerRunId={}", record.getRunId(), record.getWorkerRunId());
        } finally {
            MDC.remove("runId");
        }
    }

    /** ディスパッチ済みのジョブの実際の状態をワーカーへ問い合わせて確定させ、
     * 終了していれば状態を確定し使用量を記録する。戻り値は、同時実行数として数えるべきレコード
     * (ディスパッチ済みで、かつまだ終了していないもの)。キューで待っているだけ(まだディスパッチ
     * していない)ジョブは、枠を消費していないため、ここには含めない(呼び出し側の
     * {@link #dispatchQueuedIfCapacity}が、この数に対して上限判定を行う)。 */
    private List<RunRecord> syncActiveRecords(Long organizationId) {
        List<RunRecord> candidates = runRecordRepository.findByOrganizationIdAndStatusInOrderByCreatedAtAsc(
                organizationId, List.of(RunRecord.STATUS_QUEUED, RunRecord.STATUS_RUNNING));
        return candidates.stream()
                .filter(RunRecord::isDispatched)
                .filter(this::syncOne)
                .toList();
    }

    /** trueを返せば、まだ終了していない(ディスパッチ済みのレコードにのみ呼ぶ)。 */
    private boolean syncOne(RunRecord record) {
        MDC.put("runId", record.getRunId());
        try {
            Map<String, Object> run;
            try {
                run = workerClient.getRun(record.getWorkerRunId());
            } catch (WorkerApiException | WorkerUnavailableException e) {
                // ワーカーが一時的に応答しない(再起動中など)。まだアクティブ扱いのままにする
                // (次回の同期で自然に解決する。ワーカー起動時のcleanup_orphaned_stateが、
                // 本当に孤立していたジョブをpaused/failedへ整理してくれる)。
                log.info("[job-queue] 状態確認に失敗した、まだアクティブ扱いとする(runId={}): {}", record.getRunId(), e.toString());
                return true;
            }
            String liveStatus = String.valueOf(run.get("status"));

            // v0.8第2章: 既存のポーリングで、LLM呼び出し単位のリアルタイム減算を行う(request_idで
            // 冪等)。表示専用ではなく、実際にcredit_ledgerへ記録する。
            long consumedNow = consumeNewLlmCalls(record, run);

            if (WORKER_ACTIVE_STATUSES.contains(liveStatus)) {
                if (consumedNow > 0 && !creditService.hasPositiveBalance(record.getOrganizationId())) {
                    // 残高が0以下になった: 協調停止を要求する(即座には止まらない。次回以降の
                    // 同期で"paused"を検知する)。ここでは能動的に理由つきで止める。
                    try {
                        workerClient.cancelRun(record.getWorkerRunId(), "credit_exhausted");
                        log.info("[job-queue] 残高不足のため停止を要求: runId={}", record.getRunId());
                    } catch (WorkerApiException | WorkerUnavailableException e) {
                        log.warn("[job-queue] 残高不足時の停止要求に失敗した(runId={}): {}", record.getRunId(), e.toString());
                    }
                }
                return true;
            }
            // 終了した(completed/failed/paused/cancelled/interrupted)
            record.setStatus(liveStatus);
            record.setFinishedAt(Instant.now());
            if (RunRecord.STATUS_PAUSED.equals(liveStatus)) {
                Object reason = run.get("pauseReason");
                record.setPauseReason(reason == null ? null : String.valueOf(reason));
            } else {
                // paused以外(completed/failed等)は、本当に終わったときだけ内部の原価集計を確定する
                // (pausedは再開できるため、ここで確定すると再開後の追加費用が集計から漏れる)。
                usageService.recordIfAbsent(record);
            }
            runRecordRepository.save(record);
            log.info("[job-queue] 終了を検知: runId={} status={}", record.getRunId(), liveStatus);
            return false;
        } finally {
            MDC.remove("runId");
        }
    }

    /** v0.8第2章: run.jsonのsteps[].llmCalls[]から、まだクレジットを消費していない呼び出し
     * (requestId単位)を見つけて消費を計上する。戻り値は、今回新たに消費したクレジット数の合計
     * (0なら「新しい呼び出しは無かった」の意味。残高チェックのトリガー判定に使う)。 */
    private long consumeNewLlmCalls(RunRecord record, Map<String, Object> run) {
        Object stepsObj = run.get("steps");
        if (!(stepsObj instanceof List<?> steps)) {
            return 0;
        }
        long total = 0;
        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) {
                continue;
            }
            Object callsObj = step.get("llmCalls");
            if (!(callsObj instanceof List<?> calls)) {
                continue;
            }
            for (Object callObj : calls) {
                if (!(callObj instanceof Map<?, ?> call)) {
                    continue;
                }
                Object requestId = call.get("requestId");
                if (requestId == null) {
                    continue;
                }
                double cost = toDouble(call.get("costUsdSettled"));
                if (cost <= 0) {
                    cost = toDouble(call.get("costUsdInline"));
                }
                boolean recorded = creditService.consume(
                        record.getOrganizationId(), record.getRunId(), String.valueOf(requestId), cost);
                if (recorded) {
                    total += creditService.usdToCredits(cost);
                }
            }
        }
        return total;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    /** 実行ごとのタイムアウト(O-2)。ディスパッチ済みで、開始からrunTimeoutSecを超えているものを打ち切る。 */
    public void checkTimeouts() {
        List<RunRecord> running = runRecordRepository.findByStatusIn(List.of(RunRecord.STATUS_RUNNING));
        Instant now = Instant.now();
        for (RunRecord record : running) {
            if (!record.isDispatched() || record.getStartedAt() == null) {
                continue;
            }
            if (Duration.between(record.getStartedAt(), now).getSeconds() < runTimeoutSec) {
                continue;
            }
            MDC.put("runId", record.getRunId());
            log.warn("[job-queue] タイムアウトのため打ち切り: runId={} workerRunId={}", record.getRunId(), record.getWorkerRunId());
            try {
                workerClient.cancelRun(record.getWorkerRunId());
            } catch (WorkerApiException | WorkerUnavailableException e) {
                log.warn("[job-queue] タイムアウト時のキャンセル要求に失敗した(runId={}): {}", record.getRunId(), e.toString());
            } finally {
                MDC.remove("runId");
            }
            // v0.8第6章: タイムアウトも「中断(paused)」で確定し、再開できるようにする
            // (usageServiceへの確定記録は、pausedの間は行わない。再開後の追加費用が漏れるため)。
            record.setStatus(RunRecord.STATUS_PAUSED);
            record.setPauseReason("timeout");
            record.setFinishedAt(now);
            record.setErrorReason("実行時間の上限(" + runTimeoutSec + "秒)を超えたため、一時停止しました。再開すると、続きから実行できます。");
            runRecordRepository.save(record);
        }
    }

    /** Java再起動時の整理(AC-O2の一部): 起動時点でDB上status=queued/runningのまま残っている
     * ジョブは、前回のプロセスの続きではなく確認が必要な状態のため、同期を試みる。
     * ワーカーが停止していれば「まだアクティブ扱い」のまま残り、ワーカー復帰後の定期実行で解決する。 */
    public void resyncAllActiveOnStartup() {
        Set<Long> orgIds = runRecordRepository.findByStatusIn(List.of(RunRecord.STATUS_QUEUED, RunRecord.STATUS_RUNNING))
                .stream().map(RunRecord::getOrganizationId).collect(java.util.stream.Collectors.toSet());
        for (Long orgId : orgIds) {
            syncActiveRecords(orgId);
        }
        log.info("[job-queue] 起動時の同期: 対象組織{}件", orgIds.size());
    }

    /** ワーカーがHTMLのエラーページ等、長いメッセージを返すことがあるため、DB列の長さ内に収める。 */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1900 ? message.substring(0, 1900) + "…" : message;
    }
}
