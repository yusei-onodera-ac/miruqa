package ai.hack2026.web.findings;

/** v0.7 P6中核: 不具合(Finding)の対応状況。 */
public enum FindingStatus {
    UNADDRESSED("未対応"),
    ADDRESSED("対応済み"),
    WONT_FIX("対応しない");

    private final String label;

    FindingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 不正な値・未指定は既定値(未対応)として扱う(呼び出し元で例外にしない)。 */
    public static FindingStatus fromValue(String value) {
        if (value == null) {
            return UNADDRESSED;
        }
        for (FindingStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return UNADDRESSED;
    }
}
