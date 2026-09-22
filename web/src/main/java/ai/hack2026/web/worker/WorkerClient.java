package ai.hack2026.web.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ワーカー(Python、agent/server.py)を呼ぶクライアント。docs/contracts.md の「ワーカーAPI」に対応する。
 *
 * <p>Java層はLLMを直接呼ばず、秘密情報も持たない(絶対条件・AC-22)。ここが唯一の外部境界。
 * ワーカーが止まっている・遅い・エラーを返すときも、例外にラップして呼び出し元(コントローラ)が
 * 画面に表示できるようにする(落とさない。FR-40・AC-21)。</p>
 *
 * <p>実装メモ(2026-09-20): 当初はSpring の {@code RestClient} を使っていたが、Spring Boot 4.0の
 * JSONエンジン(既定でJackson 3系 {@code tools.jackson.*} に切り替わっている)と、
 * {@code RestClient.Builder} の自動設定がこの依存構成では噛み合わず(自動設定Beanが登録されない/
 * リクエストボディが空になる、の2つを実際に踏んだ)、ハッカソンの時間内で確実に動かすため、
 * JDK標準の {@link HttpClient} と Jackson 2(明示依存)で直接実装する方式に切り替えた。
 * Spring側のHTTPスタックの自動設定に依存しないため、今後のバージョン変化にも影響されにくい。</p>
 */
@Component
public class WorkerClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration readTimeout;
    private final Duration specUploadTimeout;
    /** v0.6 P1: ワーカーとの共有秘密(agent/config.pyのWORKER_SHARED_SECRETと同じ値)。 */
    private final String sharedSecret;

    public WorkerClient(
            @Value("${worker.base-url}") String baseUrl,
            @Value("${worker.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${worker.read-timeout-ms}") long readTimeoutMs,
            @Value("${worker.spec-upload-timeout-ms}") long specUploadTimeoutMs,
            @Value("${worker.shared-secret:}") String sharedSecret) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.specUploadTimeout = Duration.ofMillis(specUploadTimeoutMs);
        this.sharedSecret = sharedSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};

    public Map<String, Object> health() {
        return get("/api/health", MAP_TYPE);
    }

    /** 仕様書ファイルをワーカーへアップロードし、抽出されたSpecを返す(docs/contracts.md)。 */
    public Map<String, Object> uploadSpec(MultipartFile file) {
        String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = multipartBody(boundary, file);
        } catch (IOException e) {
            throw new WorkerUnavailableException("仕様書ファイルの読み込みに失敗しました: " + e.getMessage(), e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/specs"))
                .timeout(specUploadTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        addAuthHeader(builder);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = send(request);
        return parseOrThrow(response, MAP_TYPE);
    }

    private byte[] multipartBody(String boundary, MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "spec" : file.getOriginalFilename();
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        var out = new java.io.ByteArrayOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(file.getBytes());
        out.write(footer.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public Map<String, Object> createPlan(String url, List<String> specIds) {
        return createPlan(url, specIds, null);
    }

    /** v0.6 P2(L-2): authorizationは{host, verified, testEnvDeclared, consentId, grantedAt}
     * (nullable。無ければ能動テストが許可されない安全側のデフォルトになる、ワーカー側参照)。 */
    public Map<String, Object> createPlan(String url, List<String> specIds, Map<String, Object> authorization) {
        return createPlan(url, specIds, authorization, null);
    }

    /** v0.7 P5: testAccount({"username","password"})は、意図的にauthorizationとは別の
     * 最上位フィールドにする。authorizationはPlanに保存されるため、ここに混ぜるとパスワードが
     * plan.jsonへ永続化されてしまう(実際に混入させて発見した不具合。修正済み)。testAccountは
     * ワーカー側でも保存されず、下見の巡回開始前のログイン試行にだけ使われる。 */
    public Map<String, Object> createPlan(String url, List<String> specIds, Map<String, Object> authorization, Map<String, Object> testAccount) {
        return createPlan(url, specIds, authorization, testAccount, null, null);
    }

    /** v0.8第4章: carryOverPlanId/carryOverRunIdを指定すると、ワーカーは下見・項目書生成を
     * やり直さず、前回のPlanのテスト項目をそのまま持ち越す(前回データが無ければ通常どおり
     * 生成する)。組織スコープの確認(carryOverPlanIdが自組織のものであること)は、
     * 呼び出し元(JobController)が行う。 */
    public Map<String, Object> createPlan(
            String url, List<String> specIds, Map<String, Object> authorization, Map<String, Object> testAccount,
            String carryOverPlanId, String carryOverRunId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("url", url);
        body.put("specIds", specIds);
        if (authorization != null) {
            body.put("authorization", authorization);
        }
        if (testAccount != null) {
            body.put("testAccount", testAccount);
        }
        if (carryOverPlanId != null) {
            body.put("carryOverPlanId", carryOverPlanId);
            body.put("carryOverRunId", carryOverRunId);
        }
        return post("/api/plans", body, MAP_TYPE);
    }

    public Map<String, Object> getPlan(String planId) {
        return get("/api/plans/" + planId, MAP_TYPE);
    }

    public Map<String, Object> getSpec(String specId) {
        return get("/api/specs/" + specId, MAP_TYPE);
    }

    public Map<String, Object> approvePlan(String planId, List<String> testCaseIds) {
        return post("/api/plans/" + planId + "/approve", Map.of("testCaseIds", testCaseIds), MAP_TYPE);
    }

    /** コスト上限等で途中打ち切りになった項目書生成の続きを行う(判断23)。 */
    public Map<String, Object> resumePlan(String planId) {
        return post("/api/plans/" + planId + "/resume", Map.of(), MAP_TYPE);
    }

    /** v0.7 3.6a: 自然言語での項目追加の依頼(追加案を作るだけで、まだ項目書には採用しない)。 */
    public Map<String, Object> proposeTestCases(String planId, String text) {
        return post("/api/plans/" + planId + "/cases/propose", Map.of("text", text), MAP_TYPE);
    }

    /** v0.7 3.6a: 追加案のうち、利用者がONにしたものだけを項目書へ採用する。戻り値は更新後のPlan全体。 */
    public Map<String, Object> addTestCases(String planId, List<String> acceptedIds) {
        return post("/api/plans/" + planId + "/cases", Map.of("acceptedIds", acceptedIds), MAP_TYPE);
    }

    public Map<String, Object> startRun(String planId) {
        return post("/api/runs", Map.of("planId", planId), MAP_TYPE);
    }

    /** v0.7 P5: testAccount({"username","password"})は、ワーカー側に永続化されない
     * (run.jsonには保存せず、実行開始直後のログイン試行にだけ使う。呼び出し元がRunを開始する
     * たびに復号して渡す必要がある)。 */
    public Map<String, Object> startRun(String planId, Map<String, Object> testAccount) {
        if (testAccount == null) {
            return startRun(planId);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("planId", planId);
        body.put("testAccount", testAccount);
        return post("/api/runs", body, MAP_TYPE);
    }

    /** v0.6 P2(緊急停止)→v0.8第6章: 実行中のRunに停止を要求する(協調的。即座には止まらない。
     * "paused"=再開可能として終わる)。 */
    public Map<String, Object> cancelRun(String runId) {
        return post("/api/runs/" + runId + "/cancel", Map.of(), MAP_TYPE);
    }

    /** v0.8第6章: 理由つきの停止要求(残高不足等、Java層が能動的に止める場合)。 */
    public Map<String, Object> cancelRun(String runId, String reason) {
        return post("/api/runs/" + runId + "/cancel", Map.of("reason", reason), MAP_TYPE);
    }

    /** v0.8第6章: 中断(paused)した実行を、完了済みの項目を再実行せず再開する。 */
    public Map<String, Object> resumeRun(String runId, Map<String, Object> testAccount) {
        Map<String, Object> body = testAccount == null ? Map.of() : Map.of("testAccount", testAccount);
        return post("/api/runs/" + runId + "/resume", body, MAP_TYPE);
    }

    public List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> body = get("/api/runs", LIST_TYPE);
        return body == null ? List.of() : body;
    }

    public Map<String, Object> getRun(String runId) {
        return get("/api/runs/" + runId, MAP_TYPE);
    }

    /**
     * このRunの実測コスト内訳(目的別・モデル別。runs/ledger.jsonl 由来)。Runの実行分だけでなく、
     * その元になったPlanの下見・項目書生成(spec-extract/testcase-gen)のコストも合算されている
     * (2026-09-21: これらのコストが記録から漏れていたバグの修正に伴い追加)。
     * ワーカーが未対応(古いバージョン)・停止中でも例外を投げず、呼び出し元でnullとして扱えるよう
     * 呼び出し元(RunController)側でハンドリングする。
     */
    public Map<String, Object> getRunCost(String runId) {
        return get("/api/runs/" + runId + "/cost", MAP_TYPE);
    }

    public String getReportHtml(String runId) {
        return getRaw("/api/runs/" + runId + "/report");
    }

    public String getExportCsv(String runId) {
        return getRaw("/api/runs/" + runId + "/export?format=csv");
    }

    public List<Map<String, Object>> listPendingApprovals() {
        List<Map<String, Object>> body = get("/api/approvals?status=pending", LIST_TYPE);
        return body == null ? List.of() : body;
    }

    public Map<String, Object> decideApproval(String approvalId, String decision) {
        return post("/api/approvals/" + approvalId, Map.of("decision", decision), MAP_TYPE);
    }

    private <T> T get(String path, TypeReference<T> type) {
        HttpRequest request = requestBuilder(path).GET().build();
        HttpResponse<String> response = send(request);
        return parseOrThrow(response, type);
    }

    private String getRaw(String path) {
        HttpRequest request = requestBuilder(path).GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 400) {
            throw errorFor(response);
        }
        return response.body();
    }

    private <T> T post(String path, Object body, TypeReference<T> type) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new WorkerUnavailableException("リクエストの作成に失敗しました: " + e.getMessage(), e);
        }
        HttpRequest request = requestBuilder(path)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        return parseOrThrow(response, type);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(readTimeout);
        addAuthHeader(builder);
        return builder;
    }

    /** v0.6 P1: ワーカーとの共有秘密ヘッダー。未設定(空文字列)なら付与しない(移行期間の後方互換)。 */
    private void addAuthHeader(HttpRequest.Builder builder) {
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            builder.header("X-Worker-Auth", sharedSecret);
        }
    }

    /** e.getMessage()がnullのことがある(例: ConnectException)ため、その場合はクラス名で代用する。 */
    private static String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WorkerUnavailableException("ワーカーに接続できません(起動していない可能性があります): " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException("ワーカーへのリクエストが中断されました: " + describe(e), e);
        }
    }

    private <T> T parseOrThrow(HttpResponse<String> response, TypeReference<T> type) {
        if (response.statusCode() >= 400) {
            throw errorFor(response);
        }
        try {
            String body = response.body();
            if (body == null || body.isBlank()) {
                return null;
            }
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new WorkerUnavailableException("ワーカーの応答を解析できませんでした: " + e.getMessage(), e);
        }
    }

    private RuntimeException errorFor(HttpResponse<String> response) {
        try {
            Map<String, Object> body = mapper.readValue(response.body(), MAP_TYPE);
            return new WorkerApiException(response.statusCode(), body);
        } catch (Exception parseError) {
            return new WorkerUnavailableException(
                    "ワーカーがエラーを返しました(HTTP " + response.statusCode() + "): " + response.body(), parseError);
        }
    }
}
