package ai.hack2026.web.auth;

import ai.hack2026.web.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** サインアップ(U-1)。0a章の規模に合わせ、1アカウント=1組織を自動作成する(招待リンクは作らない)。 */
@Service
public class SignupService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final MockMailService mailService;
    private final AuditService auditService;
    private final TokenService tokenService;
    private final String productName;

    public SignupService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            MockMailService mailService,
            AuditService auditService,
            TokenService tokenService,
            @Value("${app.product-name:MiruQA}") String productName) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.productName = productName;
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email) { super("既に登録されているメールアドレスです: " + email); }
    }

    @Transactional
    public User signUp(String email, String rawPassword, String displayName, String organizationName) {
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        PasswordPolicy.validate(rawPassword);  // 8文字未満・よくある弱いパスワードは例外(開発者のレビュー対応)
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName);
        user = userRepository.save(user);

        Organization org = new Organization();
        org.setName(organizationName);
        org = organizationRepository.save(org);

        Membership membership = new Membership();
        membership.setUser(user);
        membership.setOrganization(org);
        membership.setRole(Role.OWNER);
        membershipRepository.save(membership);

        auditService.record(org.getId(), user.getId(), "signup", "組織「" + organizationName + "」を作成");

        // メール認証は模擬送信だが、トークンは本物(ハッシュ化保存・期限つき・ワンタイム)。
        // /verify-email?token=... を開くとemailVerifiedAtが埋まる(AuthController参照)
        String rawToken = tokenService.issue(user.getId(), AuthToken.PURPOSE_EMAIL_VERIFY);
        mailService.send(email, "[" + productName + "] メールアドレスの確認(模擬送信)",
                productName + "にご登録いただきありがとうございます。\n"
                        + "以下のリンクを開いてメールアドレスを確認してください(24時間有効・模擬送信のためファイル出力のみ):\n"
                        + "/verify-email?token=" + rawToken);

        return user;
    }

    /** パスワード再設定の申請(U-1)。メールが存在しなくても同じ応答にする
     * (登録済みメールアドレスの有無を外部から推測されないようにするため)。 */
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = tokenService.issue(user.getId(), AuthToken.PURPOSE_PASSWORD_RESET);
            mailService.send(email, "[" + productName + "] パスワード再設定(模擬送信)",
                    "以下のリンクを開いて新しいパスワードを設定してください(30分有効・模擬送信のためファイル出力のみ):\n"
                            + "/reset-password?token=" + rawToken);
        });
    }

    @Transactional
    public void resetPassword(AuthToken token, String newRawPassword) {
        PasswordPolicy.validate(newRawPassword);
        User user = userRepository.findById(token.getUserId()).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        tokenService.consume(token);
        auditService.record(null, user.getId(), "password_reset", null);
    }
}
