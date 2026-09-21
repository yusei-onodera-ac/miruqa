package ai.hack2026.web;

import ai.hack2026.web.worker.WorkerApiException;
import ai.hack2026.web.worker.WorkerUnavailableException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * ワーカーが止まっている・遅い・エラーを返すときも、この画面は落ちない(評価③・FR-40・AC-21)。
 * スタックトレースは画面に出さず、再試行を促すメッセージだけを表示する。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WorkerUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String handleUnavailable(WorkerUnavailableException e, Model model) {
        log.warn("worker unavailable: {}", e.getMessage());
        model.addAttribute("title", "ワーカーに接続できません");
        model.addAttribute("message", e.getMessage());
        model.addAttribute("retryable", true);
        return "error";
    }

    @ExceptionHandler(WorkerApiException.class)
    public String handleApiError(WorkerApiException e, HttpServletResponse response, Model model) {
        log.info("worker api error: {} {}", e.getErrorCode(), e.getMessage());
        // ワーカーが返した実際のステータスコード(400/404等)をそのまま返す。これを忘れると
        // ブラウザ側は「200 OK」としてHTMLのエラーページをJSONとしてパースしようとして
        // 壊れる(実際にブラウザで踏んだ不具合。2026-09-20)。
        response.setStatus(e.getStatusCode());
        String title = "domain_not_allowed".equals(e.getErrorCode())
                ? "許可されていないドメインです"
                : "リクエストを処理できませんでした";
        model.addAttribute("title", title);
        model.addAttribute("message", e.getMessage());
        model.addAttribute("errorCode", e.getErrorCode());
        model.addAttribute("retryable", false);
        return "error";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String handleResponseStatus(ResponseStatusException e, HttpServletResponse response, Model model) {
        // 404(テナント分離、v0.6 AC-U1)等、コントローラーが意図的に指定したステータスは
        // そのまま返す(Exception.classの汎用ハンドラに握りつぶされて500になるのを防ぐ)。
        response.setStatus(e.getStatusCode().value());
        model.addAttribute("title", e.getStatusCode().value() == 404 ? "見つかりません" : "リクエストを処理できませんでした");
        model.addAttribute("message", e.getReason() != null ? e.getReason() : "指定されたリソースにアクセスできません。");
        model.addAttribute("retryable", false);
        return "error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception e, Model model) {
        log.error("unexpected error", e);
        model.addAttribute("title", "想定外のエラーが発生しました");
        model.addAttribute("message", "しばらく待ってから、もう一度お試しください。");
        model.addAttribute("retryable", true);
        return "error";
    }
}
