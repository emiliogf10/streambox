/**
 * Revisión de QA del lote de errores de teclado y foco (revisión del frontend de 2026-11): cada test reproduce, con el
 * teclado o el puntero como lo haría una persona, un fallo que existía antes del arreglo y que los tests anteriores no
 * veían. Se comprobó que **todos fallan con el código anterior** (un `git worktree` del commit previo) y pasan con el
 * arreglo.
 *
 * Qué protegen:
 *  1. **«Mi lista» no suelta el foco.** Un botón que pasa a `disabled` mientras tiene el foco lo pierde (el navegador lo
 *     manda al `<body>`) y quien usa el teclado vuelve a empezar la página. Ahora el botón en curso usa
 *     `aria-disabled` + `aria-busy` (`Button`, prop `pending`). Ojo: `toBeDisabled()` de Playwright también da por
 *     desactivado un `aria-disabled`, así que NO sirve para distinguirlo: se comprueba que el atributo `disabled` no está.
 *  2. **Login con contraseña errónea pulsando el botón** (clic e Intro): mientras se espera, el botón conserva el foco;
 *     al llegar el 401, el foco va al aviso (decisión del autor, revisión de 2026-11), que aparece una sola vez y no es
 *     una región viva (se leería dos veces), y el siguiente Tab lleva al correo para reintentar.
 *  3. **Tráiler con un reproductor que se enfoca solo al cargar** (lo hace el de YouTube con un vídeo que no existe o no
 *     se puede incrustar): el foco vuelve a «Cerrar el tráiler» y Escape cierra. El E2E `trailer-keyboard.spec.ts` no lo
 *     veía porque su reproductor simulado no robaba el foco.
 *  4. **Diálogo de episodio con cambios sin guardar:** Escape o un clic fuera no tiran lo escrito; preguntan, con el
 *     foco en «Seguir editando».
 *  5. **Buscador:** el primer Escape cierra la lista y conserva el texto (patrón «combobox» de WAI-ARIA); el segundo lo
 *     vacía.
 *  6. **Móvil (375 px):** los campos de formulario a 16 px (con menos, Safari de iOS amplía la página al enfocarlos), el
 *     autocompletado del correo como `username` y las zonas táctiles de 44 px (WCAG 2.5.5).
 *
 * Ninguno modifica el catálogo (el diálogo de episodio nunca se guarda), por eso van en el proyecto principal.
 */
import type { Locator, Page } from '@playwright/test';
import { findSeriesIdAsAdmin, loginAdmin } from './support/api';
import { EMPTY_SERIES, HERO_TITLE, MOVIES } from './support/catalog';
import { expect, formAlert, movieCard, test, waitForLoginPage } from './support/fixtures';

/** Petición retenida por {@link holdRequests}: `arrived` se cumple al llegar y `release()` la deja seguir. */
interface HeldRequest {
  arrived: Promise<void>;
  release: () => void;
}

/**
 * Retiene en el navegador las peticiones `method` a `url` hasta llamar a `release()`. Sirve para mirar el botón
 * MIENTRAS su petición está en curso, que con el backend local dura unos milisegundos y no se podría observar.
 */
async function holdRequests(page: Page, url: string, method: string): Promise<HeldRequest> {
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  let arrive!: () => void;
  const arrived = new Promise<void>((resolve) => {
    arrive = resolve;
  });
  await page.route(url, async (route) => {
    if (route.request().method() !== method) return route.fallback();
    arrive();
    await released;
    await route.continue();
  });
  return { arrived, release };
}

/**
 * Comprueba que `button` está «en curso» SIN haber soltado el foco: anunciado como no disponible y ocupado, pero sin el
 * atributo `disabled` (que es justo lo que le quitaba el foco).
 */
async function expectPendingButKeepsFocus(button: Locator): Promise<void> {
  // Primero lo que nota la persona (el foco) y la causa (`disabled`); después cómo se anuncia el estado.
  await expect(button, 'el botón en curso debe conservar el foco').toBeFocused();
  await expect(button, 'un botón en curso no debe llevar `disabled`: soltaría el foco').not.toHaveAttribute('disabled');
  await expect(button).toHaveAttribute('aria-disabled', 'true');
  await expect(button).toHaveAttribute('aria-busy', 'true');
}

test.describe('«Mi lista» con el teclado: el foco no se pierde mientras se guarda', () => {
  const FILM = MOVIES.find((movie) => movie.title === 'Interstellar')!;

  /**
   * Pulsa Intro en «Mi lista» de `scope`, retiene el `POST`, comprueba el estado en curso y, al responder el servidor,
   * que el foco sigue en el botón. Termina con un SEGUNDO Intro, que solo funciona si el foco no se ha movido: quita el
   * título de la lista.
   */
  async function toggleWithEnter(page: Page, scope: Locator, title: string): Promise<void> {
    const button = scope.getByRole('button', { name: /^(En mi|Mi) lista/ });
    await expect(button).toHaveAccessibleName(`Mi lista — ${title}`);
    const held = await holdRequests(page, '**/api/users/me/favorites/**', 'POST');
    try {
      await button.focus();
      await page.keyboard.press('Enter');
      await held.arrived;
      await expectPendingButKeepsFocus(button);
    } finally {
      held.release();
    }

    await expect(page.getByText(`«${title}» se ha añadido a tu lista.`)).toBeVisible();
    await expect(button).toHaveAccessibleName(`En mi lista — ${title}`);
    await expect(button).not.toHaveAttribute('aria-disabled');
    await expect(button).toBeFocused();

    // Un momento después (otra pulsación): sigue ahí y el mismo Intro deshace el cambio.
    await page.keyboard.press('Enter');
    await expect(page.getByText(`«${title}» se ha quitado de tu lista.`)).toBeVisible();
    await expect(button).toHaveAccessibleName(`Mi lista — ${title}`);
    await expect(button).toBeFocused();
  }

  test('en la página de una película', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), FILM.title).click();
    const header = page.getByRole('region', { name: FILM.title });
    await expect(header.getByRole('heading', { level: 1, name: FILM.title })).toBeVisible();

    await toggleWithEnter(page, header, FILM.title);
  });

  test('en el banner de la portada', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/');
    const banner = page.getByRole('region', { name: HERO_TITLE });
    await expect(banner.getByRole('heading', { name: HERO_TITLE })).toBeVisible();

    await toggleWithEnter(page, banner, HERO_TITLE);
  });
});

test.describe('Login con contraseña errónea enviado desde el botón: el foco no se pierde y acaba en el aviso', () => {
  for (const how of ['clic', 'Intro'] as const) {
    test(`con ${how} sobre el botón`, async ({ page, user }) => {
      await page.goto('/login');
      await waitForLoginPage(page);
      await page.getByLabel('Correo electrónico').fill(user.email);
      await page.getByLabel('Contraseña', { exact: true }).fill('No-Es-La-Contrasena-99');
      const button = page.getByRole('button', { name: /^(Iniciar sesión|Entrando\.\.\.)$/ });

      const held = await holdRequests(page, '**/api/auth/login', 'POST');
      try {
        if (how === 'clic') {
          await button.click();
        } else {
          await button.focus();
          await page.keyboard.press('Enter');
        }
        await held.arrived;
        await expect(button).toHaveText('Entrando...');
        await expectPendingButKeepsFocus(button);
      } finally {
        held.release();
      }

      await expect(formAlert(page)).toContainText('Correo o contraseña incorrectos.');
      // Un solo aviso (antes cada campo tenía además su propia región viva).
      await expect(formAlert(page)).toHaveCount(1);
      await expect(button).toHaveText('Iniciar sesión');
      await expect(button).not.toHaveAttribute('aria-disabled');
      // El foco va al aviso: un grupo nombrado por su texto, sin región viva (con foco Y `role="alert"` se oiría dos veces).
      const alert = page.getByRole('group', { name: /Correo o contraseña incorrectos\./ });
      await expect(alert).toBeFocused();
      await expect(alert).not.toHaveAttribute('role', 'alert');
      await expect(alert.locator('[role="alert"], [role="status"], [aria-live]')).toHaveCount(0);
      // Y desde ahí, Tab lleva al correo: reintentar no exige buscar el formulario.
      await page.keyboard.press('Tab');
      await expect(page.getByLabel('Correo electrónico')).toBeFocused();
    });
  }
});

test.describe('Tráiler con un reproductor que se queda el foco al cargar', () => {
  /**
   * Script que el reproductor simulado carga a mitad de su documento. El test **retiene** su petición: mientras no la
   * suelta, el documento del reproductor sigue a medio analizar (`readyState === 'loading'`) y su `load` no ha llegado.
   * Así el test decide el momento exacto del robo («mientras carga» de verdad) en vez de competir con la red.
   */
  const HOLD_URL = 'https://www.youtube-nocookie.com/e2e-retener-carga.js';

  /**
   * Reproductor simulado (servido en el origen real de YouTube con `page.route`, así que es de OTRO origen, como el de
   * verdad) que se enfoca solo con `robarFoco()`. Deja constancia en `#estado`:
   * - «foco tomado» si su botón recibe el evento `focus` (el robo ha ocurrido de verdad);
   * - «foco bloqueado por el navegador» si `focus()` no movió el foco. Sin esto, un robo bloqueado solo se notaba como
   *   «no aparece "foco tomado"» tras agotar la espera, sin decir por qué (es lo que pasó en el CI, ver más abajo).
   */
  const STEAL_SCRIPT =
    "const b=document.querySelector('button');const estado=document.getElementById('estado');" +
    "b.addEventListener('focus',()=>{estado.textContent='foco tomado';});" +
    "function robarFoco(){b.focus();if(document.activeElement!==b)estado.textContent='foco bloqueado por el navegador';}";
  const player = (steal: string) =>
    `<!doctype html><title>Reproductor</title><button type="button">Reproducir</button><p id="estado"></p>` +
    `<script>${STEAL_SCRIPT}</script><script src="${HOLD_URL}"></script><script>${steal}</script>`;
  /**
   * Uno se enfoca mientras se analiza el documento (antes de su `load`, como el de YouTube con un vídeo que no se puede
   * ver: el foco entraba unos 100 ms antes del `load`) y otro un poco después de cargar.
   */
  const PLAYERS = {
    'mientras carga': player('robarFoco();'),
    'justo después de cargar': player("addEventListener('load',()=>setTimeout(robarFoco,300));"),
  } as const;

  /**
   * Abre el tráiler de Tenet con el teclado sirviendo `html` como reproductor y lo deja robar el foco. Termina cuando el
   * robo ha ocurrido (o falla diciendo que el navegador lo bloqueó).
   *
   * **Por qué se da activación de usuario al marco.** Desde Chromium 158 (Playwright 1.65) un `<iframe>` con `sandbox`
   * de otro origen —el de StreamBox lo lleva— **no puede enfocarse sin activación de usuario**: `focus()` no hace nada y
   * la consola dice «Permissions policy violation: focus-without-user-activation is not allowed in this document».
   * Comprobado con cada atributo por separado: lo provoca `sandbox`, no `allow`; y da igual el momento (al analizar, en
   * el `load` o 1,5 s después) y que el diálogo se abra con el teclado (la activación de la página no pasa al marco).
   * Por eso fallaba en el CI (que instala la 1.65 del `package-lock.json`) y no en un equipo con la 1.64 aún instalada.
   * Y por eso el caso «justo después de cargar» seguía pasando: por casualidad, porque cada evaluación de Playwright en
   * un marco (la espera de `getByText`) lleva el indicador de gesto de usuario y le daba esa activación antes de los
   * 300 ms; el caso «mientras carga» se enfoca antes de que Playwright toque el marco.
   *
   * Aquí esa activación se da **a propósito y antes del robo**, con el documento todavía a medio analizar, y se comprueba
   * que la tiene. Equivale a un navegador que no aplica ese bloqueo (otros navegadores, Chrome anterior) o a un
   * reproductor que ya la tiene: el test sigue demostrando que, si el reproductor se queda el foco por su cuenta, el
   * guardia de `TrailerDialog` lo devuelve. Nada de esto pulsa Tab ni toca la página de StreamBox, así que para el
   * guardia sigue siendo un robo, no un cambio de foco del usuario.
   */
  async function openTrailerAndLetPlayerSteal(page: Page, html: string) {
    let releasePlayer!: () => void;
    const playerReleased = new Promise<void>((resolve) => {
      releasePlayer = resolve;
    });
    await page.route(HOLD_URL, async (route) => {
      await playerReleased;
      await route.fulfill({ contentType: 'text/javascript', body: '' });
    });
    await page.route('https://www.youtube-nocookie.com/embed/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: html }),
    );

    const tenet = MOVIES.find((movie) => movie.title === 'Tenet')!;
    await page.goto(`/peliculas?anio=${tenet.releaseYear}`);
    await movieCard(page.getByRole('region', { name: 'Resultados' }), 'Tenet').click();
    const open = page.getByRole('region', { name: 'Tenet' }).getByRole('button', { name: 'Ver tráiler de Tenet' });
    await expect(open).toBeVisible();

    await open.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Tráiler de Tenet' });
    await expect(dialog).toBeVisible();
    const close = dialog.getByRole('button', { name: 'Cerrar el tráiler' });
    const frame = page.frameLocator('iframe[title="Tráiler de Tenet (YouTube)"]');
    try {
      await expect(close, 'antes del robo, el foco inicial del diálogo').toBeFocused();
      await expect(frame.getByRole('button', { name: 'Reproducir' })).toBeAttached();
      const before = await frame
        .locator('body')
        .evaluate(() => ({ activation: navigator.userActivation.isActive, readyState: document.readyState }));
      expect(before, 'el reproductor sigue cargando y ya puede enfocarse (tiene activación de usuario)').toEqual({
        activation: true,
        readyState: 'loading',
      });
    } finally {
      releasePlayer();
    }
    // El reproductor se ha quedado el foco (al menos un instante). Si el navegador lo bloquea, el mensaje lo dice.
    await expect(frame.locator('#estado'), 'el reproductor simulado debe haber robado el foco').toHaveText('foco tomado');
    return { open, dialog, close, frame };
  }

  for (const [when, html] of Object.entries(PLAYERS)) {
    test(`se enfoca ${when}: el foco vuelve a «Cerrar el tráiler», Escape cierra y vuelve a «Ver tráiler»`, async ({
      page,
      user,
      signIn,
    }) => {
      await signIn(user);
      const { open, close } = await openTrailerAndLetPlayerSteal(page, html);
      // ...y StreamBox lo ha recuperado: el pie («Pulsa Esc para cerrar») vuelve a ser verdad.
      await expect(close).toBeFocused();

      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(open).toBeFocused();
    });
  }

  test('entrar en el reproductor con Tab sigue siendo posible: no se le quita el foco a quien entra a propósito', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    const { dialog, frame: player } = await openTrailerAndLetPlayerSteal(page, PLAYERS['mientras carga']);
    await expect(dialog.getByRole('button', { name: 'Cerrar el tráiler' })).toBeFocused();

    await page.keyboard.press('Tab');
    await expect(player.getByRole('button', { name: 'Reproducir' })).toBeFocused();
    // Pasado el margen de la vigilancia (1 s tras cargar), el foco sigue donde lo llevó el usuario.
    await page.waitForTimeout(1_200);
    await expect(player.getByRole('button', { name: 'Reproducir' })).toBeFocused();
    await expect(dialog.getByRole('button', { name: 'Cerrar el tráiler' })).not.toBeFocused();
  });
});

test.describe('Diálogo de episodio del panel: los cambios sin guardar no se pierden por un Escape o un clic fuera', () => {
  /** Abre «Añadir episodio» (con Intro) en la edición de la serie sembrada sin episodios. Nunca se guarda nada. */
  async function openEpisodeDialog(
    page: Page,
    request: Parameters<typeof loginAdmin>[0],
    signIn: (user: { token: string }) => Promise<void>,
  ): Promise<{ dialog: Locator; trigger: Locator; title: Locator }> {
    const adminToken = await loginAdmin(request);
    const id = await findSeriesIdAsAdmin(request, adminToken, EMPTY_SERIES.title);
    await signIn({ token: adminToken });
    await page.goto(`/admin/series/${id}/editar`);
    const episodes = page.getByRole('region', { name: 'Episodios', exact: true });
    await expect(episodes.getByRole('heading', { level: 3, name: 'Episodios' })).toBeVisible();
    const trigger = episodes.getByRole('button', { name: 'Añadir episodio' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Añadir episodio' });
    const title = dialog.getByLabel('Título', { exact: true });
    await expect(title).toBeFocused();
    return { dialog, trigger, title };
  }

  test('Escape pregunta con el foco en «Seguir editando», que vuelve al campo sin perder el texto; un segundo Escape descarta', async ({
    page,
    request,
    signIn,
  }) => {
    const { dialog, trigger, title } = await openEpisodeDialog(page, request, signIn);
    await page.keyboard.type('Piloto');

    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();
    const keepEditing = dialog.getByRole('button', { name: 'Seguir editando' });
    await expect(keepEditing).toBeFocused();
    await expect(keepEditing).toHaveAccessibleDescription(/^Tienes cambios sin guardar\. Si cierras ahora, se perderán\./);
    await expect(dialog.getByRole('button', { name: 'Descartar cambios' })).toBeVisible();

    // «Seguir editando» (con Intro): de vuelta al campo en el que estaba, con lo escrito.
    await page.keyboard.press('Enter');
    await expect(title).toBeFocused();
    await expect(title).toHaveValue('Piloto');
    await expect(keepEditing).toHaveCount(0);

    // Escape dos veces: la pregunta y, después, descartar. El foco vuelve al botón que abrió el diálogo.
    await page.keyboard.press('Escape');
    await expect(keepEditing).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test('un clic fuera con cambios pregunta (y otro clic fuera no descarta); «Seguir editando» conserva lo escrito', async ({
    page,
    request,
    signIn,
  }) => {
    const { dialog, trigger, title } = await openEpisodeDialog(page, request, signIn);
    await page.keyboard.type('Piloto');

    // Esquina de la ventana: es el fondo del diálogo (`::backdrop`), fuera de su caja.
    await page.mouse.click(4, 4);
    await expect(dialog).toBeVisible();
    const keepEditing = dialog.getByRole('button', { name: 'Seguir editando' });
    await expect(keepEditing).toBeFocused();
    await page.mouse.click(4, 4);
    await expect(dialog).toBeVisible();
    await expect(keepEditing).toBeVisible();

    await keepEditing.click();
    await expect(title).toHaveValue('Piloto');
    await expect(title).toBeFocused();

    // «Cancelar» es una decisión explícita: cierra sin preguntar.
    await dialog.getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });
});

test('buscador: el primer Escape cierra la lista y conserva el texto, ↓ la vuelve a abrir y el segundo Escape vacía el campo', async ({
  page,
  user,
  signIn,
}) => {
  await signIn(user);
  await page.goto('/');
  await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
  const search = page.getByRole('combobox', { name: 'Buscar películas y series por título' });
  await search.focus();
  await page.keyboard.type('mad');
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await expect(search).toHaveAttribute('aria-activedescendant', /\S/);

  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await expect(search).toHaveAttribute('aria-expanded', 'false');
  await expect(search).toHaveValue('mad');
  await expect(search).toBeFocused();

  // La lista no se ha perdido: ↓ la vuelve a abrir con lo escrito.
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await expect(search).toHaveValue('mad');

  await page.keyboard.press('Escape');
  await expect(search).toHaveValue('');
  await expect(search).toBeFocused();
});

test.describe('Móvil (375 px): campos a 16 px, autocompletado y zonas táctiles de 44 px', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  /** Tamaño de letra calculado, en px. */
  async function fontSize(locator: Locator): Promise<number> {
    return locator.evaluate((element) => parseFloat(getComputedStyle(element).fontSize));
  }

  /** Comprueba que la caja de `locator` mide al menos 44 px en `dimensions`. */
  async function expectTouchTarget(locator: Locator, what: string, dimensions: ('width' | 'height')[]): Promise<void> {
    const box = await locator.boundingBox();
    if (!box) throw new Error(`${what}: no tiene caja (¿no se ve?)`);
    for (const dimension of dimensions) {
      expect.soft(box[dimension], `${what}: ${dimension} de la zona táctil`).toBeGreaterThanOrEqual(44);
    }
  }

  test('login y registro: campos de 16 px y el correo se autocompleta como `username`', async ({ page }) => {
    await page.goto('/login');
    await waitForLoginPage(page);
    const loginEmail = page.getByLabel('Correo electrónico');
    const loginPassword = page.getByLabel('Contraseña', { exact: true });
    for (const [field, name] of [
      [loginEmail, 'correo del login'],
      [loginPassword, 'contraseña del login'],
    ] as const) {
      expect.soft(await fontSize(field), `${name}: con menos de 16 px, iOS amplía la página al enfocarlo`).toBeGreaterThanOrEqual(16);
    }
    await expect.soft(loginEmail).toHaveAttribute('autocomplete', 'username');
    await expect.soft(loginEmail).toHaveAttribute('type', 'email');
    await expect.soft(loginPassword).toHaveAttribute('autocomplete', 'current-password');

    await page.goto('/registro');
    const username = page.getByLabel('Nombre de usuario');
    await expect(username).toBeVisible();
    const email = page.getByLabel('Correo electrónico');
    expect.soft(await fontSize(username), 'nombre de usuario del registro').toBeGreaterThanOrEqual(16);
    expect.soft(await fontSize(email), 'correo del registro').toBeGreaterThanOrEqual(16);
    // El nombre visible no es el usuario de la cuenta (eso es el correo): `nickname`, no `username`.
    await expect.soft(username).toHaveAttribute('autocomplete', 'nickname');
    await expect.soft(email).toHaveAttribute('autocomplete', 'username');
  });

  test('catálogo: filtros y buscador a 16 px, buscador de 44 px de alto y géneros de la ficha de 44 px', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/peliculas');
    const filters = [
      ['Género', page.getByRole('combobox', { name: 'Género' })],
      ['Ordenar por', page.getByRole('combobox', { name: 'Ordenar por' })],
      ['Año', page.getByRole('textbox', { name: 'Año' })],
    ] as const;
    for (const [name, filter] of filters) {
      await expect(filter).toBeVisible();
      expect.soft(await fontSize(filter), `filtro «${name}»`).toBeGreaterThanOrEqual(16);
    }
    // A 375 px el buscador va plegado tras la lupa (D-1): la lupa mide 44×44 y el campo aparece al pulsarla.
    const searchToggle = page.getByRole('button', { name: 'Buscar películas y series' });
    await expectTouchTarget(searchToggle, 'lupa del buscador', ['width', 'height']);
    await searchToggle.click();
    const search = page.getByRole('combobox', { name: 'Buscar películas y series por título' });
    await expect(search).toBeFocused();
    expect.soft(await fontSize(search), 'buscador').toBeGreaterThanOrEqual(16);
    // La zona que se puede pulsar es el propio campo: con 40 px, un toque en el borde de la cápsula no lo enfocaba.
    await expectTouchTarget(search, 'campo del buscador', ['height']);

    const film = MOVIES.find((movie) => movie.title === 'Interstellar')!;
    await page.goto('/');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), film.title).click();
    const header = page.getByRole('region', { name: film.title });
    await expect(header.getByRole('heading', { level: 1, name: film.title })).toBeVisible();
    // Los géneros enlazados están en la ficha (`GenreLinks`), debajo de la cabecera.
    const factSheet = page.getByRole('region', { name: 'Ficha' });
    for (const genre of film.genres) {
      await expectTouchTarget(
        factSheet.getByRole('link', { name: `${genre}, ver todas las películas del género` }),
        `género «${genre}»`,
        ['height'],
      );
    }
  });

  test('la barra (dos filas, unos 105 px) se aparta al bajar, vuelve al subir y nunca se va con el foco dentro', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    const banner = page.getByRole('banner');
    const top = async () => (await banner.boundingBox())!.y;
    // Plegado el buscador, la barra son dos filas de 44 px: logo, lupa y menú; y las pestañas (antes 165 px, tres filas).
    expect((await banner.boundingBox())!.height).toBeLessThanOrEqual(110);

    await page.mouse.wheel(0, 600);
    await expect.poll(top, { message: 'al bajar, la barra se aparta' }).toBeLessThan(-50);
    await page.mouse.wheel(0, -60);
    await expect.poll(top, { message: 'al subir, vuelve' }).toBeGreaterThanOrEqual(-0.5);

    // Con el foco en la barra (aquí, en el buscador desplegado con la lupa) no se aparta al bajar. Llegar a ella
    // con el teclado (Shift+Tab desde el contenido) lo prueba el test siguiente.
    await page.getByRole('button', { name: 'Buscar películas y series' }).click();
    await expect(page.getByRole('combobox', { name: 'Buscar películas y series por título' })).toBeFocused();
    await page.mouse.wheel(0, 600);
    await page.waitForTimeout(400); // más que los 200 ms del deslizamiento: si fuera a apartarse, ya lo habría hecho
    expect(await top()).toBeGreaterThanOrEqual(-0.5);
  });

  /**
   * La barra apartada y el TECLADO (revisión QA del lote D-1): quien navega con el teclado nunca debe quedarse
   * con el foco en un control fuera de la pantalla. Al llegar el foco a la barra apartada (Shift+Tab desde el
   * contenido), vuelve en el acto, sin deslizarse y sin que el navegador desplace la página para enseñar el
   * control; y la lupa y los tres Escape del buscador (cerrar la lista, vaciar, plegar) funcionan sin ratón.
   */
  test('con el teclado: Shift+Tab desde el contenido trae la barra apartada sin mover la página; lupa y Escape por pasos', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    const banner = page.getByRole('banner');
    const top = async () => (await banner.boundingBox())!.y;
    const scrollY = () => page.evaluate(() => window.scrollY);

    await page.mouse.wheel(0, 600);
    await expect.poll(top, { message: 'precondición: al bajar, la barra se aparta' }).toBeLessThan(-50);
    const scrolled = await scrollY();

    // Punto de partida: el `<main>`, donde deja el foco `RouteChangeFocus` tras cambiar de página.
    await page.locator('#contenido').evaluate((main) => (main as HTMLElement).focus({ preventScroll: true }));
    await page.keyboard.press('Shift+Tab');
    const menuButton = page.getByRole('button', { name: 'Menú de usuario' });
    await expect(menuButton).toBeFocused();
    // Sin `poll`: vuelve en el acto (sin transición) y la página no se mueve.
    expect(await top(), 'con el foco dentro, la barra está a la vista').toBeGreaterThanOrEqual(-0.5);
    expect(await scrollY(), 'enfocar la barra no desplaza la página').toBe(scrolled);

    const searchToggle = page.getByRole('button', { name: 'Buscar películas y series' });
    await page.keyboard.press('Shift+Tab');
    await expect(searchToggle).toBeFocused();
    await page.keyboard.press('Enter');
    const search = page.getByRole('combobox', { name: 'Buscar películas y series por título' });
    await expect(searchToggle).toHaveAttribute('aria-expanded', 'true');
    await expect(search).toBeFocused();

    await page.keyboard.type('zzqqxx');
    const noResults = page.getByText('Sin resultados para «zzqqxx».');
    await expect(noResults).toBeVisible();
    expect(await top()).toBeGreaterThanOrEqual(-0.5);

    await page.keyboard.press('Escape'); // 1.º: cierra el mensaje y conserva lo escrito
    await expect(noResults).toBeHidden();
    await expect(search).toHaveValue('zzqqxx');
    await expect(search).toBeFocused();
    await page.keyboard.press('Escape'); // 2.º: vacía el campo
    await expect(search).toHaveValue('');
    await expect(search).toBeFocused();
    await page.keyboard.press('Escape'); // 3.º: pliega y devuelve el foco a la lupa
    await expect(searchToggle).toHaveAttribute('aria-expanded', 'false');
    await expect(search).toBeHidden();
    await expect(searchToggle).toBeFocused();
    expect(await top()).toBeGreaterThanOrEqual(-0.5);
  });

  test('administración: el enlace «Administrar» (solo el escudo en móvil) mide 44×44', async ({ page, request, signIn }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/');
    const admin = page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Administrar' });
    await expect(admin).toBeVisible();
    await expectTouchTarget(admin, 'enlace «Administrar»', ['width', 'height']);
  });
});
