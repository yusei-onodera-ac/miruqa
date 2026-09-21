package ai.hack2026.web.worker;

import java.util.Map;

/**
 * ワーカーが構造化されたエラー({"error","message"}、docs/contracts.md)を返したときの例外。
 * 許可外ドメイン(domain_not_allowed)など、ユーザーの入力に起因するものはこちらで表現する。
 */
public class WorkerApiException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    public WorkerApiException(int statusCode, Map<String, Object> body) {
        super(String.valueOf(body == null ? null : body.get("message")));
        this.statusCode = statusCode;
        this.errorCode = body == null ? "" : String.valueOf(body.getOrDefault("error", ""));
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
