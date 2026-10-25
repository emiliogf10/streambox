package com.emilio.streambox.entity;

/**
 * Para qué sirve un {@link AccountToken} (columna {@code account_tokens.purpose}).
 *
 * <p>
 * Se guarda por su nombre ({@code EnumType.STRING}), y la base de datos solo
 * admite los valores de la restricción {@code ck_account_tokens_purpose}
 * (creada en la {@code V9} y ampliada en la {@code V10}). <strong>Añadir un
 * valor aquí exige una migración nueva</strong> que sustituya esa restricción;
 * si no, el {@code INSERT} del token nuevo falla con una violación de
 * {@code CHECK}. No renombres un valor: las filas guardadas conservan el nombre
 * antiguo y dejarían de leerse.
 * </p>
 *
 * <p>
 * El propósito separa los usos de la misma tabla: un token de verificación
 * nunca sirve para restablecer la contraseña ni al revés, porque todas las
 * consultas de {@code AccountTokenRepository} que deciden si un token vale
 * filtran por él (solo la limpieza de caducados y la invalidación de todos los
 * de una cuenta lo omiten, a propósito).
 * </p>
 */
public enum AccountTokenPurpose {

    /**
     * Enlace del correo que confirma que el email de una cuenta nueva es de
     * quien la registró.
     */
    EMAIL_VERIFICATION,

    /**
     * Enlace de «He olvidado mi contraseña»: permite fijar una contraseña
     * nueva a quien controla el buzón de la cuenta ({@code V10}).
     *
     * <p>
     * Es más sensible que la verificación (quien lo tenga entra en la cuenta),
     * así que la aplicación le da una caducidad más corta y, al usarlo, invalida
     * los demás tokens vigentes de la cuenta.
     * </p>
     */
    PASSWORD_RESET
}
