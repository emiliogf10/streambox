package com.emilio.streambox.security.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.repository.RefreshTokenRepository;

/**
 * Escenario compartido por las pruebas de concurrencia del refresh (H2 y
 * PostgreSQL): N hilos arrancan a la vez con el mismo refresh token.
 */
public final class RefreshConcurrencyScenario {

    private RefreshConcurrencyScenario() {
    }

    /**
     * Lanza {@code threads} refresh simultáneos de {@code token} y comprueba
     * que exactamente uno ha rotado (devuelve un refresh token nuevo), que los
     * demás han recibido solo un JWT (gracia, sin error) y que la familia
     * acaba con el token original revocado y un único sucesor activo.
     *
     * @param service    servicio real
     * @param repository repositorio, para contar los tokens de la familia
     * @param token      primer token de una familia recién abierta
     * @param threads    número de peticiones simultáneas
     * @throws Exception si algún hilo falla (p. ej. un choque al escribir)
     */
    public static void assertSingleRotation(RefreshTokenService service, RefreshTokenRepository repository,
            IssuedRefreshToken token, int threads) throws Exception {

        UUID family = familyOf(repository, token);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        try {
            List<Future<SessionTokens>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return service.refresh(token.value());
                }));
            }

            int rotated = 0;
            for (Future<SessionTokens> result : results) {
                SessionTokens tokens = result.get(30, TimeUnit.SECONDS);
                assertNotNull(tokens.accessToken());
                rotated += tokens.refreshToken() != null ? 1 : 0;
            }

            assertEquals(1, rotated, "solo una de las peticiones simultáneas puede rotar");
            List<RefreshToken> familyTokens = repository.findAll().stream()
                    .filter(t -> family.equals(t.getFamilyId()))
                    .toList();
            assertEquals(2, familyTokens.size(), "el original y un único sucesor");
            assertEquals(1, familyTokens.stream().filter(t -> t.getRevokedAt() == null).count());
        } finally {
            pool.shutdownNow();
        }
    }

    private static UUID familyOf(RefreshTokenRepository repository, IssuedRefreshToken token) {
        String hash = RefreshTokenService.sha256Hex(token.value());
        return repository.findAll().stream()
                .filter(t -> hash.equals(t.getTokenHash()))
                .findFirst()
                .orElseThrow()
                .getFamilyId();
    }
}
