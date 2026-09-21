package ai.hack2026.web.auth;

import ai.hack2026.web.consent.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/** ログイン画面・サインアップ画面・メール確認・パスワード再設定(v0.6 P1、U-1)。 */
@Controller
public class AuthController {

    private final SignupService signupService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final MembershipRepository membershipRepository;
    private final ConsentService consentService;

    public AuthController(
            SignupService signupService,
            UserRepository userRepository,
            TokenService tokenService,
            MembershipRepository membershipRepository,
            ConsentService consentService) {
        this.signupService = signupService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.membershipRepository = membershipRepository;
        this.consentService = consentService;
    }

    @GetMapping("/login")
    public String loginForm() {
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("form", new SignupForm());
        return "auth/signup";
    }

    /** 【踏んだ不具合(実測)】フィールドを public にしただけ(getter/setterなし)だと、Spring MVCの
     * @ModelAttributeバインディング(BeanWrapperのJavaBean規約に依存)がまったく効かず、
     * 送信した値が常にnullのまま扱われる(バリデーションエラーにもならず、静かに空文字列に
     * フォールバックしてしまうため発見が遅れた)。curlで生パラメータを送っても、
     * 常に「既に登録されているメールアドレスです: 」(空文字列)になる形で顕在化した。
     * getter/setterを用意することで解消した。 */
    public static class SignupForm {
        private String email;
        private String password;
        private String displayName;
        private String organizationName;
        private boolean termsAccepted;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getOrganizationName() { return organizationName; }
        public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
        public boolean isTermsAccepted() { return termsAccepted; }
        public void setTermsAccepted(boolean termsAccepted) { this.termsAccepted = termsAccepted; }
    }

    /** L-3: 利用規約・プライバシーポリシー(下書き。【要法務確認】)への同意を、サインアップの必須条件にする。
     * 同意は版番号・日時・IPのハッシュつきで記録する(Consent)。 */
    @PostMapping("/signup")
    public String signup(@ModelAttribute SignupForm form, Model model, HttpServletRequest request) {
        if (!form.isTermsAccepted()) {
            model.addAttribute("form", form);
            model.addAttribute("error", "利用規約・プライバシーポリシーへの同意が必要です。");
            return "auth/signup";
        }
        User user;
        try {
            user = signupService.signUp(
                    form.getEmail() == null ? "" : form.getEmail().trim(),
                    form.getPassword() == null ? "" : form.getPassword(),
                    blankToDefault(form.getDisplayName(), "担当者"),
                    blankToDefault(form.getOrganizationName(), (form.getEmail() == null ? "組織" : form.getEmail()) + " の組織"));
        } catch (SignupService.DuplicateEmailException | PasswordPolicy.WeakPasswordException e) {
            model.addAttribute("form", form);
            model.addAttribute("error", e.getMessage());
            return "auth/signup";
        } catch (IllegalArgumentException e) {
            model.addAttribute("form", form);
            model.addAttribute("error", "入力内容を確認してください: " + e.getMessage());
            return "auth/signup";
        }
        Long organizationId = membershipRepository.findByUserId(user.getId()).get(0).getOrganization().getId();
        consentService.recordTermsConsent(user.getId(), organizationId, request.getRemoteAddr());
        return "redirect:/login?registered";
    }

    /** メール確認リンク(模擬送信されたメール本文内のURL)。 */
    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam String token, Model model) {
        Optional<AuthToken> valid = tokenService.verify(token, AuthToken.PURPOSE_EMAIL_VERIFY);
        if (valid.isEmpty()) {
            model.addAttribute("title", "確認リンクが無効です");
            model.addAttribute("message", "リンクの有効期限が切れているか、既に使用されています。ログイン後、再送信してください。");
            return "auth/token-error";
        }
        AuthToken authToken = valid.get();
        userRepository.findById(authToken.getUserId()).ifPresent(user -> {
            user.setEmailVerifiedAt(java.time.Instant.now());
            userRepository.save(user);
        });
        tokenService.consume(authToken);
        return "redirect:/login?emailVerified";
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String email, Model model) {
        signupService.requestPasswordReset(email == null ? "" : email.trim());
        // 登録有無に関わらず同じ文言(メールアドレスの存在を外部から推測されないようにするため)
        model.addAttribute("message", "入力されたメールアドレス宛に、パスワード再設定の案内を送信しました(登録がある場合のみ)。");
        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        if (tokenService.verify(token, AuthToken.PURPOSE_PASSWORD_RESET).isEmpty()) {
            model.addAttribute("title", "再設定リンクが無効です");
            model.addAttribute("message", "リンクの有効期限(30分)が切れているか、既に使用されています。もう一度「パスワードを忘れた場合」からやり直してください。");
            return "auth/token-error";
        }
        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token, @RequestParam String password, Model model) {
        Optional<AuthToken> valid = tokenService.verify(token, AuthToken.PURPOSE_PASSWORD_RESET);
        if (valid.isEmpty()) {
            model.addAttribute("title", "再設定リンクが無効です");
            model.addAttribute("message", "リンクの有効期限(30分)が切れているか、既に使用されています。もう一度「パスワードを忘れた場合」からやり直してください。");
            return "auth/token-error";
        }
        try {
            signupService.resetPassword(valid.get(), password);
        } catch (PasswordPolicy.WeakPasswordException e) {
            model.addAttribute("token", token);
            model.addAttribute("error", e.getMessage());
            return "auth/reset-password";
        }
        return "redirect:/login?passwordReset";
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
