package com.miruqa.landing.value;

/** 値オブジェクトの検証に失敗したときの例外。メッセージは、そのまま画面に表示できる。 */
public class InvalidValueException extends RuntimeException {
    public InvalidValueException(String message) { super(message); }
}
