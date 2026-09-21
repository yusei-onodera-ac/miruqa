package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.consent.Consent;
import ai.hack2026.web.consent.ConsentService;
import ai.hack2026.web.credential.CredentialEncryptionService;
import ai.hack2026.web.credential.TestCredential;
import ai.hack2026.web.credential.TestCredentialRepository;
import ai.hack2026.web.domain.Domain;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.domain.DomainSafetyChecker;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.SpecRecord;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.worker.WorkerClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 新規点検ウィザードの1〜2画面目(FR-36・v0.5): URL入力と仕様書のドラッグ&ドロップを1画面にまとめ、
 * ワーカーに仕様書を渡してからプランを作らせる(POST /api/plans は url と specIds を一緒に取るため)。
 * 許可ドメイン外は、ワーカーの domain_not_allowed を GlobalExceptionHandler が拾って表示する。
 * ペルソナ・ゴールの入力欄は無い(v0.5で完全に廃止)。
 * v0.6 P2(L-3): 実行ごとに、対象ドメイン名の再入力と「テストする権限がある」旨の同意を必須にする。
 * v0.6 P2(AC-L1): 所有確認が済んでいないドメインでは、診断そのものを開始できない
 * (プロジェクト画面での所有確認が前提。「プロジェクト」から先にドメインを登録・確認すること)。
 */
@Controller
public class JobController {

    private final WorkerClient workerClient;
    private final ConsentService consentService;
    private final DomainRepository domainRepository;
    private final ProjectRepository projectRepository;
    private final PlanRecordRepository planRecordRepository;
    private final SpecRecordRepository specRecordRepository;
    private final DomainSafetyChecker domainSafetyChecker;
    private final TestCredentialRepository testCredentialRepository;
    private final CredentialEncryptionService credentialEncryptionService;

    public JobController(
            WorkerClient workerClient,
            ConsentService consentService,
            DomainRepository domainRepository,
            ProjectRepository projectRepository,
            PlanRecordRepository planRecordRepository,
            SpecRecordRepository specRecordRepository,
            DomainSafetyChecker domainSafetyChecker,
            TestCredentialRepository testCredentialRepository,
            CredentialEncryptionService credentialEncryptionService) {
        this.workerClient = workerClient;
        this.consentService = consentService;
        this.domainRepository = domainRepository;
        this.projectRepository = projectRepository;
        this.planRecordRepository = planRecordRepository;
        this.specRecordRepository = specRecordRepository;
        this.domainSafetyChecker = domainSafetyChecker;
        this.testCredentialRepository = testCredentialRepository;
        this.credentialEncryptionService = credentialEncryptionService;
    }

    /** v0.6 P3(前半): プロジェクトが無いと診断を開始できない(AC-L1は所有確認済みのプロジェクトを
     * 前提にしているため)。projectIdが無い場合は、プロジェクト一覧へ案内する。 */
    @GetMapping("/")
    public String form(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(value = "projectId", required = false) Long projectId,
            Model model) {
        if (projectId == null) {
            return "redirect:/projects";
        }
        Project project = projectRepository.findByIdAndOrganizationId(projectId, user.getOrganizationId())
                .orElse(null);
        if (project == null) {
            return "redirect:/projects";
        }
        model.addAttribute("projectId", project.getId());
        model.addAttribute("defaultUrl", project.getTargetUrl());
        model.addAttribute("projectKind", project.getKind());
        return "index";
    }

    @PostMapping("/jobs")
    public String create(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam Long projectId,
            @RequestParam String url,
            @RequestParam(value = "domainConfirmation", defaultValue = "") String domainConfirmation,
            @RequestParam(value = "consentGiven", defaultValue = "false") boolean consentGiven,
            @RequestParam(value = "specFiles", required = false) List<MultipartFile> specFiles,
            HttpServletRequest request) {
        Project project = projectRepository.findByIdAndOrganizationId(projectId, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        String hostname = hostnameOf(url);
        if (!consentGiven) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "このドメインをテストする権限がある旨の同意が必要です。");
        }
        if (hostname == null || !hostname.equalsIgnoreCase(domainConfirmation.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "入力されたドメイン名が、対象URLのホスト名と一致しません。誤操作の防止のため、"
                            + "正確なホスト名を入力してください。");
        }

        // v0.7(第1・1a・1b節): 対象を分類し、プロジェクトの種類(kind)と組み合わせてモードを決める。
        // メタデータのアドレス等は、種類に関わらず常に遮断する。
        DomainSafetyChecker.HostClass hostClass = domainSafetyChecker.classify(hostname);
        if (hostClass == DomainSafetyChecker.HostClass.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "この対象(" + hostname + ")には診断を実行できません(内部・特殊用途のアドレスのため常に遮断されます)。");
        }
        Optional<Domain> existingDomain = domainRepository.findByOrganizationIdAndHostname(user.getOrganizationId(), hostname);
        String mode;
        if (project.isPublicReadonly()) {
            if (hostClass == DomainSafetyChecker.HostClass.LOCAL_OR_PRIVATE) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "公開ページの点検(読み取り専用)では、ローカル・プライベートな対象は指定できません。"
                                + "自分の開発環境を点検する場合は「開発環境の点検」のプロジェクトを作成してください。");
            }
            mode = "readonly";
            // 読み取り専用モードでは所有確認(/.well-known)は求めない(第三者の公開ページが対象のため)。
            // 開始時の同意(規約・robots.txtの確認)は、consentGiven(このフォームのチェックボックス。
            // index.htmlが種類ごとに文言を出し分ける)で担保する。
        } else if (hostClass == DomainSafetyChecker.HostClass.PUBLIC) {
            // モードB: 所有確認済みの公開ステージング(AC-L1、既存のまま)。
            boolean verified = existingDomain.map(d -> Domain.STATUS_VERIFIED.equals(d.getStatus())).orElse(false);
            if (!verified) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "このドメイン(" + hostname + ")の所有確認がまだ完了していません。"
                                + "「プロジェクト」画面からこのURLのプロジェクトを作成し、所有確認を済ませてください。");
            }
            mode = "staging";
        } else {
            // モードA: ローカル・プライベートな開発環境。所有確認の代わりに、テスト環境の宣言(同意)を必須にする。
            boolean localDeclared = existingDomain.map(d -> Domain.STATUS_LOCAL_DECLARED.equals(d.getStatus())).orElse(false);
            if (!localDeclared) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "このローカル環境(" + hostname + ")は、まだ「開発中・リリース前のテスト環境である」ことの"
                                + "宣言が済んでいません。「プロジェクト」画面から宣言してください。");
            }
            mode = "local";
        }
        boolean verified = existingDomain.map(Domain::isDiagnosisAllowed).orElse(false);

        List<String> specIds = new ArrayList<>();
        if (specFiles != null) {
            for (MultipartFile file : specFiles) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                Map<String, Object> spec = workerClient.uploadSpec(file);
                String specId = String.valueOf(spec.get("specId"));
                specIds.add(specId);

                // v0.6 P3(前半・開発者からの指摘): 仕様書も組織に紐付ける(IDORの是正)。
                SpecRecord specRecord = new SpecRecord();
                specRecord.setSpecId(specId);
                specRecord.setProjectId(project.getId());
                specRecord.setOrganizationId(user.getOrganizationId());
                specRecord.setCreatedBy(user.getUserId());
                specRecordRepository.save(specRecord);
            }
        }
        Consent consent = consentService.recordPerExecutionConsent(
                user.getUserId(), user.getOrganizationId(), hostname, request.getRemoteAddr());

        // v0.6 P2(L-2)/v0.7(第1b節): 所有確認・テスト環境宣言・モードをauthorizationとして
        // ワーカーに渡す。modeは利用者の入力では変えられず、Web層がproject.kindと対象の分類から
        // 決める(readonly/local/staging)。ワーカーはmode=readonlyのとき、第1a節の制限を、
        // 既存の3条件の判定より前に無条件で適用する。
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("host", hostname);
        authorization.put("verified", verified);
        authorization.put("mode", mode);
        authorization.put("testEnvDeclared", existingDomain.map(Domain::isTestEnvironment).orElse(false));
        authorization.put("consentId", consent.getId());
        authorization.put("grantedAt", Instant.now().toString());

        // v0.7 P5: ログインが必要な画面の点検。モードC(読み取り専用)では行わない(第1a節、
        // 認証を回避しない・突破しない方針。ここでのログインは第三者の画面ではなく、利用者自身が
        // 登録したテスト用アカウントでの、承認済み対象への通常のログインであり突破ではない)。
        // パスワードは復号した直後にワーカーへ渡すだけで、このメソッドのローカル変数以外には
        // 残さない(ログ・監査ログにも出さない)。testAccountは意図的にauthorizationとは別の
        // 最上位フィールドで渡す(authorizationはPlanに保存されるため、ここに混ぜるとパスワードが
        // plan.jsonへ永続化されてしまう。実際に混入させて発見した不具合。WorkerClient参照)。
        Map<String, Object> testAccount = null;
        if (!"readonly".equals(mode)) {
            TestCredential credential = testCredentialRepository
                    .findByProjectIdAndOrganizationId(project.getId(), user.getOrganizationId()).orElse(null);
            if (credential != null) {
                String plainPassword = credentialEncryptionService.decrypt(
                        credential.getEncryptedPassword(), credential.getIv());
                testAccount = new LinkedHashMap<>();
                testAccount.put("username", credential.getUsername());
                testAccount.put("password", plainPassword);
            }
        }

        Map<String, Object> plan = workerClient.createPlan(url, specIds, authorization, testAccount);
        String planId = String.valueOf(plan.get("planId"));

        PlanRecord record = new PlanRecord();
        record.setPlanId(planId);
        record.setProjectId(project.getId());
        record.setOrganizationId(user.getOrganizationId());
        record.setCreatedBy(user.getUserId());
        planRecordRepository.save(record);

        return "redirect:/plans/" + planId;
    }

    /** ProjectController.hostnameOf()と同じ規約(host[:port])に揃える。ここが食い違うと、
     * ドメイン所有確認(プロジェクト画面でhost:port単位に保存される)と、この画面のAC-L1判定が
     * 一致しなくなる(実測で発見した不具合。ポート付きURL(デモサイトの8765等)で常に
     * 「所有確認未完了」と判定されてしまっていた)。 */
    private static String hostnameOf(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                return null;
            }
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }
}
