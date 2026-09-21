package com.miruqa.landing.value;

import java.util.Locale;
import java.util.regex.Pattern;

/** メールアドレスを表す値オブジェクト。生成時に検証し、小文字に正規化する。 */
public record Email(String value) {
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    public static final int MAX_LENGTH = 254;

    public Email {
        if (value == null) throw new InvalidValueException("メールアドレスを入力してください。");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new InvalidValueException("メールアドレスを入力してください。");
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new InvalidValueException("メールアドレスの形式が正しくありません。");
        }
    }

    /** 一部を伏せた表示用の文字列（ログ出力用）。 */
    public String masked() {
        int at = value.indexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }
}
