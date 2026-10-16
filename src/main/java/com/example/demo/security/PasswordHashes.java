package com.example.demo.security;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** アプリと移行ツールで同一のArgon2id仕様を利用する。平文照合は提供しない。 */
public final class PasswordHashes {
    private static final Pattern FORMAT = Pattern.compile(
            "\\{argon2}\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");

    private PasswordHashes() {}

    public static PasswordEncoder encoder() {
        return new DelegatingPasswordEncoder("argon2",
                Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19456, 2)));
    }

    public static boolean isSupported(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
