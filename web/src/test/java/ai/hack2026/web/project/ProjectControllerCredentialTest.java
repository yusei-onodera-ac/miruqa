package ai.hack2026.web.project;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.credential.CredentialEncryptionService;
import ai.hack2026.web.credential.TestCredential;
import ai.hack2026.web.credential.TestCredentialRepository;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.domain.DomainSafetyChecker;
import ai.hack2026.web.domain.DomainVerificationService;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.findings.FindingStatusRecordRepository;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.7 P5(ログインが必要な画面の点検): テスト用アカウントの登録・削除。
 * SpringBootTestは使わず、依存をすべてMockitoでモックした素のユニットテスト
 * (PlanControllerCasesTest等と同じ方針)。 */
class ProjectControllerCredentialTest {

    private static final Long ORG_ID = 2L;
    private static final Long OTHER_ORG_ID = 9L;
    private static final Long PROJECT_ID = 77L;

    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final DomainRepository domainRepository = Mockito.mock(DomainRepository.class);
    private final DomainVerificationService domainVerificationService = Mockito.mock(DomainVerificationService.class);
    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final DomainSafetyChecker domainSafetyChecker = Mockito.mock(DomainSafetyChecker.class);
    private final TestCredentialRepository testCredentialRepository = Mockito.mock(TestCredentialRepository.class);
    private final CredentialEncryptionService credentialEncryptionService = Mockito.mock(CredentialEncryptionService.class);
    private final PlanRecordRepository planRecordRepository = Mockito.mock(PlanRecordRepository.class);
    private final SpecRecordRepository specRecordRepository = Mockito.mock(SpecRecordRepository.class);
    private final FindingStatusRecordRepository findingStatusRecordRepository = Mockito.mock(FindingStatusRecordRepository.class);
    private final ai.hack2026.web.credit.CreditService creditService = Mockito.mock(ai.hack2026.web.credit.CreditService.class);
    private final ProjectController controller = new ProjectController(
            projectRepository, auditService, domainRepository, domainVerificationService,
            runRecordRepository, workerClient, domainSafetyChecker,
            testCredentialRepository, credentialEncryptionService,
            planRecordRepository, specRecordRepository, findingStatusRecordRepository, creditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    private Project stubProject() {
        Project project = Mockito.mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));
        return project;
    }

    @Test
    void saveTestCredentialEncryptsPasswordAndRecordsAudit() {
        stubProject();
        when(testCredentialRepository.findByProjectIdAndOrganizationId(PROJECT_ID, ORG_ID)).thenReturn(Optional.empty());
        when(credentialEncryptionService.encrypt("demo-pass-1"))
                .thenReturn(new CredentialEncryptionService.Encrypted("cipher-abc", "iv-xyz"));

        String view = controller.saveTestCredential(user, PROJECT_ID, "alice@example.test", "demo-pass-1");

        assertEquals("redirect:/projects/" + PROJECT_ID, view);
        verify(credentialEncryptionService, times(1)).encrypt("demo-pass-1");
        var captor = org.mockito.ArgumentCaptor.forClass(TestCredential.class);
        verify(testCredentialRepository, times(1)).save(captor.capture());
        TestCredential saved = captor.getValue();
        assertEquals("alice@example.test", saved.getUsername());
        assertEquals("cipher-abc", saved.getEncryptedPassword());
        assertEquals("iv-xyz", saved.getIv());
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("test_credential_saved"), contains("alice@example.test"));
    }

    @Test
    void saveTestCredentialRejectsBlankPassword() {
        stubProject();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.saveTestCredential(user, PROJECT_ID, "alice@example.test", ""));

        assertEquals(400, ex.getStatusCode().value());
        verify(testCredentialRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveTestCredentialRejectsOtherOrganizationsProject() {
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, ORG_ID)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.saveTestCredential(user, PROJECT_ID, "alice@example.test", "demo-pass-1"));

        assertEquals(404, ex.getStatusCode().value());
        verify(credentialEncryptionService, never()).encrypt(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteTestCredentialRemovesRecordAndRecordsAudit() {
        stubProject();

        String view = controller.deleteTestCredential(user, PROJECT_ID);

        assertEquals("redirect:/projects/" + PROJECT_ID, view);
        verify(testCredentialRepository, times(1)).deleteByProjectIdAndOrganizationId(PROJECT_ID, ORG_ID);
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("test_credential_deleted"), contains("projectId=" + PROJECT_ID));
    }

    @Test
    void deleteTestCredentialRejectsOtherOrganizationsProject() {
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, OTHER_ORG_ID)).thenReturn(Optional.empty());
        AppUserPrincipal otherOrgUser = new AppUserPrincipal(5L, "other@example.com", "hash", OTHER_ORG_ID, Role.OWNER, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteTestCredential(otherOrgUser, PROJECT_ID));

        assertEquals(404, ex.getStatusCode().value());
        verify(testCredentialRepository, never()).deleteByProjectIdAndOrganizationId(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** v0.7 P7(縮小): プロジェクト単位の削除。 */
    @Test
    void deleteProjectRemovesAllRelatedRecordsWhenNameConfirmed() {
        Project project = stubProject();
        when(project.getName()).thenReturn("テストプロジェクト");
        when(runRecordRepository.findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(PROJECT_ID, ORG_ID))
                .thenReturn(java.util.List.of());
        when(planRecordRepository.findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(PROJECT_ID, ORG_ID))
                .thenReturn(java.util.List.of());
        when(specRecordRepository.findByProjectIdAndOrganizationId(PROJECT_ID, ORG_ID))
                .thenReturn(java.util.List.of());
        when(findingStatusRecordRepository.findByOrganizationIdAndProjectId(ORG_ID, PROJECT_ID))
                .thenReturn(java.util.List.of());

        String view = controller.deleteProject(user, PROJECT_ID, "テストプロジェクト");

        assertEquals("redirect:/projects", view);
        verify(testCredentialRepository, times(1)).deleteByProjectIdAndOrganizationId(PROJECT_ID, ORG_ID);
        verify(projectRepository, times(1)).delete(project);
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("project_deleted"), contains("projectId=" + PROJECT_ID));
    }

    @Test
    void deleteProjectRejectsWhenConfirmationNameDoesNotMatch() {
        Project project = stubProject();
        when(project.getName()).thenReturn("テストプロジェクト");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteProject(user, PROJECT_ID, "違う名前"));

        assertEquals(400, ex.getStatusCode().value());
        verify(projectRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteProjectRejectsOtherOrganizationsProject() {
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, OTHER_ORG_ID)).thenReturn(Optional.empty());
        AppUserPrincipal otherOrgUser = new AppUserPrincipal(5L, "other@example.com", "hash", OTHER_ORG_ID, Role.OWNER, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteProject(otherOrgUser, PROJECT_ID, "anything"));

        assertEquals(404, ex.getStatusCode().value());
        verify(projectRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
