package com.miruqa.landing.value;

/** 会社名・組織名。任意（空でもよい）。最大100文字。 */
public record CompanyName(String value) {
    public static final int MAX_LENGTH = 100;

    public CompanyName {
        value = TextRules.clean(value);
        if (value.length() > MAX_LENGTH) throw new InvalidValueException("会社名は" + MAX_LENGTH + "文字以内で入力してください。");
    }

    public boolean isEmpty() { return value.isEmpty(); }
}
