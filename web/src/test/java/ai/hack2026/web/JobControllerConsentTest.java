package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.consent.Consent;
import ai.hack2026.web.consent.ConsentService;
import ai.hack2026.web.credential.CredentialEncryptionService;
import ai.hack2026.web.credential.TestCredential;
import ai.hack2026.web.credential.TestCredentialRepository;
import ai.hack2026.web.domain.Domain;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.domain.DomainSafetyChecker;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.6 P2(L-3、開発者からの指摘): 同意なしでは実行が始まらないこと(JobController)。
 * v0.7(第1・1a・1b節): 対象の分類(ローカル/公開/遮断)とプロジェクトの種類(kind)の組み合わせで
 * 許可の内容が決まること。127.0.0.1:8765(デモサイト)はループバック=ローカル扱いになる点に注意
 * (v0.6まではこのホストも所有確認が必要だったが、v0.7ではローカル宣言に置き換わった)。
 * SpringBootTestは使わず、依存をすべてMockitoでモックした素のユニットテスト。 */
class JobControllerConsentTest {

    private static final Long PROJECT_ID = 99L;
    private static final String LOCAL_HOST = "127.0.0.1:8765";
    private static final String PUBLIC_HOST = "shop.example.com";

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final ConsentService consentService = Mockito.mock(ConsentService.class);
    private final DomainRepository domainRepository = Mockito.mock(DomainRepository.class);
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final PlanRecordRepository planRecordRepository = Mockito.mock(PlanRecordRepository.class);
    private final ai.hack2026.web.execution.SpecRecordRepository specRecordRepository =
            Mockito.mock(ai.hack2026.web.execution.SpecRecordRepository.class);
    private final DomainSafetyChecker domainSafetyChecker = Mockito.mock(DomainSafetyChecker.class);
    private final TestCredentialRepository testCredentialRepository = Mockito.mock(TestCredentialRepository.class);
    private final CredentialEncryptionService credentialEncryptionService = Mockito.mock(CredentialEncryptionService.class);
    private final JobController controller = new JobController(
            workerClient, consentService, domainRepository, projectRepository, planRecordRepository,
            specRecordRepository, domainSafetyChecker, testCredentialRepository, credentialEncryptionService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", 2L, Role.OWNER, null);

    private Project devEnvProject;

    @BeforeEach
    void setUpProject() {
        devEnvProject = Mockito.mock(Project.class);
        when(devEnvProject.getId()).thenReturn(PROJECT_ID);
        when(devEnvProject.isPublicReadonly()).thenReturn(false);
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, user.getOrganizationId()))
                .thenReturn(Optional.of(devEnvProject));
        when(domainSafetyChecker.classify(LOCAL_HOST)).thenReturn(DomainSafetyChecker.HostClass.LOCAL_OR_PRIVATE);
        when(domainSafetyChecker.classify(PUBLIC_HOST)).thenReturn(DomainSafetyChecker.HostClass.PUBLIC);
    }

    @Test
    void rejectsExecutionWhenProjectNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(projectRepository.findByIdAndOrganizationId(any(), any())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", LOCAL_HOST, true, null, request));

        assertEquals(404, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
    }

    @Test
    void rejectsExecutionWithoutConsentCheckbox() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", LOCAL_HOST, false, null, request));

        assertEquals(400, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
        verify(consentService, never()).recordPerExecutionConsent(any(), any(), anyString(), any());
    }

    @Test
    void rejectsExecutionWhenDomainConfirmationDoesNotMatch() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", "not-the-right-host", true, null, request));

        assertEquals(400, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
    }

    @Test
    void rejectsMetadataAddressRegardlessOfProjectKind() {
        // v0.7: クラウドのメタデータアドレス等は、種類・モードに関わらず常に遮断する
        when(domainSafetyChecker.classify("169.254.169.254")).thenReturn(DomainSafetyChecker.HostClass.BLOCKED);
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://169.254.169.254/", "169.254.169.254", true, null, request));

        assertEquals(403, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
    }

    @Test
    void rejectsLocalHostWhenNotYetDeclared() {
        // v0.7(モードA): ローカル対象は、宣言(local_declared)が無ければ開始できない
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(domainRepository.findByOrganizationIdAndHostname(any(), eq(LOCAL_HOST))).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", LOCAL_HOST, true, null, request));

        assertEquals(403, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
        verify(consentService, never()).recordPerExecutionConsent(any(), any(), anyString(), any());
    }

    @Test
    void proceedsWhenLocalHostAlreadyDeclared() {
        // v0.7(モードA・AC): 所有確認なしでも、ローカル宣言だけで診断を開始できる
        MockHttpServletRequest request = new MockHttpServletRequest();
        Consent consent = Mockito.mock(Consent.class);
        when(consent.getId()).thenReturn(42L);
        when(consentService.recordPerExecutionConsent(anyLong(), anyLong(), anyString(), any())).thenReturn(consent);
        Domain declaredDomain = Mockito.mock(Domain.class);
        when(declaredDomain.getStatus()).thenReturn(Domain.STATUS_LOCAL_DECLARED);
        when(declaredDomain.isDiagnosisAllowed()).thenReturn(true);
        when(declaredDomain.isTestEnvironment()).thenReturn(false);
        when(domainRepository.findByOrganizationIdAndHostname(any(), eq(LOCAL_HOST))).thenReturn(Optional.of(declaredDomain));
        when(workerClient.createPlan(anyString(), anyList(), anyMap(), isNull())).thenReturn(Map.of("planId", "p-1"));

        String view = controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", LOCAL_HOST, true, null, request);

        assertEquals("redirect:/plans/p-1", view);
        verify(consentService, times(1)).recordPerExecutionConsent(1L, 2L, LOCAL_HOST, request.getRemoteAddr());
        verify(workerClient, times(1)).createPlan(anyString(), anyList(), anyMap(), isNull());
        verify(planRecordRepository, times(1)).save(any());
    }

    @Test
    void rejectsPublicHostWhenNotVerified() {
        // v0.7(モードB、既存AC-L1を維持): 公開ホストは、所有確認が無ければ開始できない
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(domainRepository.findByOrganizationIdAndHostname(any(), eq(PUBLIC_HOST))).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://" + PUBLIC_HOST + "/", PUBLIC_HOST, true, null, request));

        assertEquals(403, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
    }

    @Test
    void proceedsWhenPublicHostVerified() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Consent consent = Mockito.mock(Consent.class);
        when(consent.getId()).thenReturn(42L);
        when(consentService.recordPerExecutionConsent(anyLong(), anyLong(), anyString(), any())).thenReturn(consent);
        Domain verifiedDomain = Mockito.mock(Domain.class);
        when(verifiedDomain.getStatus()).thenReturn(Domain.STATUS_VERIFIED);
        when(verifiedDomain.isDiagnosisAllowed()).thenReturn(true);
        when(verifiedDomain.isTestEnvironment()).thenReturn(false);
        when(domainRepository.findByOrganizationIdAndHostname(any(), eq(PUBLIC_HOST))).thenReturn(Optional.of(verifiedDomain));
        when(workerClient.createPlan(anyString(), anyList(), anyMap(), isNull())).thenReturn(Map.of("planId", "p-1"));

        String view = controller.create(user, PROJECT_ID, "http://" + PUBLIC_HOST + "/", PUBLIC_HOST, true, null, request);

        assertEquals("redirect:/plans/p-1", view);
        verify(workerClient, times(1)).createPlan(anyString(), anyList(), anyMap(), isNull());
    }

    /** v0.7 P5(ログインが必要な画面の点検)の回帰テスト。実装中に一度、testAccountを
     * authorizationの中に混ぜてしまい、Planに保存されるauthorization経由でパスワードが
     * plan.jsonへ永続化される不具合を作り込んでいた(ライブ確認で発見・修正)。testAccountは
     * authorizationとは別の最上位引数として渡さなければならないことを、ここで固定する。 */
    @Test
    void testAccountIsPassedSeparatelyFromAuthorizationNotNestedInsideIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Consent consent = Mockito.mock(Consent.class);
        when(consent.getId()).thenReturn(42L);
        when(consentService.recordPerExecutionConsent(anyLong(), anyLong(), anyString(), any())).thenReturn(consent);
        Domain declaredDomain = Mockito.mock(Domain.class);
        when(declaredDomain.getStatus()).thenReturn(Domain.STATUS_LOCAL_DECLARED);
        when(declaredDomain.isDiagnosisAllowed()).thenReturn(true);
        when(declaredDomain.isTestEnvironment()).thenReturn(false);
        when(domainRepository.findByOrganizationIdAndHostname(any(), eq(LOCAL_HOST))).thenReturn(Optional.of(declaredDomain));

        TestCredential credential = Mockito.mock(TestCredential.class);
        when(credential.getUsername()).thenReturn("alice@example.test");
        when(credential.getEncryptedPassword()).thenReturn("cipher");
        when(credential.getIv()).thenReturn("iv");
        when(testCredentialRepository.findByProjectIdAndOrganizationId(PROJECT_ID, user.getOrganizationId()))
                .thenReturn(Optional.of(credential));
        when(credentialEncryptionService.decrypt("cipher", "iv")).thenReturn("demo-pass-1");
        when(workerClient.createPlan(anyString(), anyList(), any(), any())).thenReturn(Map.of("planId", "p-1"));

        controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", LOCAL_HOST, true, null, request);

        var authorizationCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        var testAccountCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(workerClient, times(1)).createPlan(anyString(), anyList(), authorizationCaptor.capture(), testAccountCaptor.capture());

        assertEquals(Map.of("username", "alice@example.test", "password", "demo-pass-1"), testAccountCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(authorizationCaptor.getValue().containsKey("testAccount"),
                "testAccountがauthorizationの中に混ざっている(plan.jsonへ永続化されてしまう)");
        org.junit.jupiter.api.Assertions.assertFalse(String.valueOf(authorizationCaptor.getValue()).contains("demo-pass-1"),
                "authorizationの文字列表現にパスワードが含まれている");
    }

    @Test
    void rejectsPublicReadonlyProjectWhenTargetIsLocal() {
        // v0.7(第1b節): 公開ページの点検(読み取り専用)には、ローカル・プライベートな対象は使えない
        Project readonlyProject = Mockito.mock(Project.class);
        when(readonlyProject.getId()).thenReturn(PROJECT_ID);
        when(readonlyProject.isPublicReadonly()).thenReturn(true);
        when(projectRepository.findByIdAndOrganizationId(PROJECT_ID, user.getOrganizationId()))
                .thenReturn(Optional.of(readonlyProject));
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(user, PROJECT_ID, "http://" + LOCAL_HOST + "/", LOCAL_HOST, true, null, request));

        assertEquals(403, ex.getStatusCode().value());
        verify(workerClient, never()).createPlan(anyString(), anyList(), anyMap(), isNull());
    }
}
