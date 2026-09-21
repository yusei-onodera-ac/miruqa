package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.findings.FindingStatus;
import ai.hack2026.web.findings.FindingStatusRecord;
import ai.hack2026.web.findings.FindingStatusRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.util.FindingFingerprint;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.7 P6中核(状態管理・抑制)。findings.htmlの元になるFindingsController.findings()と、
 * 状態変更エンドポイントupdateStatus()を、PlanControllerQueueTestと同じ方針
 * (SpringBootTestを使わない素のMockitoユニットテスト)で確認する。
 */
class FindingsControllerTest {

    private static final Long ORG_ID = 2L;
    private static final Long PROJECT_ID = 77L;

    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final FindingStatusRecordRepository findingStatusRecordRepository = Mockito.mock(FindingStatusRecordRepository.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final FindingsController controller = new FindingsController(
            runRecordRepository, projectRepository, workerClient, findingStatusRecordRepository, auditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    private RunRecord dispatchedRunRecord() {
        RunRecord record = new RunRecord();
        record.setRunId("run-1");
        record.setPlanId("p-1");
        record.setProjectId(PROJECT_ID);
        record.setOrganizationId(ORG_ID);
        record.setWorkerRunId("run-1");
        record.setStatus(RunRecord.STATUS_COMPLETED);
        return record;
    }

    private Map<String, Object> findingMap(String detail) {
        return Map.of(
                "perspective", "P-LINK",
                "kind", "unreachable",
                "title", "特定商取引法ページへのリンクが無い",
                "detail", detail,
                "severity", "Med",
                "confidence", "confirmed",
                "url", "https://example.com/",
                "specRef", List.of());
    }

    @Test
    void defaultViewHidesAddressedFindings() {
        RunRecord record = dispatchedRunRecord();
        when(runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(record));
        Project project = Mockito.mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(project.getName()).thenReturn("テストプロジェクト");
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(project));

        Map<String, Object> finding = findingMap("リンクが見つからなかった");
        Map<String, Object> run = new java.util.HashMap<>(Map.of("startedAt", "2026-09-22T00:00:00Z", "findings", List.of(finding)));
        when(workerClient.getRun("run-1")).thenReturn(run);

        String fingerprint = FindingFingerprint.compute(finding);
        FindingStatusRecord statusRecord = new FindingStatusRecord();
        statusRecord.setStatus(FindingStatus.ADDRESSED.name());
        when(findingStatusRecordRepository.findByOrganizationIdAndProjectIdAndFingerprint(ORG_ID, PROJECT_ID, fingerprint))
                .thenReturn(Optional.of(statusRecord));

        Model model = new ExtendedModelMap();
        controller.findings(user, null, null, "UNADDRESSED", model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("findings");
        assertEquals(0, rows.size());
    }

    @Test
    void allFilterShowsAddressedFindingsWithStatusLabel() {
        RunRecord record = dispatchedRunRecord();
        when(runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(record));
        Project project = Mockito.mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(project.getName()).thenReturn("テストプロジェクト");
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(project));

        Map<String, Object> finding = findingMap("リンクが見つからなかった");
        Map<String, Object> run = new java.util.HashMap<>(Map.of("startedAt", "2026-09-22T00:00:00Z", "findings", List.of(finding)));
        when(workerClient.getRun("run-1")).thenReturn(run);

        String fingerprint = FindingFingerprint.compute(finding);
        FindingStatusRecord statusRecord = new FindingStatusRecord();
        statusRecord.setStatus(FindingStatus.ADDRESSED.name());
        when(findingStatusRecordRepository.findByOrganizationIdAndProjectIdAndFingerprint(ORG_ID, PROJECT_ID, fingerprint))
                .thenReturn(Optional.of(statusRecord));

        Model model = new ExtendedModelMap();
        controller.findings(user, null, null, "ALL", model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("findings");
        assertEquals(1, rows.size());
        assertEquals(FindingStatus.ADDRESSED.name(), rows.get(0).get("status"));
        assertEquals("対応済み", rows.get(0).get("statusLabel"));
    }

    @Test
    void findingWithoutStoredStatusDefaultsToUnaddressedAndIsShown() {
        RunRecord record = dispatchedRunRecord();
        when(runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(record));
        Project project = Mockito.mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(project.getName()).thenReturn("テストプロジェクト");
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(project));

        Map<String, Object> finding = findingMap("リンクが見つからなかった");
        Map<String, Object> run = new java.util.HashMap<>(Map.of("startedAt", "2026-09-22T00:00:00Z", "findings", List.of(finding)));
        when(workerClient.getRun("run-1")).thenReturn(run);
        when(findingStatusRecordRepository.findByOrganizationIdAndProjectIdAndFingerprint(eq(ORG_ID), eq(PROJECT_ID), any()))
                .thenReturn(Optional.empty());

        Model model = new ExtendedModelMap();
        controller.findings(user, null, null, "UNADDRESSED", model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("findings");
        assertEquals(1, rows.size());
        assertEquals(FindingStatus.UNADDRESSED.name(), rows.get(0).get("status"));
    }

    @Test
    void updateStatusSavesRecordAndRecordsAudit() {
        Project project = Mockito.mock(Project.class);
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));
        when(findingStatusRecordRepository.findByOrganizationIdAndProjectIdAndFingerprint(ORG_ID, PROJECT_ID, "fp-1"))
                .thenReturn(Optional.empty());

        Map<String, Object> result = controller.updateStatus(user, PROJECT_ID, "fp-1", "ADDRESSED");

        assertEquals("ADDRESSED", result.get("status"));
        verify(findingStatusRecordRepository, times(1)).save(any(FindingStatusRecord.class));
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("finding_status_updated"),
                org.mockito.ArgumentMatchers.contains("fp-1"));
    }

    @Test
    void updateStatusRejectsOtherOrganizationsProject() {
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, ORG_ID)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateStatus(user, PROJECT_ID, "fp-1", "ADDRESSED"));

        assertEquals(404, ex.getStatusCode().value());
        verify(findingStatusRecordRepository, Mockito.never()).save(any());
    }
}
