package com.emilio.streambox.support;

import java.time.Instant;
import java.util.Locale;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Utilidades de los tests para las cuentas y la verificación del correo (V9).
 *
 * <p>
 * Desde la V9 el registro deja la cuenta <b>pendiente</b> y una cuenta pendiente
 * no puede iniciar sesión (el login responde el 401 de un fallo) ni usar la API con un
 * JWT. Los tests que registran por HTTP y luego inician sesión no prueban la
 * verificación, así que marcan la cuenta como verificada con este método, igual
 * que haría el clic en el enlace del correo.
 * </p>
 */
public final class TestAccounts {

    private TestAccounts() {
    }

    /**
     * Marca como verificada la cuenta con ese email (normalizado como en el
     * registro).
     *
     * <p>
     * Se hace sobre la entidad y con {@code saveAndFlush}, no con
     * {@code UserRepository.markEmailVerified}, a propósito: en un test
     * {@code @Transactional}, MockMvc atiende la petición en la misma
     * transacción y la misma sesión de Hibernate, y un {@code UPDATE} masivo no
     * cambiaría la entidad ya cargada en esa sesión (el login la leería aún
     * pendiente). Así funciona igual con o sin transacción del test.
     * </p>
     *
     * @param userRepository repositorio de usuarios
     * @param email          email con el que se registró la cuenta
     * @return la cuenta, ya verificada
     */
    public static User markEmailVerified(UserRepository userRepository, String email) {
        User user = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException("No existe la cuenta " + email));
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }
}
