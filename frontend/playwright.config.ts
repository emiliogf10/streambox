import { defineConfig, devices } from '@playwright/test';
import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  BACKEND_PORT,
  BACKEND_URL,
  FRONTEND_PORT,
  FRONTEND_URL,
  JWT_SECRET,
  LOCKOUT_MAX_FAILURES,
} from './e2e/support/config';

/**
 * Configuración de la suite E2E (Playwright, navegador real + backend real).
 *
 * **Qué arranca.** Dos procesos, ambos EFÍMEROS y en puertos propios para no
 * chocar con la aplicación que el desarrollador tenga en marcha (backend 8080
 * con su PostgreSQL y Vite 5173):
 *  1. El backend Spring Boot (`spring-boot:run`) en el puerto {@link BACKEND_PORT},
 *     con base de datos H2 EN MEMORIA y un perfil propio (`e2e`). Se usa
 *     `useTestClasspath` solo porque H2 es una dependencia de scope `test`; OJO:
 *     eso añade las dependencias de test pero NO `target/test-classes`, así que
 *     `application-test.properties` NO se carga. Por eso toda la configuración
 *     va explícita en variables de entorno (más abajo) y no depende de ese archivo.
 *     Por lo mismo, lo que este backend necesita de H2 (la V5 de `db/vendor/h2` y
 *     `H2SearchFunctions`) vive en `src/main`, no en `src/test`
 *     (lo vigila `VendorMigrationsLocationTest`).
 *  2. Vite en el puerto {@link FRONTEND_PORT}, con su proxy `/api` apuntando al
 *     backend anterior (variable `VITE_API_PROXY_TARGET`, ver `vite.config.ts`).
 *
 * **Configuración del backend por variables de entorno** (y no por argumentos
 * `-Dspring-boot.run.arguments`, cuyo entrecomillado es frágil en Windows). Spring
 * las lee con prioridad sobre los `.properties`:
 *  - `SPRING_DATASOURCE_*`: fuerza H2 en memoria aunque exista un
 *    `application-local.properties`; es la garantía de que esta suite NUNCA
 *    toca PostgreSQL.
 *  - `JWT_SECRET`: secreto propio de la suite (no el del desarrollador).
 *  - `ADMIN_*`: el administrador con el que se siembra el catálogo.
 *  - `STREAMBOX_SECURITY_RATE_LIMIT_*`: límites por IP muy altos (todos los
 *    tests salen de 127.0.0.1) y bloqueo de cuenta en 5 fallos para poder
 *    provocar un 429 real. Solo afecta a este proceso efímero, no a producción.
 *  - `STREAMBOX_EMAIL_*` y `STREAMBOX_PUBLIC_URL`: sin límite silencioso de
 *    reenvíos y con los enlaces de los correos apuntando a este Vite. Con el
 *    perfil `e2e` los correos no salen: van a una bandeja en memoria que los
 *    tests leen como administrador (`GET /api/admin/e2e/emails`, ver
 *    `e2e/support/api.ts`).
 *
 * `reuseExistingServer: false` (por defecto, ver `reuseServers`): si los puertos ya están ocupados la suite falla
 * en lugar de "engancharse" a un servidor ajeno (y sembrar datos en él).
 */
const isWindows = process.platform === 'win32';

/**
 * Atajo SOLO para iterar mientras se escriben tests: con `E2E_REUSE_SERVERS=1` se
 * reutilizan los servidores de ESTA suite si ya están levantados en 8099/5199 (el
 * arranque de Spring tarda). Por defecto es `false`, que es lo seguro: nunca
 * engancharse a un servidor que no se ha arrancado aquí.
 */
const reuseServers = process.env.E2E_REUSE_SERVERS === '1';

/**
 * Specs que MODIFICAN el catálogo compartido (crean y borran películas y series desde el panel).
 *
 * Todos los tests usan el mismo catálogo sembrado y muchos comprueban cosas que dependen de él: la
 * película del banner es la más reciente, la portada dice "Mostrando 25 de 25"... Mientras existe una
 * película creada por un test, ELLA pasa a ser la más reciente y esos tests fallarían al azar según el
 * orden en que corran. Por eso estos specs van en un proyecto propio (`catalogo-mutable`) que depende del
 * principal: Playwright no lo empieza hasta que el resto ha terminado.
 *
 * Contrapartida conocida: si falla algún test del proyecto principal, este se omite. Para ejecutarlo
 * solo: `npx playwright test admin-peliculas --no-deps` (o `admin-series`).
 *
 * `admin-series.spec.ts` va aquí porque crea, edita y borra series y episodios: una serie con episodios es
 * visible para todos (banner de `/series`, fila «Series» de la portada, buscador) y cambiaría lo que comprueban
 * los demás tests.
 */
const CATALOG_MUTATING_SPECS = /admin-(peliculas|series)\.spec\.ts$/;

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  outputDir: './test-results',
  globalSetup: './e2e/support/global-setup.ts',

  // Cada test usa usuarios con email único, así que pueden ejecutarse en paralelo sin pisarse.
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  // Sin reintentos: un test que solo pasa a la segunda es un test inestable y debe verse.
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 30_000,
  expect: { timeout: 7_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],

  use: {
    baseURL: FRONTEND_URL,
    locale: 'es-ES',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, testIgnore: CATALOG_MUTATING_SPECS },
    {
      name: 'catalogo-mutable',
      use: { ...devices['Desktop Chrome'] },
      testMatch: CATALOG_MUTATING_SPECS,
      dependencies: ['chromium'],
    },
  ],

  webServer: [
    {
      name: 'backend',
      command: isWindows
        ? '.\\mvnw.cmd -B spring-boot:run -Dspring-boot.run.useTestClasspath=true -Dspring-boot.run.profiles=e2e'
        : './mvnw -B spring-boot:run -Dspring-boot.run.useTestClasspath=true -Dspring-boot.run.profiles=e2e',
      cwd: '../streambox',
      // `/actuator/health` es público; responde 200 cuando la aplicación ha arrancado.
      url: `${BACKEND_URL}/actuator/health`,
      // El primer arranque compila el backend y descarga dependencias: puede tardar minutos.
      timeout: 300_000,
      reuseExistingServer: reuseServers,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        ...(process.env as Record<string, string>),
        SPRING_PROFILES_ACTIVE: 'e2e',
        SERVER_PORT: String(BACKEND_PORT),
        SPRING_DATASOURCE_URL: 'jdbc:h2:mem:streambox_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE',
        SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'org.h2.Driver',
        SPRING_DATASOURCE_USERNAME: 'sa',
        SPRING_DATASOURCE_PASSWORD: '',
        JWT_SECRET: JWT_SECRET,
        JWT_ACCESS_TOKEN_TTL: '15m',
        // El backend E2E corre en http: sin esto la cookie de sesión llevaría `Secure` y
        // el navegador no la guardaría. (`streambox.auth.cookie.secure`; en producción es `true`.)
        STREAMBOX_AUTH_COOKIE_SECURE: 'false',
        ADMIN_EMAIL,
        ADMIN_USERNAME,
        ADMIN_PASSWORD,
        STREAMBOX_SECURITY_RATE_LIMIT_LOGIN_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_REGISTER_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_REFRESH_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_LOCKOUT_MAX_FAILURES: String(LOCKOUT_MAX_FAILURES),
        // Verificación del correo: todos los tests confirman su cuenta (y algunos piden reenvíos) desde
        // 127.0.0.1, así que los límites por IP de confirmar y reenviar se suben como los demás...
        STREAMBOX_SECURITY_RATE_LIMIT_EMAIL_VERIFICATION_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_EMAIL_VERIFICATION_RESEND_MAX_REQUESTS: '100000',
        // ...y el límite SILENCIOSO por cuenta (60 s entre correos, 3 por hora) se quita: con él, un reenvío
        // justo después del registro respondería 202 sin enviar nada y el test esperaría un correo que no llega.
        STREAMBOX_EMAIL_RESEND_MIN_INTERVAL: '0s',
        STREAMBOX_EMAIL_RESEND_MAX_PER_WINDOW: '1000',
        // «He olvidado mi contraseña» (`e2e/password-reset.spec.ts`): pedir el enlace y cambiar la contraseña
        // también tienen su límite por IP. Su límite silencioso por cuenta debe quedar quitado como el del
        // reenvío de arriba: el test del enlace sustituido pide dos seguidos.
        STREAMBOX_SECURITY_RATE_LIMIT_PASSWORD_RESET_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_PASSWORD_RESET_CONFIRM_MAX_REQUESTS: '100000',
        // Origen de los enlaces de los correos (`/verificar-email#token=…`): el Vite de ESTA suite, para que
        // el test pueda abrir el enlace tal cual llega al buzón.
        STREAMBOX_PUBLIC_URL: FRONTEND_URL,
        // Sin SMTP: con el perfil `e2e` el arranque falla a propósito si hay `spring.mail.host` (los correos
        // deben quedarse en la bandeja en memoria). Vacío aquí, por si la terminal heredara uno.
        SPRING_MAIL_HOST: '',
      },
    },
    {
      name: 'frontend',
      command: `npx vite --port ${FRONTEND_PORT} --strictPort`,
      url: FRONTEND_URL,
      timeout: 60_000,
      reuseExistingServer: reuseServers,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        ...(process.env as Record<string, string>),
        VITE_API_PROXY_TARGET: BACKEND_URL,
      },
    },
  ],
});
