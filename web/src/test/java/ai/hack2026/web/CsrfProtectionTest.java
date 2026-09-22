package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Membership;
import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import ai.hack2026.web.domain.Domain;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v0.6 P2(AC-U2、指揮官指摘 2026-09-21): 状態を変えるすべてのPOSTエンドポイントに、CSRF保護が
 * 実際にかかっていることを確認する。CSRFトークンなし→403、トークンあり→403にならないこと
 * (ビジネスロジック上の別の理由(400/404/503等)で拒否されるのは正常。CSRF自体が通ることだけを見る)。
 *
 * 実際に、index.html(点検フォーム)がth:actionではなくプレーンなaction属性を使っていたため
 * CSRFトークンが自動注入されず、ログイン済みユーザーが新規点検を開始しようとすると常に403に
 * なる、という不具合が本番相当のコードに入っていた(このテストが無かったために発見が遅れた)。
 * 同種の不具合が plan.html(承認フォーム・続き生成フォーム)・run.html(承認のfetch)にも
 * 見つかったため、再発防止としてこのテストを追加する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfProtectionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private PlanRecordRepository planRecordRepository;
    @Autowired
    private RunRecordRepository runRecordRepository;

    private AppUserPrincipal principal;

    @BeforeEach
    void setUp() {
        String email = "csrf-test-" + System.nanoTime() + "@example.com";
        User user = signupService.signUp(email, "password123", "テスト担当", "テスト組織" + System.nanoTime());
        Membership membership = membershipRepository.findByUserId(user.getId()).get(0);
        principal = new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                membership.getOrganization().getId(), Role.OWNER, null);
    }

    /** /jobsはAC-L1(所有確認済みでないと診断を開始できない)の対象のため、CSRFの確認だけを
     * 独立して見るには、あらかじめ確認済みのDomainを用意しておく必要がある。
     * v0.7: 127.0.0.1:8765はループバック(ローカル)に分類されるため、所有確認(verified)ではなく
     * ローカル宣言(local_declared)を用意する(モードAのゲート)。 */
    private void markDomainVerified(String hostname) {
        Domain domain = new Domain();
        domain.setOrganizationId(principal.getOrganizationId());
        domain.setHostname(hostname);
        domain.setVerificationToken("test-token");
        domain.setStatus(Domain.STATUS_LOCAL_DECLARED);
        domainRepository.save(domain);
    }

    /** /plans/{id}・/runs/{id}はv0.6 P3(前半)のテナント分離対象のため、レンダリング確認のためだけ
     * でも、あらかじめ自組織のPlanRecord/RunRecordを用意しておく必要がある。 */
    private Project createProjectForRecords() {
        Project project = new Project();
        project.setOrganizationId(principal.getOrganizationId());
        project.setName("CSRFレンダリングテスト用プロジェクト");
        project.setTargetUrl("http://127.0.0.1:8765/");
        return projectRepository.save(project);
    }

    private void registerPlan(String planId, Long projectId) {
        PlanRecord record = new PlanRecord();
        record.setPlanId(planId);
        record.setProjectId(projectId);
        record.setOrganizationId(principal.getOrganizationId());
        record.setCreatedBy(principal.getUserId());
        planRecordRepository.save(record);
    }

    private void registerRun(String runId, String planId, Long projectId) {
        RunRecord record = new RunRecord();
        record.setRunId(runId);
        record.setPlanId(planId);
        record.setProjectId(projectId);
        record.setOrganizationId(principal.getOrganizationId());
        runRecordRepository.save(record);
    }

    private void assertCsrfIsEnforced(String path, String... params) throws Exception {
        // トークンなし→403(CSRF検証失敗)
        var noToken = post(path).with(SecurityMockMvcRequestPostProcessors.user(principal));
        for (int i = 0; i + 1 < params.length; i += 2) {
            noToken = noToken.param(params[i], params[i + 1]);
        }
        mockMvc.perform(noToken).andExpect(status().isForbidden());

        // トークンあり→403にはならない(ビジネスロジック上の別の結果は許容する)
        var withToken = post(path)
                .with(SecurityMockMvcRequestPostProcessors.user(principal))
                .with(SecurityMockMvcRequestPostProcessors.csrf());
        for (int i = 0; i + 1 < params.length; i += 2) {
            withToken = withToken.param(params[i], params[i + 1]);
        }
        mockMvc.perform(withToken).andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status == 403) {
                throw new AssertionError(path + " がCSRFトークンありでも403になった(退行)");
            }
        });
    }

    @Test
    void jobsRequiresCsrf() throws Exception {
        markDomainVerified("127.0.0.1:8765");
        Project project = new Project();
        project.setOrganizationId(principal.getOrganizationId());
        project.setName("CSRFテスト用プロジェクト");
        project.setTargetUrl("http://127.0.0.1:8765/");
        project = projectRepository.save(project);
        assertCsrfIsEnforced("/jobs",
                "projectId", String.valueOf(project.getId()),
                "url", "http://127.0.0.1:8765/", "domainConfirmation", "127.0.0.1:8765", "consentGiven", "true");
    }

    @Test
    void planApproveRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/plans/p-does-not-exist/approve");
    }

    @Test
    void planResumeRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/plans/p-does-not-exist/resume");
    }

    /** v0.7 3.6a(自然言語での項目追加)で新設した2エンドポイント。JSON body(fetch)だが、
     * CSRFフィルタ自体はコンテンツタイプに関わらずサーブレットフィルタの段階でかかるため、
     * トークン無し→403の確認は、他のPOSTエンドポイントと同じ形で行える。 */
    @Test
    void planCasesProposeRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/plans/p-does-not-exist/cases/propose");
    }

    @Test
    void planCasesAddRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/plans/p-does-not-exist/cases");
    }

    /** v0.7 P6中核(状態管理・抑制)で新設したエンドポイント。 */
    @Test
    void findingsStatusRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/findings/status", "projectId", "999999", "fingerprint", "fp-1", "status", "ADDRESSED");
    }

    @Test
    void runApprovalRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/runs/r-does-not-exist/approvals/a-does-not-exist");
    }

    @Test
    void runStopRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/runs/r-does-not-exist/stop");
    }

    /** v0.7 P6中核(再実行)で新設したエンドポイント。 */
    @Test
    void runRerunRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/runs/r-does-not-exist/rerun");
    }

    @Test
    void projectCreateRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/projects", "name", "テストプロジェクト", "targetUrl", "http://127.0.0.1:8765/");
    }

    @Test
    void domainVerificationEndpointsRequireCsrf() throws Exception {
        assertCsrfIsEnforced("/projects/999999/domain/start");
        assertCsrfIsEnforced("/projects/999999/domain/check");
        assertCsrfIsEnforced("/projects/999999/domain/declare-test-environment");
    }

    /** v0.7 P5(ログインが必要な画面の点検)で新設したエンドポイント。 */
    @Test
    void testCredentialEndpointsRequireCsrf() throws Exception {
        assertCsrfIsEnforced("/projects/999999/credentials", "username", "alice@example.test", "password", "demo-pass-1");
        assertCsrfIsEnforced("/projects/999999/credentials/delete");
    }

    /** v0.7 P7(縮小、プロジェクト単位の削除)で新設したエンドポイント。 */
    @Test
    void projectDeleteRequiresCsrf() throws Exception {
        assertCsrfIsEnforced("/projects/999999/delete", "confirmName", "anything");
    }

    @Test
    void signupRequiresCsrf() throws Exception {
        // 未ログインの公開エンドポイントでも、CSRF保護は認証と独立にかかる
        mockMvc.perform(post("/signup")).andExpect(status().isForbidden());
        mockMvc.perform(post("/signup").with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(result -> {
                    if (result.getResponse().getStatus() == 403) {
                        throw new AssertionError("/signup がCSRFトークンありでも403になった(退行)");
                    }
                });
    }

    @Test
    void forgotPasswordAndResetPasswordRequireCsrf() throws Exception {
        mockMvc.perform(post("/forgot-password")).andExpect(status().isForbidden());
        mockMvc.perform(post("/reset-password")).andExpect(status().isForbidden());
    }

    @Test
    void planPageRendersCsrfTokenForApproveAndResumeForms() throws Exception {
        // plan.html の approveForm(静的hidden入力)・resumeフォーム(JSテンプレート文字列)の両方に
        // CSRFトークンの埋め込みが残っていることを、レンダリング結果で確認する
        // (v0.6 P3前半のテナント分離対象のため、自組織のPlanRecordを用意してからアクセスする)
        Project project = createProjectForRecords();
        registerPlan("p-csrf-render-test", project.getId());
        mockMvc.perform(get("/plans/p-csrf-render-test").with(SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("const csrfToken =")));
    }

    @Test
    void runPageRendersCsrfMetaTagsForApprovalFetch() throws Exception {
        // run.html の decide()(fetchによる承認・却下)が使うCSRFメタタグが、実際に出力されることを確認する
        Project project = createProjectForRecords();
        registerPlan("p-csrf-render-test-2", project.getId());
        registerRun("r-csrf-render-test", "p-csrf-render-test-2", project.getId());
        mockMvc.perform(get("/runs/r-csrf-render-test").with(SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf_header\"")));
    }

    @Test
    void findingsPageRendersCsrfMetaTagsForStatusFetch() throws Exception {
        // findings.html の状態変更セレクト(fetchによるPOST)が使うCSRFメタタグが出力されることを確認する
        mockMvc.perform(get("/findings").with(SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf_header\"")));
    }
}
