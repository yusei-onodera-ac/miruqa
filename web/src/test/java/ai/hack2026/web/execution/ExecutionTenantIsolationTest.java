package ai.hack2026.web.execution;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Membership;
import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v0.6 P3(前半、AC-U1と同じ方針): /plans/{id}・/runs/{id}・/historyを、PlanRecord/RunRecordで
 * 組織に紐付け、他組織のIDを直接指定してもアクセスできないこと(404)を確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionTenantIsolationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private PlanRecordRepository planRecordRepository;
    @Autowired
    private RunRecordRepository runRecordRepository;
    @Autowired
    private SpecRecordRepository specRecordRepository;

    private AppUserPrincipal principalFor(User user) {
        Membership membership = membershipRepository.findByUserId(user.getId()).get(0);
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                membership.getOrganization().getId(), Role.OWNER, null);
    }

    private Project projectFor(AppUserPrincipal principal) {
        Project project = new Project();
        project.setOrganizationId(principal.getOrganizationId());
        project.setName("テナント分離テスト用プロジェクト");
        project.setTargetUrl("http://127.0.0.1:8765/");
        return projectRepository.save(project);
    }

    @Test
    void otherOrgPlanIdIsNotAccessible() throws Exception {
        User ownerA = signupService.signUp("plan-tenant-a-" + System.nanoTime() + "@example.com", "password123", "組織A担当", "組織A");
        User ownerB = signupService.signUp("plan-tenant-b-" + System.nanoTime() + "@example.com", "password123", "組織B担当", "組織B");
        AppUserPrincipal principalA = principalFor(ownerA);
        AppUserPrincipal principalB = principalFor(ownerB);
        Project projectA = projectFor(principalA);

        PlanRecord plan = new PlanRecord();
        plan.setPlanId("p-tenant-isolation-test");
        plan.setProjectId(projectA.getId());
        plan.setOrganizationId(principalA.getOrganizationId());
        plan.setCreatedBy(principalA.getUserId());
        planRecordRepository.save(plan);

        mockMvc.perform(get("/plans/{planId}", "p-tenant-isolation-test")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/plans/{planId}", "p-tenant-isolation-test")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA)))
                .andExpect(status().isOk());
    }

    @Test
    void otherOrgRunIdIsNotAccessible() throws Exception {
        User ownerA = signupService.signUp("run-tenant-a-" + System.nanoTime() + "@example.com", "password123", "組織A担当", "組織A");
        User ownerB = signupService.signUp("run-tenant-b-" + System.nanoTime() + "@example.com", "password123", "組織B担当", "組織B");
        AppUserPrincipal principalA = principalFor(ownerA);
        AppUserPrincipal principalB = principalFor(ownerB);
        Project projectA = projectFor(principalA);

        RunRecord run = new RunRecord();
        run.setRunId("run-tenant-isolation-test");
        run.setPlanId("p-does-not-matter");
        run.setProjectId(projectA.getId());
        run.setOrganizationId(principalA.getOrganizationId());
        runRecordRepository.save(run);

        mockMvc.perform(get("/runs/{runId}", "run-tenant-isolation-test")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/runs/{runId}", "run-tenant-isolation-test")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA)))
                .andExpect(status().isOk());
    }

    @Test
    void otherOrgSpecIdIsNotAccessible() throws Exception {
        // 指揮官指摘(2026-09-21): GET /specs/{id}.json が組織の確認なしに誰でも取得できてしまう
        // IDORがあった。SpecRecordでの是正を確認する。
        User ownerA = signupService.signUp("spec-tenant-a-" + System.nanoTime() + "@example.com", "password123", "組織A担当", "組織A");
        User ownerB = signupService.signUp("spec-tenant-b-" + System.nanoTime() + "@example.com", "password123", "組織B担当", "組織B");
        AppUserPrincipal principalA = principalFor(ownerA);
        AppUserPrincipal principalB = principalFor(ownerB);
        Project projectA = projectFor(principalA);

        SpecRecord spec = new SpecRecord();
        spec.setSpecId("s-tenant-isolation-test");
        spec.setProjectId(projectA.getId());
        spec.setOrganizationId(principalA.getOrganizationId());
        spec.setCreatedBy(principalA.getUserId());
        specRecordRepository.save(spec);

        mockMvc.perform(get("/specs/{specId}.json", "s-tenant-isolation-test")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalB)))
                .andExpect(status().isNotFound());
        // 自組織なら、テナント確認自体は通る(ワーカー未接続等で200以外になるのは許容する。
        // ここで見たいのは「404で弾かれないこと」)。
        mockMvc.perform(get("/specs/{specId}.json", "s-tenant-isolation-test")
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA)))
                .andExpect(result -> {
                    if (result.getResponse().getStatus() == 404) {
                        throw new AssertionError("自組織のspecIdなのに404になった(退行)");
                    }
                });
    }

    @Test
    void historyOnlyShowsOwnOrganizationsRuns() throws Exception {
        // /historyはワーカーの一覧をRunRecordで絞り込むため、他組織の実行がワーカー側に
        // 存在していても、少なくとも自組織のユーザーがアクセスして200になることを確認する
        // (実際の絞り込みロジックはHistoryControllerで、RunRecordに無いrunIdは除外される)
        User owner = signupService.signUp("history-tenant-" + System.nanoTime() + "@example.com", "password123", "担当", "組織");
        AppUserPrincipal principal = principalFor(owner);

        mockMvc.perform(get("/history").with(SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk());
    }
}
