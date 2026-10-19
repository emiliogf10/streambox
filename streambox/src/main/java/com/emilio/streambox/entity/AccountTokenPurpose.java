package com.emilio.streambox.entity;

/**
 * Para qué sirve un {@link AccountToken} (columna {@code account_tokens.purpose}).
 *
 * <p>
 * Se guarda por su nombre ({@code EnumType.STRING}), y la base de datos solo
 * admite los valores de la restricción {@code ck_account_tokens_purpose}
 * ({@code V9}). <strong>Añadir un valor aquí exige una migración nueva</strong>
 * que sustituya esa restricción; si no, el {@code INSERT} del token nuevo falla
 * con una violación de {@code CHECK}. El propósito separa los usos de la misma
 * tabla: un token de verificación nunca sirve para restablecer la contraseña,
 * porque todas las consultas de {@code AccountTokenRepository} filtran por él.
 * </p>
 */
public enum AccountTokenPurpose {

    /**
     * Enlace del correo que confirma que el email de una cuenta nueva es de
     * quien la registró.
     */
    EMAIL_VERIFICATION
}
