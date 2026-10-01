/**
 * Constantes compartidas por la configuración de Playwright, la siembra de datos
 * y los tests.
 *
 * La suite E2E levanta SU PROPIO backend y SU PROPIO Vite en puertos distintos
 * de los de desarrollo (8080 y 5173) para no tocar nunca la aplicación ni la
 * base de datos que el desarrollador tenga en marcha.
 */

/** Puerto del backend efímero (perfil `e2e`, H2 en memoria). El de desarrollo usa el 8080. */
export const BACKEND_PORT = 8099;

/** Puerto de Vite para la suite. El de desarrollo usa el 5173. */
export const FRONTEND_PORT = 5199;

/** URL base del backend (se usa directamente para sembrar datos y crear usuarios por API). */
export const BACKEND_URL = `http://localhost:${BACKEND_PORT}`;

/** URL base del frontend (la que abre el navegador). */
export const FRONTEND_URL = `http://localhost:${FRONTEND_PORT}`;

/**
 * Administrador del backend efímero. NO es una credencial real: la base de datos
 * es H2 en memoria y desaparece al terminar la suite. Se puede sobrescribir con
 * `E2E_ADMIN_PASSWORD` si se prefiere no usar el valor por defecto.
 *
 * El backend exige al menos 12 caracteres en la contraseña del administrador.
 */
export const ADMIN_EMAIL = 'admin-e2e@streambox.local';
export const ADMIN_USERNAME = 'admin-e2e';
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'e2e-contrasena-admin-1';

/** Secreto JWT de la suite (≥ 32 caracteres, lo exige el backend). No es un secreto real: la BD y los tokens son efímeros. */
export const JWT_SECRET = 'secreto-solo-para-e2e-de-streambox-0123456789';

/**
 * Fallos de login que bloquean una cuenta en el backend de la suite
 * (`LoginAttemptService`). Igual que en producción (5 fallos en 15 minutos);
 * el límite por IP, en cambio, se sube mucho en este backend efímero porque todos
 * los tests salen de 127.0.0.1. Permite provocar un 429 real sin tocar producción.
 */
export const LOCKOUT_MAX_FAILURES = 5;

/** Contraseña válida (8-100 caracteres) con la que se registran los usuarios de los tests. */
export const USER_PASSWORD = 'Contrasena-e2e-1';
