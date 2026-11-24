/**
 * `test` extendido con lo que necesitan casi todas las pruebas:
 *
 *  - `user`: una cuenta recién registrada (email y usuario únicos) y con el
 *    correo YA CONFIRMADO (el enlace se lee de la bandeja del backend E2E, ver
 *    `createUserWithToken`), con su JWT (el valor de la cookie de sesión que fijó
 *    el login). Sin confirmar, el login daría 401 (como una contraseña incorrecta).
 *    Cada test tiene la suya, por eso son independientes y paralelizables. Con la pregunta de bienvenida
 *    («¿qué géneros te gustan?») ya CERRADA (ver `createUserWithToken`): si no, al abrir la app con su
 *    sesión iría a `/bienvenida`. Quien registre usuarios a mano y entre con el formulario usa
 *    {@link skipGenresQuestionIfAsked}.
 *  - `signIn`: función que deja la sesión abierta en el navegador sin pasar por
 *    el formulario (siembra en el contexto del navegador la cookie `streambox_token`,
 *    HttpOnly como la real, que es lo que haría el login). Solo esa: sin el refresh
 *    token (`streambox_refresh`), así que esa sesión NO se renueva (un 401 es sesión
 *    caducada). Dura 15 minutos, de sobra para un test.
 *    Los tests que prueban el formulario de login o la renovación NO la usan.
 *  - `failOnPageErrors` (automático): una excepción JavaScript no capturada en la
 *    página (un fallo de React, un `undefined`...) hace fallar el test aunque la
 *    UI "parezca" funcionar.
 */
import { test as base, expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { SESSION_COOKIE, createUserWithToken } from './api';
import type { TestUser } from './api';

interface Fixtures {
  user: TestUser & { token: string };
  signIn: (user: { token: string }) => Promise<void>;
  failOnPageErrors: void;
}

export const test = base.extend<Fixtures>({
  user: async ({ request }, provide) => {
    await provide(await createUserWithToken(request));
  },

  signIn: async ({ page }, provide) => {
    await provide(async ({ token }) => {
      // La sesión vive en una cookie HttpOnly, inaccesible desde la página: se siembra
      // desde el contexto del navegador con los mismos atributos que fija el backend.
      // Va sin `Secure` porque el backend E2E corre en http (ver playwright.config.ts).
      await page.context().addCookies([
        { name: SESSION_COOKIE, value: token, domain: 'localhost', path: '/api', httpOnly: true, sameSite: 'Strict' },
      ]);
    });
  },

  failOnPageErrors: [
    async ({ page }, provide) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await provide();
      expect(errors, 'Excepciones JavaScript no capturadas en la página').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

/**
 * Cookie de sesión del contexto del navegador, o `undefined` si no hay sesión.
 * Se lee con la API de Playwright porque, al ser HttpOnly, la página no puede
 * verla (justo lo que se quiere: el JavaScript de la app no tiene el token).
 */
export async function sessionCookie(page: Page) {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === SESSION_COOKIE);
}

/**
 * Espera a que la pantalla de login esté PINTADA (su `<h1>`, `LoginPage`). Hay que llamarla antes de escribir en el
 * formulario si se llega a `/login` con un clic desde otra pantalla.
 *
 * `toHaveURL` no lo garantiza: React Router cambia la ruta dentro de `startTransition`, así que `pushState` actualiza
 * la URL al instante y la ruta nueva se pinta después. En ese hueco sigue en pantalla la página anterior, y
 * `getByLabel('Correo electrónico')` puede resolverse en un campo con la misma etiqueta que ya no interesa (el del
 * reenvío de `/verificar-email`). Así falló en el CI: el correo se escribió allí, el login se quedó sin él y su
 * validación impidió entrar. Una persona no puede escribir en ese hueco: es una carrera del test, no un fallo de la app.
 * El `<h1>` solo existe en el login, y React pinta la ruta nueva de una vez: visto él, ya no queda nada de la anterior.
 */
export async function waitForLoginPage(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name: 'Bienvenido de nuevo' })).toBeVisible();
}

/**
 * En el móvil (< 768 px), deja la barra superior a la vista y QUIETA antes de pulsar algo en ella, como haría una
 * persona: sube un poco la página con la rueda y espera a que la barra haya terminado de entrar.
 *
 * **Por qué hace falta.** Al bajar, la barra se aparta (`useHideOnScroll`, `-translate-y-full`) y vuelve al subir
 * con un deslizamiento de 200 ms. Si se pulsa un enlace suyo estando apartada, Playwright desplaza la página para
 * alcanzarlo; ese desplazamiento es hacia arriba, así que la barra empieza a entrar, y Playwright puede darla por
 * «estable» y pulsar a mitad del deslizamiento: el clic cae donde estaba el enlace un instante antes (en la traza,
 * el enlace «Inicio» con 15 px aún fuera de la pantalla) y no navega. Una persona no pulsa algo que no ve: primero
 * sube para que aparezca. No es un fallo de la app: durante esos 200 ms el enlace se mueve de verdad.
 *
 * Espera, por este orden: que la barra ya no esté marcada como apartada (`data-navbar-away`), que no le quede
 * ninguna animación en curso (las transiciones CSS salen en `getAnimations()`), que esté en su sitio (arriba del
 * todo) y que la página haya dejado de desplazarse (la rueda puede ser suave): así, lo que se pulse después está
 * donde Playwright lo mide.
 *
 * @param scrollUp cuánto subir con la rueda (px); poco, para que la página siga lejos de arriba
 */
export async function revealMobileNavbar(page: Page, scrollUp = 150): Promise<void> {
  const banner = page.getByRole('banner');
  await page.mouse.wheel(0, -scrollUp);
  await expect(banner).not.toHaveAttribute('data-navbar-away');
  await expect
    .poll(
      () =>
        banner.evaluate((element) => ({
          animating: element.getAnimations().length > 0,
          inPlace: element.getBoundingClientRect().top >= -0.5,
        })),
      { message: 'la barra ha vuelto y está quieta' },
    )
    .toEqual({ animating: false, inPlace: true });
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            new Promise<boolean>((resolve) => {
              const y = window.scrollY;
              requestAnimationFrame(() => requestAnimationFrame(() => resolve(window.scrollY === y)));
            }),
        ),
      { message: 'la página ha dejado de desplazarse' },
    )
    .toBe(true);
}

/**
 * Espera a que la tabla del panel enseñe los resultados de la búsqueda `term` (`useAdminSearchList`). Hay que llamarla
 * antes de actuar sobre una fila tras buscar.
 *
 * `toHaveURL` tampoco basta aquí, y la fila buscada tampoco es señal: la URL cambia antes de pintarse y, mientras
 * llega la respuesta, sigue la tabla anterior (`keepPreviousData`), que suele incluir ya esa fila. Pulsar en ella es
 * actuar sobre una página a medio cargar. Son dos pasos porque cada uno cubre un hueco: el `<caption>` cambia con la URL
 * (dice que la pantalla ya es la de esta búsqueda, aún con los datos viejos) y `aria-busy` se va al llegar los datos.
 */
export async function waitForAdminSearchResults(page: Page, term: string): Promise<void> {
  const table = page.getByRole('table', { name: new RegExp(`^Resultados para «${escapeRegExp(term)}»`) });
  await expect(table).toBeVisible();
  await expect(table).not.toHaveAttribute('aria-busy', 'true');
}

/** `<h1>` de la pregunta de bienvenida (`/bienvenida`, `WelcomePage`). */
export const WELCOME_TITLE = 'Cuéntanos qué te gusta';

/**
 * Tras enviar el formulario de login: si la cuenta aún tiene la pregunta de bienvenida pendiente (toda cuenta
 * nueva que nunca la contestó), pulsa «Ahora no» y espera a salir de `/bienvenida`; si no, no hace nada.
 *
 * Es determinista: a quien tiene la pregunta pendiente la guarda le lleva a `/bienvenida` ANTES de pintar la
 * portada (su `<h1>` «Películas y series» nunca llega a existir), así que lo primero que aparezca de los dos
 * dice en qué caso se está. Para los tests que entran con una cuenta registrada a mano y no tratan de la
 * bienvenida (la prueban `favorite-genres.spec.ts`).
 */
export async function skipGenresQuestionIfAsked(page: Page): Promise<void> {
  const welcome = page.getByRole('heading', { level: 1, name: WELCOME_TITLE });
  const home = page.getByRole('heading', { level: 1, name: 'Películas y series' });
  await expect(welcome.or(home)).toBeAttached();
  if (await welcome.isVisible()) {
    await page.getByRole('button', { name: 'Ahora no' }).click();
    await expect(page).not.toHaveURL(/\/bienvenida/);
  }
}

/** Ruta de las recomendaciones de la portada (`useRecommendations`). */
const RECOMMENDATIONS_PATH = '/api/users/me/recommendations';

/**
 * Abre la portada, espera a la respuesta del servidor a las recomendaciones y devuelve su `basis` (en qué se
 * basan: `NONE`, `GENRES`, `LIKES` o `GENRES_AND_LIKES`). La usan `likes.spec.ts` y `favorite-genres.spec.ts`.
 *
 * **Por qué se espera a la respuesta.** No vale esperar a que se vaya un esqueleto: a quien no tiene «Me gusta»
 * ni géneros favoritos no se le pinta ninguno (ver `RecommendedRows`), así que un «no hay filas» podría
 * comprobarse antes de que llegaran. Se escucha ANTES de navegar porque la portada la pide en su primer render,
 * en paralelo con el catálogo.
 *
 * **Por qué el cuerpo se lee DENTRO del predicado.** La primera petición a veces se CANCELA a medio camino
 * (visto en la traza: 200 y luego `net::ERR_ABORTED`). La lanza la precarga (`usePrefetchRecommendations`, sin
 * observadores); cuando se montan las filas, el `StrictMode` de React en desarrollo (el E2E usa Vite en modo
 * desarrollo) monta, desmonta y vuelve a montar su `useQuery`; al quedarse sin observadores con la petición en
 * vuelo y el `signal` ya usado, TanStack Query la aborta y, al volver a montarse, la repite
 * (`Query.removeObserver`). Si las cabeceras ya habían llegado, Playwright entrega esa respuesta, pero su cuerpo
 * nunca terminó de recibirse: leerlo después fallaba con «Network.getResponseBody: No data found for resource»
 * (2 de cada 3 veces). Ahora una respuesta cuyo cuerpo no se puede leer no cuenta y se espera a la repetición,
 * que es la que pinta la interfaz.
 */
export async function openHomeAndWaitForRecommendations(page: Page): Promise<string> {
  let basis: string | undefined;
  const answered = page.waitForResponse(async (response) => {
    if (response.request().method() !== 'GET' || new URL(response.url()).pathname !== RECOMMENDATIONS_PATH) {
      return false;
    }
    try {
      basis = ((await response.json()) as { basis?: string }).basis;
      return true;
    } catch {
      // Petición cancelada por la aplicación: su cuerpo no existe. La app la repite; se espera a esa.
      return false;
    }
  });
  await page.goto('/');
  const response = await answered;
  expect(response.status(), 'Las recomendaciones deben responder 200').toBe(200);
  await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
  if (basis === undefined) throw new Error('La respuesta de las recomendaciones no trae `basis`');
  return basis;
}

/** Escapa un texto para usarlo literalmente dentro de una expresión regular. */
export function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Tarjeta de una película dentro de `scope`: un ENLACE a su página (`/peliculas/:id`;
 * antes era un botón que abría un diálogo). Su nombre accesible es
 * «Título 2014 · 2 h 1 min»; se ancla al inicio y exige el año para que
 * «Blade Runner» no case con «Blade Runner 2049».
 */
export function movieCard(scope: Page | Locator, title: string): Locator {
  return scope.getByRole('link', { name: new RegExp(`^${escapeRegExp(title)} \\d{4} ·`) });
}

/**
 * Aviso de error de un formulario (`FormAlert`), en cualquiera de sus dos modos:
 * el que se lleva el foco (lo normal: un grupo enfocable, `tabindex="-1"`, sin
 * región viva, para que se lea una sola vez) y el que acompaña a errores de
 * campo (`role="alert"`, con el foco en el primer campo con error). Hay que
 * filtrar por texto porque la zona de avisos (`ToastViewport`) mantiene SIEMPRE
 * una región `role="alert"` vacía en el DOM (una región viva debe existir antes
 * de recibir contenido para que los lectores de pantalla lo anuncien).
 */
export function formAlert(page: Page): Locator {
  return page
    .getByRole('alert')
    .or(page.locator('[role="group"][tabindex="-1"]'))
    .filter({ hasText: /\S/ });
}

/**
 * Espera a que el formulario de película del panel esté COMPLETO, con las
 * casillas de géneros: la señal visible que esperaría una persona antes de
 * pulsar «Crear película».
 *
 * Por qué hace falta: los géneros llegan en una petición aparte
 * (`GET /api/genres`) y, al cambiar la línea «Cargando géneros...» por las
 * casillas, todo lo que hay debajo (la vista previa y los botones) baja de golpe
 * (~130 px a 375 px). Si el clic coincide con ese salto, el botón ya no está bajo
 * el puntero y el formulario no se envía: así fallaba a veces el test responsive
 * a 375 px. Las esperas automáticas de Playwright no lo evitan: comprueban que el
 * botón está quieto ANTES de empezar el clic y miran qué hay bajo el puntero
 * solo en el PRIMER evento (`mousedown`). Si la página se mueve antes del
 * `mouseup`, el `click` va a parar a otro elemento.
 */
export async function waitForMovieForm(page: Page): Promise<void> {
  await expect(page.getByRole('group', { name: 'Géneros' }).getByRole('checkbox').first()).toBeVisible();
}
