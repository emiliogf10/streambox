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
 * Specs que MODIFICAN el catálogo compartido (crean y borran películas desde el panel).
 *
 * Todos los tests usan el mismo catálogo sembrado y muchos comprueban cosas que dependen de él: la
 * película del banner es la más reciente, la portada dice "Mostrando 25 de 25"... Mientras existe una
 * película creada por un test, ELLA pasa a ser la más reciente y esos tests fallarían al azar según el
 * orden en que corran. Por eso estos specs van en un proyecto propio (`catalogo-mutable`) que depende del
 * principal: Playwright no lo empieza hasta que el resto ha terminado.
 *
 * Contrapartida conocida: si falla algún test del proyecto principal, este se omite. Para ejecutarlo
 * solo: `npx playwright test admin-peliculas --no-deps`.
 */
const CATALOG_MUTATING_SPECS = /admin-peliculas\.spec\.ts$/;

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
        JWT_EXPIRATION_HOURS: '1',
        ADMIN_EMAIL,
        ADMIN_USERNAME,
        ADMIN_PASSWORD,
        STREAMBOX_SECURITY_RATE_LIMIT_LOGIN_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_REGISTER_MAX_REQUESTS: '100000',
        STREAMBOX_SECURITY_RATE_LIMIT_LOCKOUT_MAX_FAILURES: String(LOCKOUT_MAX_FAILURES),
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
