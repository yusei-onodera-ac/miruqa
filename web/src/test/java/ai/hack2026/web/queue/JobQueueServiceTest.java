package ai.hack2026.web.queue;

import ai.hack2026.web.auth.Membership;
import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import ai.hack2026.web.credential.CredentialEncryptionService;
import ai.hack2026.web.credential.TestCredentialRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.6 P3(O-1・O-2): ジョブキューの同時実行数の上限(AC-O1、開発者の指示で組織あたり2件)と、
 * 実行ごとのタイムアウト(O-2)を確認する。WorkerClientはMockitoでモックし、実LLM呼び出しは0件。
 * run_recordsのproject_id/organization_idは実テーブルへのFK制約があるため、
 * ExecutionTenantIsolationTestと同じ方針で、SignupService経由の実データを使う。
 */
@SpringBootTest
@ActiveProfiles("test")
class JobQueueServiceTest {

    @Autowired
    private RunRecordRepository runRecordRepository;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private ProjectRepository projectRepository;

    private WorkerClient workerClient;
    private UsageService usageService;
    private TestCredentialRepository testCredentialRepository;
    private CredentialEncryptionService credentialEncryptionService;
    private JobQueueService service;
    private Long orgId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        workerClient = Mockito.mock(WorkerClient.class);
        usageService = Mockito.mock(UsageService.class);
        testCredentialRepository = Mockito.mock(TestCredentialRepository.class);
        credentialEncryptionService = Mockito.mock(CredentialEncryptionService.class);
        service = new JobQueueService(runRecordRepository, workerClient, usageService,
                testCredentialRepository, credentialEncryptionService, 2, 900);

        User owner = signupService.signUp(
                "job-queue-test-" + System.nanoTime() + "@example.com", "password123", "テスト担当", "テスト組織");
        Membership membership = membershipRepository.findByUserId(owner.getId()).get(0);
        orgId = membership.getOrganization().getId();

        Project project = new Project();
        project.setOrganizationId(orgId);
        project.setName("ジョブキューテスト用プロジェクト");
        project.setTargetUrl("http://127.0.0.1:8765/");
        projectId = projectRepository.save(project).getId();
    }

    private Long otherOrganization() {
        User owner = signupService.signUp(
                "job-queue-other-org-" + System.nanoTime() + "@example.com", "password123", "他組織担当", "他組織");
        return membershipRepository.findByUserId(owner.getId()).get(0).getOrganization().getId();
    }

    @Test
    void enqueuedJobStartsQueuedAndNotDispatched() {
        RunRecord record = service.enqueue("p-1", projectId, orgId);

        assertEquals(RunRecord.STATUS_QUEUED, record.getStatus());
        assertNull(record.getWorkerRunId());
        verify(workerClient, never()).startRun(anyString());
    }

    @Test
    void thirdConcurrentJobStaysQueuedUntilASlotFrees() {
        // AC-O1: 同時実行は組織ごとに2件まで。3件目はqueuedのまま待つ。
        RunRecord r1 = service.enqueue("p-1", projectId, orgId);
        RunRecord r2 = service.enqueue("p-2", projectId, orgId);
        RunRecord r3 = service.enqueue("p-3", projectId, orgId);

        when(workerClient.startRun("p-1")).thenReturn(Map.of("runId", "run-1"));
        when(workerClient.startRun("p-2")).thenReturn(Map.of("runId", "run-2"));
        when(workerClient.getRun("run-1")).thenReturn(Map.of("status", "running"));
        when(workerClient.getRun("run-2")).thenReturn(Map.of("status", "running"));

        service.dispatchQueuedIfCapacity(orgId);

        RunRecord r1After = reload(r1);
        RunRecord r2After = reload(r2);
        RunRecord r3After = reload(r3);
        assertEquals(RunRecord.STATUS_RUNNING, r1After.getStatus());
        assertEquals(RunRecord.STATUS_RUNNING, r2After.getStatus());
        assertEquals(RunRecord.STATUS_QUEUED, r3After.getStatus());
        assertNull(r3After.getWorkerRunId());
        verify(workerClient, never()).startRun("p-3");
        assertEquals(2, service.activeCount(orgId));

        // r1が完了すると、空きができてr3がディスパッチされる
        when(workerClient.getRun("run-1")).thenReturn(Map.of("status", "completed"));
        when(workerClient.startRun("p-3")).thenReturn(Map.of("runId", "run-3"));
        when(workerClient.getRun("run-3")).thenReturn(Map.of("status", "running"));

        service.dispatchQueuedIfCapacity(orgId);

        RunRecord r3Dispatched = reload(r3);
        assertEquals(RunRecord.STATUS_RUNNING, r3Dispatched.getStatus());
        assertEquals("run-3", r3Dispatched.getWorkerRunId());
        RunRecord r1Completed = reload(r1);
        assertEquals("completed", r1Completed.getStatus());
        verify(usageService, times(1)).recordIfAbsent(argThatRunId(r1.getRunId()));
    }

    @Test
    void otherOrganizationsQueueDoesNotAffectThisOrgsConcurrency() {
        // 組織ごとの上限であること(他組織の実行中ジョブがカウントに影響しない)
        Long otherOrgId = otherOrganization();
        service.enqueue("p-other-1", projectId, otherOrgId);
        service.enqueue("p-other-2", projectId, otherOrgId);
        when(workerClient.startRun(anyString())).thenReturn(Map.of("runId", "run-other"));
        when(workerClient.getRun("run-other")).thenReturn(Map.of("status", "running"));
        service.dispatchQueuedIfCapacity(otherOrgId);

        RunRecord r1 = service.enqueue("p-1", projectId, orgId);
        when(workerClient.startRun("p-1")).thenReturn(Map.of("runId", "run-1"));
        when(workerClient.getRun("run-1")).thenReturn(Map.of("status", "running"));
        service.dispatchQueuedIfCapacity(orgId);

        assertEquals(RunRecord.STATUS_RUNNING, reload(r1).getStatus());
    }

    @Test
    void workerUnreachableDuringSyncKeepsRecordActiveInsteadOfCrashing() {
        // AC-O2: ワーカーが一時的に応答しなくても、画面が壊れず(例外を投げず)アクティブ扱いのままにする
        RunRecord record = service.enqueue("p-1", projectId, orgId);
        when(workerClient.startRun("p-1")).thenReturn(Map.of("runId", "run-1"));
        when(workerClient.getRun("run-1"))
                .thenThrow(new ai.hack2026.web.worker.WorkerUnavailableException("worker down", null));
        service.dispatchQueuedIfCapacity(orgId);

        int active = service.activeCount(orgId);

        assertEquals(1, active);
        assertEquals(RunRecord.STATUS_RUNNING, reload(record).getStatus());
    }

    @Test
    void timeoutCancelsLongRunningJobAndRecordsUsage() {
        RunRecord record = service.enqueue("p-1", projectId, orgId);
        record.setWorkerRunId("run-1");
        record.setStatus(RunRecord.STATUS_RUNNING);
        record.setStartedAt(Instant.now().minusSeconds(120));
        runRecordRepository.save(record);
        JobQueueService shortTimeoutService = new JobQueueService(runRecordRepository, workerClient, usageService,
                testCredentialRepository, credentialEncryptionService, 2, 60);

        shortTimeoutService.checkTimeouts();

        RunRecord after = reload(record);
        assertEquals(RunRecord.STATUS_TIMEOUT, after.getStatus());
        assertTrue(after.getErrorReason().contains("タイムアウト"));
        verify(workerClient, times(1)).cancelRun("run-1");
        verify(usageService, times(1)).recordIfAbsent(argThatRunId(record.getRunId()));
    }

    @Test
    void queuePositionReflectsOrderWithinOrganization() {
        RunRecord r1 = service.enqueue("p-1", projectId, orgId);
        RunRecord r2 = service.enqueue("p-2", projectId, orgId);

        assertEquals(1, service.queuePositionOf(r1));
        assertEquals(2, service.queuePositionOf(r2));
    }

    private RunRecord reload(RunRecord record) {
        return runRecordRepository.findById(record.getId()).orElseThrow();
    }

    private static RunRecord argThatRunId(String runId) {
        return org.mockito.ArgumentMatchers.argThat(r -> r != null && runId.equals(r.getRunId()));
    }
}
