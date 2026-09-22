package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.credit.CreditLedgerEntry;
import ai.hack2026.web.credit.CreditPack;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.payment.PaymentGateway;
import ai.hack2026.web.payment.PaymentRequest;
import ai.hack2026.web.payment.PaymentResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * v0.8第3章: クレジットのチャージ(架空の決済。デモ)。カード情報(番号・期限・名義)は、
 * このコントローラでも保存・ログ出力しない(受け取ったらPaymentGatewayに渡すだけ)。
 * 本物の課金・外部決済APIは呼ばない({@link ai.hack2026.web.payment.FakePaymentGateway}参照)。
 */
@Controller
public class CreditController {

    private static final DateTimeFormatter JST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Tokyo"));

    private final CreditService creditService;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;

    public CreditController(CreditService creditService, PaymentGateway paymentGateway, AuditService auditService) {
        this.creditService = creditService;
        this.paymentGateway = paymentGateway;
        this.auditService = auditService;
    }

    /** 指揮官バグ報告(2026-09-22)対応: チャージ後に元の画面(実行前のプラン等)へ戻すための
     * returnTo。オープンリダイレクト対策として、自サイト内の決まった形のパスだけを許可する
     * (任意のURLを転送先にはしない)。 */
    private static final java.util.regex.Pattern SAFE_RETURN_TO = java.util.regex.Pattern.compile("^/plans/[A-Za-z0-9_-]+$");

    private static String sanitizeReturnTo(String returnTo) {
        return returnTo != null && SAFE_RETURN_TO.matcher(returnTo).matches() ? returnTo : null;
    }

    @GetMapping("/credits/charge")
    public String chargeForm(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(required = false) String returnTo,
            Model model) {
        model.addAttribute("packs", CreditPack.ALL);
        model.addAttribute("creditBalance", creditService.getBalance(user.getOrganizationId()));
        if (!model.containsAttribute("returnTo")) {
            model.addAttribute("returnTo", sanitizeReturnTo(returnTo));
        }
        return "credit/charge";
    }

    @PostMapping("/credits/charge")
    public String charge(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam String packId,
            @RequestParam String cardNumber,
            @RequestParam String expiry,
            @RequestParam String cardHolderName,
            @RequestParam(required = false) String returnTo,
            RedirectAttributes redirectAttributes) {
        String safeReturnTo = sanitizeReturnTo(returnTo);
        CreditPack pack = CreditPack.byId(packId);
        if (pack == null) {
            redirectAttributes.addFlashAttribute("error", "選択されたパックが見つかりません。もう一度お試しください。");
            redirectAttributes.addFlashAttribute("returnTo", safeReturnTo);
            return "redirect:/credits/charge";
        }
        // カード情報(cardNumber/expiry/cardHolderName)は、ここから先はPaymentRequestの中だけで
        // 扱い、この後の変数・ログ・監査記録には一切残さない。
        PaymentResult result = paymentGateway.charge(new PaymentRequest(cardNumber, expiry, cardHolderName, pack.yenAmount()));
        if (!result.success()) {
            auditService.record(user.getOrganizationId(), user.getUserId(), "credit_charge_failed", "packId=" + packId);
            redirectAttributes.addFlashAttribute("error", result.failureReason());
            redirectAttributes.addFlashAttribute("returnTo", safeReturnTo);
            return "redirect:/credits/charge";
        }
        creditService.charge(user.getOrganizationId(), pack.credits(), result.receiptNumber(),
                pack.yenAmount() + "円チャージ(" + pack.id() + ")");
        auditService.record(user.getOrganizationId(), user.getUserId(), "credit_charged",
                "packId=" + packId + " credits=" + pack.credits() + " receiptNumber=" + result.receiptNumber());
        redirectAttributes.addFlashAttribute("receiptNumber", result.receiptNumber());
        redirectAttributes.addFlashAttribute("receiptAt", JST.format(Instant.now()));
        redirectAttributes.addFlashAttribute("yenAmount", pack.yenAmount());
        redirectAttributes.addFlashAttribute("creditsGranted", pack.credits());
        redirectAttributes.addFlashAttribute("returnTo", safeReturnTo);
        return "redirect:/credits/receipt";
    }

    @GetMapping("/credits/receipt")
    public String receipt(@AuthenticationPrincipal AppUserPrincipal user, Model model) {
        if (!model.containsAttribute("receiptNumber")) {
            // 直接アクセス(リロード等)。二重チャージにならないよう、フォームへ戻す。
            return "redirect:/credits/charge";
        }
        model.addAttribute("creditBalance", creditService.getBalance(user.getOrganizationId()));
        return "credit/receipt";
    }

    @GetMapping("/credits/history")
    public String history(@AuthenticationPrincipal AppUserPrincipal user, Model model) {
        List<CreditLedgerEntry> entries = creditService.history(user.getOrganizationId());
        // Thymeleafはjava.time.Instantを標準では扱えない(thymeleaf-extras-java8time未導入)ため、
        // 他画面(DisplayFormat)と同じ方針で、表示用の文字列にしてからテンプレートへ渡す。
        List<java.util.Map<String, Object>> rows = entries.stream().map(e -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("createdAtDisplay", JST.format(e.getCreatedAt()));
            row.put("kind", e.getKind());
            row.put("amountCredits", e.getAmountCredits());
            row.put("runId", e.getRunId());
            row.put("paymentReference", e.getPaymentReference());
            row.put("detail", e.getDetail());
            return row;
        }).toList();
        model.addAttribute("entries", rows);
        model.addAttribute("creditBalance", creditService.getBalance(user.getOrganizationId()));
        return "credit/history";
    }
}
