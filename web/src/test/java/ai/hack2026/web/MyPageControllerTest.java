package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Organization;
import ai.hack2026.web.auth.OrganizationRepository;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.auth.User;
import ai.hack2026.web.auth.UserRepository;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.8第5章: マイページ。組織スコープのリポジトリ経由でしか自分のデータを取得しないこと
 * (他組織のuserId/organizationIdを渡されても、自分自身のAppUserPrincipalの値しか使わない)、
 * 名前・組織名の変更、空欄が拒否されることを確認する(SpringBootTestは使わず、
 * 依存をすべてMockitoでモックした素のユニットテスト)。 */
class MyPageControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 2L;

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final OrganizationRepository organizationRepository = Mockito.mock(OrganizationRepository.class);
    private final CreditService creditService = Mockito.mock(CreditService.class);
    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final PlanRecordRepository planRecordRepository = Mockito.mock(PlanRecordRepository.class);
    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final MyPageController controller = new MyPageController(
            userRepository, organizationRepository, creditService, runRecordRepository, projectRepository,
            planRecordRepository, workerClient, auditService);
    private final AppUserPrincipal user = new AppUserPrincipal(USER_ID, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    @Test
    void showUsesOnlyTheAuthenticatedUsersOwnIdsNeverAnyOtherOrganization() {
        User account = Mockito.mock(User.class);
        when(account.getDisplayName()).thenReturn("テスト太郎");
        when(account.getEmail()).thenReturn("test@example.com");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        Organization org = Mockito.mock(Organization.class);
        when(org.getName()).thenReturn("テスト組織");
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(creditService.getBalance(ORG_ID)).thenReturn(1234L);
        when(creditService.history(ORG_ID)).thenReturn(List.of());
        when(runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of());
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of());
        when(planRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        String view = controller.show(user, model);

        assertEquals("mypage", view);
        assertEquals("テスト太郎", model.getAttribute("displayName"));
        assertEquals("テスト組織", model.getAttribute("organizationName"));
        assertEquals(1234L, model.getAttribute("creditBalance"));
        // AppUserPrincipal自身のuserId/organizationId以外は一切問い合わせていないこと
        // (このテストではUSER_ID/ORG_ID以外を一切スタブしていないため、他IDで問い合わせていれば
        // Optional.empty()経由でResponseStatusExceptionになり、このテスト自体が失敗する)。
        verify(creditService, times(1)).getBalance(ORG_ID);
        verify(userRepository, times(1)).findById(USER_ID);
    }

    /** 指揮官バグ報告(2026-09-22): 残高不足等で承認・実行できなかった下見済みプランが、
     * どこからも辿れず「消えた」ように見えていた。RunRecordがまだ無いPlanRecordを
     * 「下見済み・未実行のプラン」として一覧に出すこと。 */
    @Test
    void showListsPlansWithoutARunRecordAsPendingPlans() {
        stubAccountAndOrgFor(1234L);
        PlanRecord plan = new PlanRecord();
        plan.setPlanId("p-pending");
        plan.setProjectId(5L);
        when(planRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(plan));
        when(runRecordRepository.findByPlanIdAndOrganizationId("p-pending", ORG_ID)).thenReturn(Optional.empty());
        Project project = Mockito.mock(Project.class);
        when(project.getId()).thenReturn(5L);
        when(project.getName()).thenReturn("対象プロジェクト");
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(project));

        Model model = new ExtendedModelMap();
        controller.show(user, model);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> pendingPlans = (List<java.util.Map<String, Object>>) model.getAttribute("pendingPlans");
        assertEquals(1, pendingPlans.size());
        assertEquals("p-pending", pendingPlans.get(0).get("planId"));
        assertEquals("対象プロジェクト", pendingPlans.get(0).get("projectName"));
    }

    @Test
    void showExcludesPlansThatAlreadyHaveARunRecordFromPendingPlans() {
        stubAccountAndOrgFor(0L);
        PlanRecord plan = new PlanRecord();
        plan.setPlanId("p-already-run");
        plan.setProjectId(5L);
        when(planRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(plan));
        when(runRecordRepository.findByPlanIdAndOrganizationId("p-already-run", ORG_ID))
                .thenReturn(Optional.of(new RunRecord()));
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller.show(user, model);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> pendingPlans = (List<java.util.Map<String, Object>>) model.getAttribute("pendingPlans");
        assertEquals(0, pendingPlans.size());
    }

    /** show()に必要な最小限のスタブ(アカウント・組織・クレジット・実行履歴)をまとめる。 */
    private void stubAccountAndOrgFor(long creditBalance) {
        User account = Mockito.mock(User.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        Organization org = Mockito.mock(Organization.class);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(creditService.getBalance(ORG_ID)).thenReturn(creditBalance);
        when(creditService.history(ORG_ID)).thenReturn(List.of());
        when(runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of());
    }

    @Test
    void updateAccountChangesDisplayNameAndOrganizationName() {
        User account = Mockito.mock(User.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        Organization org = Mockito.mock(Organization.class);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        String view = controller.updateAccount(user, "新しい名前", "新しい組織名");

        assertEquals("redirect:/mypage", view);
        verify(account, times(1)).setDisplayName("新しい名前");
        verify(org, times(1)).setName("新しい組織名");
        verify(userRepository, times(1)).save(account);
        verify(organizationRepository, times(1)).save(org);
    }

    @Test
    void updateAccountRejectsBlankName() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateAccount(user, "   ", "組織名"));

        assertEquals(400, ex.getStatusCode().value());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void updateAccountRejectsBlankOrganizationName() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateAccount(user, "名前", "   "));

        assertEquals(400, ex.getStatusCode().value());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void updateAccountRecordsAuditLog() {
        User account = Mockito.mock(User.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        Organization org = Mockito.mock(Organization.class);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        controller.updateAccount(user, "新しい名前", "新しい組織名");

        verify(auditService, times(1)).record(eq(ORG_ID), eq(USER_ID), eq("account_updated"), anyString());
    }
}
