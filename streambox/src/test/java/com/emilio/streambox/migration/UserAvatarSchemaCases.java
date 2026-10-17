package com.emilio.streambox.migration;

import java.util.List;

/**
 * Casos para la restricción {@code ck_users_avatar} de la migración
 * {@code V7__user_avatar.sql}, compartidos por el test de esquema de H2
 * ({@code FlywaySchemaIntegrationTest}) y el de PostgreSQL real
 * ({@code postgres.PostgresSchemaIntegrationTest}), para que los dos motores
 * se prueben con exactamente las mismas cadenas.
 *
 * <p>
 * La base de datos solo comprueba el <b>formato</b> de la clave (de 1 a 32
 * caracteres de {@code [a-z0-9-]}), no que sea uno de los avatares existentes:
 * esa lista blanca la valida el backend, para que añadir un avatar no exija una
 * migración. Por eso {@link #VALID} incluye claves que hoy no existen.
 * </p>
 */
public final class UserAvatarSchemaCases {

    /** Claves que el {@code CHECK} acepta: las doce de hoy y otras con formato válido. */
    public static final List<String> VALID = List.of(
            "rocket", "ghost", "cat", "dog", "star", "flame", "leaf", "moon", "sun", "zap", "music", "crown",
            "a", "0", "avatar-2", "-", "a".repeat(32));

    /**
     * Cadenas de longitud válida (de 0 a 32 caracteres) que el {@code CHECK}
     * rechaza por su formato. Las de más de 32 caracteres las rechaza antes el
     * propio {@code VARCHAR(32)}, con otro error (22001).
     */
    public static final List<String> INVALID_FORMAT = List.of(
            "",
            " ",
            "Rocket",
            "ROCKET",
            " rocket",
            "rocket ",
            "rock et",
            "rock_et",
            "rock.et",
            "rocket\n",
            "cohete-ñ",
            // Signo Kelvin (U+212A): en minúscula es una «k» ASCII; por eso el CHECK no usa LOWER
            "Kat",
            // Letras de ancho completo: no son ASCII aunque se vean igual
            "ｒｏｃｋｅｔ",
            "https://evil.example/a.png",
            "<img src=x>");

    private UserAvatarSchemaCases() {
    }
}
