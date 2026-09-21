package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.worker.WorkerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * トップバーの環境バッジ(TEST_MODE)・ワーカー死活状態を、全画面で共通に使えるようにする。
 * ワーカーが止まっていてもこの処理自体は例外を投げない(評価③): バッジが「不明」になるだけ。
 *
 * あわせて、v0.6の共通ヘッダー(fragments/shell::header)が使うログイン中ユーザーのメールアドレスも
 * ここで注入する(未ログイン画面ではuserがnullなので、その場合はnullのままにする)。
 */
@ControllerAdvice
public class GlobalModelAttributes {

    private final WorkerClient workerClient;
    private final String productName;

    public GlobalModelAttributes(
            WorkerClient workerClient,
            @Value("${app.product-name:MiruQA}") String productName) {
        this.workerClient = workerClient;
        this.productName = productName;
    }

    /** v0.7: サービス名(MiruQA)。設定1箇所(app.product-name)から全画面へ配る。 */
    @ModelAttribute("productName")
    public String productName() {
        return productName;
    }

    @ModelAttribute
    public void addWorkerHealth(Model model) {
        try {
            var health = workerClient.health();
            model.addAttribute("workerUp", true);
            model.addAttribute("testMode", Boolean.TRUE.equals(health.get("testMode")));
        } catch (Exception e) {
            model.addAttribute("workerUp", false);
            model.addAttribute("testMode", false);
        }
    }

    @ModelAttribute("currentUserEmail")
    public String currentUserEmail(@AuthenticationPrincipal AppUserPrincipal user) {
        return user == null ? null : user.getUsername();
    }
}
