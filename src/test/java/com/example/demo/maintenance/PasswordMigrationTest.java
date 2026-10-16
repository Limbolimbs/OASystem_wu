package com.example.demo.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PasswordMigrationTest {
    @Test void encryptedSnapshotIsAuthenticatedAndRestoresTheExactCredential() throws Exception {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var rows = List.of(new PasswordMigration.Credential(1L, " Legacy秘密123! ", "migrationHash"));
        byte[] archive = PasswordMigration.seal(rows, key);
        assertFalse(new String(archive, StandardCharsets.ISO_8859_1).contains("Legacy"));
        assertEquals(rows, PasswordMigration.open(archive, key));
        assertFalse(Arrays.equals(archive, PasswordMigration.seal(rows, key)));
        byte[] wrongKey = key.clone(); wrongKey[0] ^= 1;
        assertThrows(javax.crypto.AEADBadTagException.class, () -> PasswordMigration.open(archive, wrongKey));
        archive[archive.length - 1] ^= 1;
        assertThrows(javax.crypto.AEADBadTagException.class, () -> PasswordMigration.open(archive, key));
    }
}
