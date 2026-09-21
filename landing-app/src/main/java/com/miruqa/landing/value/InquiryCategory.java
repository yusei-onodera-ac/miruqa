package com.miruqa.landing.value;

import java.util.Arrays;

/** お問い合わせの種別。 */
public enum InquiryCategory {
    EARLY_ACCESS("先行案内を希望する"),
    DEMO("デモ・導入のご相談"),
    OTHER("その他のお問い合わせ");

    private final String label;
    InquiryCategory(String label) { this.label = label; }
    public String getLabel() { return label; }

    public static InquiryCategory parse(String code) {
        return Arrays.stream(values()).filter(c -> c.name().equals(code)).findFirst()
                .orElseThrow(() -> new InvalidValueException("お問い合わせの種別を選択してください。"));
    }
}
