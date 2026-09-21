package ai.hack2026.web;

import ai.hack2026.web.auth.Membership;
import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import ai.hack2026.web.auth.UserRepository;
import ai.hack2026.web.domain.Domain;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * v0.6 UIチェックポイント用: `runs/samples/`(コミット済みのリプレイ用サンプル。AC-8)を、
 * デモ用の組織・プロジェクトに紐付け、実データで画面のスクリーンショットを撮れるようにする。
 *
 * `app.seed-demo-data=true`(環境変数 `SEED_DEMO_DATA=true`)のときだけ動く。既定は何もしない
 * (本番の起動経路には一切影響しない)。冪等: 何度実行しても、既存のデモ組織・登録済みのRunId は
 * 重複登録しない。
 *
 * 使い方: `SEED_DEMO_DATA=true mvn spring-boot:run`(1回起動して止めてよい。以後は通常起動で、
 * 登録済みのデモデータがそのまま残る)。
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_EMAIL = "demo-screenshots@example.com";
    private static final String DEMO_PASSWORD = "DemoScreenshots9";
    private static final String DEMO_HOSTNAME = "127.0.0.1:8765";
    // runs/samples/内で、実際にPlan本体(下見結果・テスト項目書)まで揃っている唯一のもの(v0.5形式)。
    // 他の旧サンプルはRunRecordだけ登録し、履歴一覧の行数を稼ぐ(Plan本体が無いため詳細画面は簡略表示)。
    private static final String FULL_SAMPLE_RUN_ID = "run-0b84031e60";
    private static final String FULL_SAMPLE_PLAN_ID = "p-6838881f";

    private final boolean enabled;
    private final SignupService signupService;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final ProjectRepository projectRepository;
    private final DomainRepository domainRepository;
    private final PlanRecordRepository planRecordRepository;
    private final RunRecordRepository runRecordRepository;

    public DemoDataSeeder(
            @Value("${app.seed-demo-data:false}") boolean enabled,
            SignupService signupService,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            ProjectRepository projectRepository,
            DomainRepository domainRepository,
            PlanRecordRepository planRecordRepository,
            RunRecordRepository runRecordRepository) {
        this.enabled = enabled;
        this.signupService = signupService;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.projectRepository = projectRepository;
        this.domainRepository = domainRepository;
        this.planRecordRepository = planRecordRepository;
        this.runRecordRepository = runRecordRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        log.info("[demo-seed] SEED_DEMO_DATA=true のため、runs/samples/ をデモ組織へ登録します。");

        User user = userRepository.findByEmail(DEMO_EMAIL).orElseGet(() ->
                signupService.signUp(DEMO_EMAIL, DEMO_PASSWORD, "デモ担当", "デモ組織"));
        Membership membership = membershipRepository.findByUserId(user.getId()).get(0);
        Long organizationId = membership.getOrganization().getId();

        Project project = projectRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .findFirst()
                .orElseGet(() -> {
                    Project p = new Project();
                    p.setOrganizationId(organizationId);
                    p.setName("デモECサイト");
                    p.setTargetUrl("http://" + DEMO_HOSTNAME + "/");
                    p.setCreatedBy(user.getId());
                    return projectRepository.save(p);
                });

        // スクリーンショット用の種データのため、実際の宣言操作は行わず、宣言済みとして直接登録する
        // (本番の宣言フロー(ProjectController.declareLocalEnvironment)とは別の、シード専用の経路)。
        // v0.7: 127.0.0.1はループバック(ローカル)に分類されるため、所有確認(verified)ではなく
        // ローカル宣言(local_declared)にする(モードAのゲート)。
        domainRepository.findByOrganizationIdAndHostname(organizationId, DEMO_HOSTNAME).orElseGet(() -> {
            Domain domain = new Domain();
            domain.setOrganizationId(organizationId);
            domain.setHostname(DEMO_HOSTNAME);
            domain.setVerificationToken("demo-seed-token");
            domain.setStatus(Domain.STATUS_LOCAL_DECLARED);
            domain.setVerifiedAt(Instant.now());
            domain.setTestEnvironment(true);
            return domainRepository.save(domain);
        });

        copySampleRunFiles();

        if (planRecordRepository.findByPlanIdAndOrganizationId(FULL_SAMPLE_PLAN_ID, organizationId).isEmpty()) {
            PlanRecord planRecord = new PlanRecord();
            planRecord.setPlanId(FULL_SAMPLE_PLAN_ID);
            planRecord.setProjectId(project.getId());
            planRecord.setOrganizationId(organizationId);
            planRecord.setCreatedBy(user.getId());
            planRecordRepository.save(planRecord);
        }

        for (String runId : new String[]{
                "run-0b84031e60", "run-5b303eeedc", "run-8fff06d407", "run-9409d6a593", "run-a04bf5ce05"}) {
            if (runRecordRepository.findByRunIdAndOrganizationId(runId, organizationId).isPresent()) {
                continue;
            }
            RunRecord runRecord = new RunRecord();
            runRecord.setRunId(runId);
            runRecord.setPlanId(runId.equals(FULL_SAMPLE_RUN_ID) ? FULL_SAMPLE_PLAN_ID : "(旧形式・Planなし)");
            runRecord.setProjectId(project.getId());
            runRecord.setOrganizationId(organizationId);
            // v0.6 P3: ジョブキュー導入後、runIdはJava側の安定した識別子/workerRunIdはワーカー側の
            // 実際のrunIdという区別ができた。サンプルはすでに完了済みの実行のため、両方とも
            // 同じ値にし、statusも実データの実際の状態(completed)に合わせる(既定のqueuedのままだと、
            // 完了済みのはずのサンプルがキュー待機中として表示されてしまう)。
            runRecord.setWorkerRunId(runId);
            runRecord.setStatus(RunRecord.STATUS_COMPLETED);
            runRecordRepository.save(runRecord);
        }

        log.info("[demo-seed] 完了: organizationId={}, projectId={}, ログイン: {} / {}",
                organizationId, project.getId(), DEMO_EMAIL, DEMO_PASSWORD);
    }

    /** runs/samples/<runId>/* を、ワーカーが実際に読み書きするrunsディレクトリへコピーする
     * (ワーカーのGET /api/runs/{id}等が参照できるようにするため。既に存在すれば上書きしない)。 */
    private void copySampleRunFiles() {
        Path samplesDir = Paths.get("..", "runs", "samples");
        Path runsDir = Paths.get("..", "runs");
        if (!Files.isDirectory(samplesDir)) {
            log.warn("[demo-seed] runs/samples/ が見つかりません(パス: {})。ファイルのコピーをスキップします。", samplesDir.toAbsolutePath());
            return;
        }
        try (Stream<Path> sampleRunDirs = Files.list(samplesDir)) {
            sampleRunDirs.filter(Files::isDirectory).forEach(sampleRunDir -> {
                Path dest = runsDir.resolve(sampleRunDir.getFileName());
                if (Files.exists(dest)) {
                    return; // 既にワーカー側にある(過去に実行済み、または前回のシードで作成済み)
                }
                try {
                    Files.createDirectories(dest);
                    try (Stream<Path> files = Files.list(sampleRunDir)) {
                        for (Path file : (Iterable<Path>) files::iterator) {
                            Files.copy(file, dest.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    log.info("[demo-seed] コピー: {} -> {}", sampleRunDir, dest);
                } catch (IOException e) {
                    log.warn("[demo-seed] {} のコピーに失敗しました: {}", sampleRunDir, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("[demo-seed] runs/samples/ の一覧取得に失敗しました: {}", e.toString());
        }
    }
}
