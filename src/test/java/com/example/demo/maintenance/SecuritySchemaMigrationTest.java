package com.example.demo.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SecuritySchemaMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"})
    void acceptsEncryptedModesIncludingStrictIdentity(String mode) {
        assertDoesNotThrow(() -> SecuritySchemaMigration.validateDestination(
                "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=" + mode
                + "&trustCertificateKeyStoreUrl=file%3AD%3A%2Fprivate%2Fca.p12"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=DISABLED",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=PREFERRED",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=REQUIRED_EXTRA",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=REQUIRED&sslMode=DISABLED",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=VERIFY_IDENTITY&SSLMODE=DISABLED",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=REQUIRED&%73slMode=DISABLED",
        "jdbc:mysql://example.aivencloud.com:12029/other?sslMode=REQUIRED",
        "jdbc:mysql://example.aivencloud.com.evil.test:12029/oa_system?sslMode=REQUIRED",
        "jdbc:mysql://evil.test:12029/oa_system?note=.aivencloud.com:&sslMode=REQUIRED",
        "jdbc:mysql://example.aivencloud.com/oa_system?sslMode=REQUIRED",
        "jdbc:mysql://user:pass@example.aivencloud.com:12029/oa_system?sslMode=REQUIRED",
        "jdbc:mysql://example.aivencloud.com:12029/oa_system?sslMode=REQUIRED#fragment",
        "jdbc:postgresql://example.aivencloud.com:12029/oa_system?sslMode=REQUIRED"
    })
    void rejectsUnencryptedAmbiguousOrUnexpectedDestinations(String url) {
        assertThrows(IllegalArgumentException.class, () -> SecuritySchemaMigration.validateDestination(url));
    }
}
