package com.miruqa.landing.value;

/** 入力文字列の共通の整形。前後の空白を除き、制御文字を取り除く（HTMLの無害化は、表示側でエスケープする）。 */
final class TextRules {
    private TextRules() {}

    static String clean(String s) {
        if (s == null) return "";
        return stripControls(s, false).trim();
    }

    static String cleanMultiline(String s) {
        if (s == null) return "";
        return stripControls(s.replace("\r\n", "\n").replace('\r', '\n'), true).trim();
    }

    private static String stripControls(String s, boolean keepNewline) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' && keepNewline) { sb.append(c); continue; }
            if (Character.isISOControl(c)) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
