package ai.hack2026.web.project;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.credential.CredentialEncryptionService;
import ai.hack2026.web.credential.TestCredential;
import ai.hack2026.web.credential.TestCredentialRepository;
import ai.hack2026.web.domain.Domain;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.domain.DomainSafetyChecker;
import ai.hack2026.web.domain.DomainVerificationService;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.findings.FindingStatusRecordRepository;
import ai.hack2026.web.util.DisplayFormat;
import ai.hack2026.web.worker.WorkerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** プロジェクト管理(U-3)。すべての取得をorganizationIdで絞り込み、他組織のIDを直接指定しても
 * 404になる(U-4テナント分離)。 */
@Controller
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRepository projectRepository;
    private final AuditService auditService;
    private final DomainRepository domainRepository;
    private final DomainVerificationService domainVerificationService;
    private final RunRecordRepository runRecordRepository;
    private final WorkerClient workerClient;
    private final DomainSafetyChecker domainSafetyChecker;
    private final TestCredentialRepository testCredentialRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final PlanRecordRepository planRecordRepository;
    private final SpecRecordRepository specRecordRepository;
    private final FindingStatusRecordRepository findingStatusRecordRepository;
    private final ai.hack2026.web.credit.CreditService creditService;

    public ProjectController(
            ProjectRepository projectRepository,
            AuditService auditService,
            DomainRepository domainRepository,
            DomainVerificationService domainVerificationService,
            RunRecordRepository runRecordRepository,
            WorkerClient workerClient,
            DomainSafetyChecker domainSafetyChecker,
            TestCredentialRepository testCredentialRepository,
            CredentialEncryptionService credentialEncryptionService,
            PlanRecordRepository planRecordRepository,
            SpecRecordRepository specRecordRepository,
            FindingStatusRecordRepository findingStatusRecordRepository,
            ai.hack2026.web.credit.CreditService creditService) {
        this.projectRepository = projectRepository;
        this.auditService = auditService;
        this.domainRepository = domainRepository;
        this.domainVerificationService = domainVerificationService;
        this.runRecordRepository = runRecordRepository;
        this.workerClient = workerClient;
        this.domainSafetyChecker = domainSafetyChecker;
        this.testCredentialRepository = testCredentialRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.planRecordRepository = planRecordRepository;
        this.specRecordRepository = specRecordRepository;
        this.findingStatusRecordRepository = findingStatusRecordRepository;
        this.creditService = creditService;
    }

    @GetMapping("/projects")
    public String list(@AuthenticationPrincipal AppUserPrincipal user, Model model) {
        var projects = projectRepository.findByOrganizationIdOrderByCreatedAtDesc(user.getOrganizationId());
        if (projects.isEmpty()) {
            // v0.6 UIチェックポイント: プロジェクトが1件も無い組織には、オンボーディングの
            // チェックリストを見せる(第3a章: ウィザードではなく、罫線区切りのチェックリスト形式)。
            return "project/onboarding";
        }
        model.addAttribute("projects", projects);
        return "project/list";
    }

    @GetMapping("/projects/new")
    public String newForm(Model model) {
        model.addAttribute("form", new ProjectForm());
        return "project/new";
    }

    /** getter/setter必須(bareフィールドだとSpring MVCの@ModelAttributeバインディングが効かない。
     * AuthController.SignupFormで踏んだ不具合と同じ原因のため、最初からgetter/setterで用意する)。 */
    public static class ProjectForm {
        private String name;
        private String targetUrl;
        private String kind = Project.KIND_DEV_ENV;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
    }

    /** v0.7(第1b節): 種類(kind)は作成時に決め、以後は変更しない(更新用のエンドポイントを
     * 用意しないことで担保する)。対象URLの種類と合わない場合は拒否する
     * (PUBLIC_READONLY×ローカル・プライベート)。 */
    @PostMapping("/projects")
    public String create(@AuthenticationPrincipal AppUserPrincipal user, @ModelAttribute ProjectForm form) {
        String kind = Project.KIND_PUBLIC_READONLY.equals(form.getKind())
                ? Project.KIND_PUBLIC_READONLY : Project.KIND_DEV_ENV;
        String targetUrl = form.getTargetUrl() == null ? "" : form.getTargetUrl().trim();
        String hostname = hostnameOf(targetUrl);
        if (hostname != null) {
            DomainSafetyChecker.HostClass hostClass = domainSafetyChecker.classify(hostname);
            if (hostClass == DomainSafetyChecker.HostClass.BLOCKED) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "この対象(" + hostname + ")は、内部・特殊用途のアドレスのため、プロジェクトを作成できません。");
            }
            if (Project.KIND_PUBLIC_READONLY.equals(kind) && hostClass == DomainSafetyChecker.HostClass.LOCAL_OR_PRIVATE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "公開ページの点検(読み取り専用)には、ローカル・プライベートなURLは指定できません。"
                                + "自分の開発環境を点検する場合は「開発環境の点検」を選んでください。");
            }
        }
        Project project = new Project();
        project.setOrganizationId(user.getOrganizationId());
        project.setName(form.getName() == null || form.getName().isBlank() ? targetUrl : form.getName().trim());
        project.setTargetUrl(targetUrl);
        project.setKind(kind);
        project.setCreatedBy(user.getUserId());
        project = projectRepository.save(project);
        auditService.record(user.getOrganizationId(), user.getUserId(), "project_created",
                "プロジェクト「" + project.getName() + "」(id=" + project.getId() + ", kind=" + kind + ")");
        return "redirect:/projects/" + project.getId();
    }

    @GetMapping("/projects/{id}")
    public String detail(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, Model model) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        model.addAttribute("project", project);
        String hostname = hostnameOf(project.getTargetUrl());
        model.addAttribute("domain", hostname == null ? null
                : domainRepository.findByOrganizationIdAndHostname(user.getOrganizationId(), hostname).orElse(null));
        model.addAttribute("verificationFileName", domainVerificationService.verificationFileName());
        model.addAttribute("executions", executionHistoryFor(project, user.getOrganizationId()));
        model.addAttribute("isLocalHost", hostname != null
                && domainSafetyChecker.classify(hostname) == DomainSafetyChecker.HostClass.LOCAL_OR_PRIVATE);
        TestCredential credential = testCredentialRepository
                .findByProjectIdAndOrganizationId(id, user.getOrganizationId()).orElse(null);
        model.addAttribute("testCredentialUsername", credential == null ? null : credential.getUsername());
        return "project/detail";
    }

    /** v0.7 P5: 対象サイトのログインが必要な画面を点検するための、テスト用アカウントを登録・更新する。
     * パスワードは平文では保存せず、CredentialEncryptionServiceで暗号化してから保存する。
     * 画面には平文パスワードを一切返さない(登録済みかどうかとユーザー名だけを表示する)。 */
    @PostMapping("/projects/{id}/credentials")
    public String saveTestCredential(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable Long id,
            @RequestParam String username,
            @RequestParam String password) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        if (username.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ユーザー名とパスワードの両方が必要です");
        }
        CredentialEncryptionService.Encrypted encrypted = credentialEncryptionService.encrypt(password);
        TestCredential credential = testCredentialRepository
                .findByProjectIdAndOrganizationId(project.getId(), user.getOrganizationId())
                .orElseGet(TestCredential::new);
        credential.setOrganizationId(user.getOrganizationId());
        credential.setProjectId(project.getId());
        credential.setUsername(username);
        credential.setEncryptedPassword(encrypted.ciphertextBase64());
        credential.setIv(encrypted.ivBase64());
        credential.setCreatedBy(user.getUserId());
        credential.setUpdatedAt(java.time.Instant.now());
        testCredentialRepository.save(credential);

        // 監査ログにも平文パスワードは残さない(ユーザー名のみ)。
        auditService.record(user.getOrganizationId(), user.getUserId(), "test_credential_saved",
                "projectId=" + id + " username=" + username);
        return "redirect:/projects/" + id;
    }

    /** v0.7 P5: 登録済みのテスト用アカウントを削除する。 */
    @PostMapping("/projects/{id}/credentials/delete")
    public String deleteTestCredential(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) {
        projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        testCredentialRepository.deleteByProjectIdAndOrganizationId(id, user.getOrganizationId());
        auditService.record(user.getOrganizationId(), user.getUserId(), "test_credential_deleted", "projectId=" + id);
        return "redirect:/projects/" + id;
    }

    /** v0.7 P7(縮小): プロジェクト単位の削除。Java層のメタデータ(Plan/Run記録・仕様書記録・
     * 不具合の状態・テスト用アカウント)だけを削除する。ワーカー側のPlan/Run本体(runs/配下)は
     * 削除しない(復旧・監査のため残す。物理削除が必要な場合は、ワーカー側のディスク上で
     * 個別に行う運用とする。docs/RESUME.mdのバックアップ・復元の手順を参照)。使用量の記録
     * (UsageEvent)は、内部の請求根拠として残す(削除しない)。 */
    @Transactional
    @PostMapping("/projects/{id}/delete")
    public String deleteProject(
            @AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id,
            @RequestParam(defaultValue = "") String confirmName) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        if (!project.getName().equals(confirmName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "確認のため、プロジェクト名を正確に入力してください。");
        }
        Long organizationId = user.getOrganizationId();
        testCredentialRepository.deleteByProjectIdAndOrganizationId(id, organizationId);
        findingStatusRecordRepository.deleteAll(findingStatusRecordRepository.findByOrganizationIdAndProjectId(organizationId, id));
        runRecordRepository.deleteAll(runRecordRepository.findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(id, organizationId));
        planRecordRepository.deleteAll(planRecordRepository.findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(id, organizationId));
        specRecordRepository.deleteAll(specRecordRepository.findByProjectIdAndOrganizationId(id, organizationId));
        projectRepository.delete(project);
        auditService.record(organizationId, user.getUserId(), "project_deleted", "projectId=" + id + " name=" + project.getName());
        return "redirect:/projects";
    }

    /** タブ「実行履歴」用(v0.6 UIチェックポイント)。RunRecordで自プロジェクトの実行に絞り込み、
     * ワーカーから概要(状態・指摘件数)を取得する。ワーカーが応答しない実行は一覧から静かに除く
     * (画面全体は落とさない)。 */
    private List<Map<String, Object>> executionHistoryFor(Project project, Long organizationId) {
        List<Map<String, Object>> executions = new ArrayList<>();
        for (RunRecord record : runRecordRepository.findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(project.getId(), organizationId)) {
            try {
                Map<String, Object> run = ai.hack2026.web.util.RunDisplay.resolve(workerClient, record);
                run.put("startedAtDisplay", DisplayFormat.jst(run.get("startedAt")));
                run.put("costDisplay", DisplayFormat.creditsOf(run, creditService.getUsdToJpyRate(), creditService.getMarkupMultiplier()));
                run.put("durationDisplay", DisplayFormat.durationOf(run));
                executions.add(run);
            } catch (RuntimeException e) {
                log.info("プロジェクト詳細用のRun取得に失敗した(runId={}): {}", record.getRunId(), e.toString());
            }
        }
        executions.sort((a, b) -> DisplayFormat.instantOf(b.get("startedAt"))
                .compareTo(DisplayFormat.instantOf(a.get("startedAt"))));
        return executions;
    }

    /** L-1: ドメイン所有確認を開始する(トークンを発行)。対象ホストは、プロジェクトのURLから決まる。 */
    @PostMapping("/projects/{id}/domain/start")
    public String startDomainVerification(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        String hostname = hostnameOf(project.getTargetUrl());
        if (hostname == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "対象URLを解釈できません");
        }
        try {
            domainVerificationService.startVerification(user.getOrganizationId(), hostname, schemeOf(project.getTargetUrl()));
        } catch (DomainVerificationService.DeniedHostnameException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "このドメインは診断対象にできません: " + e.getMessage());
        }
        auditService.record(user.getOrganizationId(), user.getUserId(), "domain_verification_started",
                "hostname=" + hostname);
        return "redirect:/projects/" + id;
    }

    /** L-1: 対象ホストへ実際にHTTPで取得しにいき、トークンの一致を確認する。 */
    @PostMapping("/projects/{id}/domain/check")
    public String checkDomainVerification(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        String hostname = hostnameOf(project.getTargetUrl());
        Domain domain = hostname == null ? null
                : domainRepository.findByOrganizationIdAndHostname(user.getOrganizationId(), hostname).orElse(null);
        if (domain == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先に確認を開始してください");
        }
        domain = domainVerificationService.checkVerification(domain);
        auditService.record(user.getOrganizationId(), user.getUserId(), "domain_verification_checked",
                "hostname=" + hostname + " status=" + domain.getStatus());
        return "redirect:/projects/" + id;
    }

    /** L-2: 所有者が「テスト環境である」と宣言する(能動テスト(P-SEC)の許可条件の一部)。 */
    @PostMapping("/projects/{id}/domain/declare-test-environment")
    public String declareTestEnvironment(
            @AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean confirmed) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        String hostname = hostnameOf(project.getTargetUrl());
        Domain domain = hostname == null ? null
                : domainRepository.findByOrganizationIdAndHostname(user.getOrganizationId(), hostname).orElse(null);
        if (domain == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先に所有確認を完了してください");
        }
        if (!confirmed) {
            return "redirect:/projects/" + id;
        }
        domain.setTestEnvironment(true);
        domainRepository.save(domain);
        auditService.record(user.getOrganizationId(), user.getUserId(), "domain_test_environment_declared",
                "hostname=" + hostname);
        return "redirect:/projects/" + id;
    }

    /** v0.7(第1節、モードA): ローカル・プライベートな対象は、/.well-known方式で所有を証明できない
     * ため、「開発中・リリース前のテスト環境であり、本番ではない」ことの宣言だけで診断を開始できる
     * ようにする(所有確認の代わり)。対象が実際にローカル・プライベートであることを確認してから
     * 宣言を受け付ける(公開ホストに対してこの宣言だけで診断を始める抜け道を作らないため)。 */
    @PostMapping("/projects/{id}/domain/declare-local")
    public String declareLocalEnvironment(
            @AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean confirmed) {
        Project project = projectRepository.findByIdAndOrganizationId(id, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        if (project.isPublicReadonly()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "公開ページの点検(読み取り専用)のプロジェクトには、この宣言は使えません。");
        }
        String hostname = hostnameOf(project.getTargetUrl());
        if (hostname == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "対象URLを解釈できません");
        }
        DomainSafetyChecker.HostClass hostClass = domainSafetyChecker.classify(hostname);
        if (hostClass != DomainSafetyChecker.HostClass.LOCAL_OR_PRIVATE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "この対象(" + hostname + ")はローカル・プライベートではないため、この宣言は使えません。"
                            + "「所有確認を開始する」から、公開ステージングとしての手続きを行ってください。");
        }
        if (!confirmed) {
            return "redirect:/projects/" + id;
        }
        Domain domain = domainRepository.findByOrganizationIdAndHostname(user.getOrganizationId(), hostname)
                .orElseGet(() -> {
                    Domain d = new Domain();
                    d.setOrganizationId(user.getOrganizationId());
                    d.setHostname(hostname);
                    d.setScheme(schemeOf(project.getTargetUrl()));
                    d.setVerificationToken(java.util.UUID.randomUUID().toString());
                    return d;
                });
        domain.setStatus(Domain.STATUS_LOCAL_DECLARED);
        domain.setVerifiedAt(java.time.Instant.now());
        domainRepository.save(domain);
        auditService.record(user.getOrganizationId(), user.getUserId(), "local_environment_declared",
                "hostname=" + hostname);
        return "redirect:/projects/" + id;
    }

    private static String hostnameOf(String targetUrl) {
        try {
            URI uri = new URI(targetUrl);
            if (uri.getHost() == null) {
                return null;
            }
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String schemeOf(String targetUrl) {
        try {
            String scheme = new URI(targetUrl).getScheme();
            return scheme == null ? "http" : scheme;
        } catch (URISyntaxException e) {
            return "http";
        }
    }
}
