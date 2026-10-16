package com.example.demo.security;

/** アカウント登録時の入力規則。ログイン時の既存パスワードには適用しない。 */
public final class AccountRules {
    public static final int PASSWORD_MIN_LENGTH = 12;
    public static final int PASSWORD_MAX_LENGTH = 128;
    public static final int PASSWORD_MIN_CATEGORIES = 3;
    public static final int LOGIN_ID_MAX_LENGTH = 50;
    public static final String PASSWORD_REQUIREMENTS =
            "12～128文字で、英大文字・英小文字・数字・記号の4種類のうち3種類以上を含めてください。順序は自由です。";

    private AccountRules() {}

    public static void validateLoginId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("会社から割り当てられた社員番号（ログインID）を入力してください。");
        }
        String loginId = value.trim();
        if (length(loginId) > LOGIN_ID_MAX_LENGTH || loginId.codePoints().anyMatch(AccountRules::isInvalidCharacter)) {
            throw new IllegalArgumentException("社員番号（ログインID）は制御文字を含めず、50文字以内で入力してください。");
        }
    }

    public static void validateNewPassword(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("初期パスワードを入力してください。");
        }
        if (length(value) < PASSWORD_MIN_LENGTH || length(value) > PASSWORD_MAX_LENGTH) {
            throw new IllegalArgumentException("パスワードは12～128文字で入力してください。");
        }
        if (value.codePoints().anyMatch(AccountRules::isInvalidCharacter)) {
            throw new IllegalArgumentException("パスワードに改行や制御文字は使用できません。");
        }
        int categories = 0;
        if (value.chars().anyMatch(c -> c >= 'A' && c <= 'Z')) categories++;
        if (value.chars().anyMatch(c -> c >= 'a' && c <= 'z')) categories++;
        if (value.chars().anyMatch(c -> c >= '0' && c <= '9')) categories++;
        if (value.chars().anyMatch(AccountRules::isSymbol)) categories++;
        if (categories < PASSWORD_MIN_CATEGORIES) {
            throw new IllegalArgumentException("英大文字・英小文字・数字・記号の4種類のうち、3種類以上を含めてください。");
        }
    }

    public static void validatePasswordConfirmation(String password, String confirmation) {
        if (confirmation == null || confirmation.isEmpty()) {
            throw new IllegalArgumentException("確認用パスワードを入力してください。");
        }
        if (!password.equals(confirmation)) {
            throw new IllegalArgumentException("パスワードと確認用パスワードが一致しません。");
        }
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean isInvalidCharacter(int c) {
        return Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || (c >= 0xD800 && c <= 0xDFFF);
    }

    private static boolean isSymbol(int c) {
        return (c >= 33 && c <= 47) || (c >= 58 && c <= 64)
                || (c >= 91 && c <= 96) || (c >= 123 && c <= 126);
    }
}
