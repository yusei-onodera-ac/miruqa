package ai.hack2026.web.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** v0.6 P3: ワーカーのRunを、プロジェクト・組織に紐付けるメタデータであると同時に、
 * DBのジョブキューのエントリでもある(runId発行時点ではまだワーカーへ実行を依頼しない場合がある。
 * {@code status}/{@code workerRunId}参照)。Run本体(実行状況・結果)は引き続きワーカー側のJSONが正。 */
@Entity
@Table(name = "run_records")
public class RunRecord {

    /** キューに入っている(まだワーカーへ依頼していない)。 */
    public static final String STATUS_QUEUED = "queued";
    /** ワーカーへ依頼済み。実際の状態(running/waiting_approval等)はワーカー側を都度確認する。 */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_INTERRUPTED = "interrupted";
    /** このJava層が実行ごとのタイムアウトを検知して打ち切った場合。ワーカー側にはこの概念が無い。 */
    public static final String STATUS_TIMEOUT = "timeout";
    /** v0.8第6章: 中断(残高不足・LLM障害・ワーカー再起動・タイムアウト・利用者の停止のいずれか)。
     * 終端ではあるが、{@link #isTerminal()}のほかに{@link #isPaused()}でも判定でき、
     * 「再開」(完了済みの項目を再実行しない再開)の対象になる。 */
    public static final String STATUS_PAUSED = "paused";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 承認直後から使う、安定した識別子(URL・履歴で使う。Java層が発行する)。 */
    @Column(name = "run_id", nullable = false, unique = true, length = 64)
    private String runId;

    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** queued/running/completed/failed/cancelled/interrupted/timeout。ジョブキューの状態。 */
    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_QUEUED;

    /** ワーカーへ実際に依頼した後の、ワーカー側の本当のrunId(依頼前はnull)。 */
    @Column(name = "worker_run_id", length = 64)
    private String workerRunId;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_reason", length = 2000)
    private String errorReason;

    /** v0.8第6章: 中断の理由(user_stop/credit_exhausted/llm_failure/worker_restart/timeout等)。
     * status=pausedのときだけ意味を持つ。画面の文言選びに使う(USDは出さない)。 */
    @Column(name = "pause_reason", length = 64)
    private String pauseReason;

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWorkerRunId() { return workerRunId; }
    public void setWorkerRunId(String workerRunId) { this.workerRunId = workerRunId; }
    public Instant getQueuedAt() { return queuedAt; }
    public void setQueuedAt(Instant queuedAt) { this.queuedAt = queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }

    public boolean isDispatched() { return workerRunId != null; }

    public boolean isActive() {
        return STATUS_QUEUED.equals(status) || STATUS_RUNNING.equals(status);
    }

    public boolean isPaused() {
        return STATUS_PAUSED.equals(status);
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status) || STATUS_INTERRUPTED.equals(status)
                || STATUS_TIMEOUT.equals(status) || STATUS_PAUSED.equals(status);
    }
}
