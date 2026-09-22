package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.6 P2(緊急停止): POST /runs/{id}/stop が、ワーカーへキャンセルを要求し、監査ログに残すこと。
 * v0.6 P3: ディスパッチ済み(workerRunIdあり)か、まだキュー待機中かで挙動が分かれるため、両方を確認する。
 * SpringBootTestは使わず、依存をすべてMockitoでモックした素のユニットテスト。 */
class RunControllerStopTest {

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final ai.hack2026.web.execution.PlanRecordRepository planRecordRepository =
            Mockito.mock(ai.hack2026.web.execution.PlanRecordRepository.class);
    private final JobQueueService jobQueueService = Mockito.mock(JobQueueService.class);
    private final UsageService usageService = Mockito.mock(UsageService.class);
    private final ai.hack2026.web.credit.CreditService creditService = Mockito.mock(ai.hack2026.web.credit.CreditService.class);
    private final RunController controller = new RunController(
            workerClient, auditService, runRecordRepository, planRecordRepository, jobQueueService, usageService, creditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", 2L, Role.OWNER, null);

    @Test
    void stopOnDispatchedRunCallsWorkerCancelAndRecordsAudit() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isDispatched()).thenReturn(true);
        when(record.getWorkerRunId()).thenReturn("worker-run-1");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-1", 2L)).thenReturn(Optional.of(record));
        when(workerClient.cancelRun(anyString(), anyString())).thenReturn(Map.of("runId", "worker-run-1", "status", "cancel_requested"));

        String view = controller.stop(user, "run-1");

        assertEquals("redirect:/runs/run-1", view);
        verify(workerClient, times(1)).cancelRun("worker-run-1", "user_stop");
        verify(auditService, times(1)).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("run_stop_requested"), anyString());
    }

    @Test
    void stopOnQueuedRunPausesWithoutContactingWorker() {
        // v0.6 P3: まだワーカーへ依頼していない(キュー待機中)ジョブの停止は、ワーカーへ連絡しない。
        // v0.8第6章: 失敗にせず「中断(paused)」で確定する(usageServiceへの確定記録もしない。
        // 再開できるため)。
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isDispatched()).thenReturn(false);
        when(record.getRunId()).thenReturn("run-2");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-2", 2L)).thenReturn(Optional.of(record));

        String view = controller.stop(user, "run-2");

        assertEquals("redirect:/runs/run-2", view);
        verify(workerClient, never()).cancelRun(anyString());
        verify(workerClient, never()).cancelRun(anyString(), anyString());
        verify(record, times(1)).setStatus(RunRecord.STATUS_PAUSED);
        verify(record, times(1)).setPauseReason("user_stop");
        verify(usageService, never()).recordIfAbsent(record);
        verify(auditService, times(1)).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("run_stop_requested"), anyString());
    }
}
