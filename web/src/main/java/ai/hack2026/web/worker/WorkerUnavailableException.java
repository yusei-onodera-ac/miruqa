package ai.hack2026.web.worker;

/**
 * ワーカー(Python)に接続できない・応答がない・応答を解釈できないときの例外。
 * 評価③(FR-40): 画面はこれを捕まえて、落ちずに再試行できる形で表示する。
 */
public class WorkerUnavailableException extends RuntimeException {
    public WorkerUnavailableException(String message) {
        super(message);
    }

    public WorkerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
