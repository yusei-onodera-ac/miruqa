package com.miruqa.landing.value;

/** お名前。1〜60文字。制御文字は使えない。 */
public record PersonName(String value) {
    public static final int MAX_LENGTH = 60;

    public PersonName {
        value = TextRules.clean(value);
        if (value.isEmpty()) throw new InvalidValueException("お名前を入力してください。");
        if (value.length() > MAX_LENGTH) throw new InvalidValueException("お名前は" + MAX_LENGTH + "文字以内で入力してください。");
    }
}
