package com.miruqa.landing.value;

/** お問い合わせの本文。1〜2000文字。改行は使える。 */
public record MessageText(String value) {
    public static final int MAX_LENGTH = 2000;

    public MessageText {
        value = TextRules.cleanMultiline(value);
        if (value.isEmpty()) throw new InvalidValueException("お問い合わせ内容を入力してください。");
        if (value.length() > MAX_LENGTH) throw new InvalidValueException("お問い合わせ内容は" + MAX_LENGTH + "文字以内で入力してください。");
    }
}
