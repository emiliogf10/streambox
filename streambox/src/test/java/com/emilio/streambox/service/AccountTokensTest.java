package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests unitarios de {@link AccountTokens}: la forma de los tokens que comparten
 * la verificación del correo y «He olvidado mi contraseña», su hash y el
 * reconocimiento de lo que no puede ser un token propio. (Estaban en
 * {@code EmailVerificationServiceTest} antes de sacar el código común.)
 */
class AccountTokensTest {

    /** 32 bytes aleatorios en Base64URL sin relleno: 43 caracteres que van tal cual en una URL. */
    @Test
    void cadaTokenTiene43CaracteresDeBase64UrlYNoSeRepite() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = AccountTokens.newToken();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
            assertTrue(AccountTokens.isWellFormed(token), token);
            assertTrue(seen.add(token), "Token repetido: " + token);
        }
    }

    /** En la base de datos va el SHA-256 en hexadecimal en minúsculas (vector conocido de «abc»). */
    @Test
    void elHashEsElSha256EnHexadecimalEnMinusculas() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AccountTokens.sha256Hex("abc"));
    }

    /** Solo 43 caracteres exactos de Base64URL tienen forma de token propio. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", " ", "abc", "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iYZ", "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i=",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i+", " q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iñ" })
    void loQueNoTieneLaFormaExactaNoEsUnToken(String token) {
        assertFalse(AccountTokens.isWellFormed(token));
    }
}
