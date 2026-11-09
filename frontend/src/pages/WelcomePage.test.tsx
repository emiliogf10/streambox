/**
 * Tests de la pregunta de bienvenida (`/bienvenida`, `WelcomePage`) junto con la
 * guarda que lleva a ella (`RequireAuth`), con la sesión, la caché y los avisos
 * reales y `fetch` simulado.
 *
 * Protegen lo que pidió el autor: solo a un `USER` con la pregunta pendiente y
 * una sola vez; volver a donde iba; «Guardar» solo con 3–5 y un contador
 * verdadero (al llegar a 5 no deja marcar más y lo dice); «Ahora no» siempre
 * visible (lista vacía); sin géneros elegibles, solo «Continuar»; los errores
 * avisan y dejan reintentar sin bloquear la app («Continuar sin guardar»); y que
 * al terminar se descartan las recomendaciones en caché.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RequireAuth } from '../components/RouteGuards';
import { queryKeys } from '../lib/queryKeys';
import {
  CURRENT_USER,
  GENRE_OPTIONS,
  SAVE_FAVORITE_GENRES,
  errorResponse,
  jsonResponse,
  makeRecommendations,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { createTestQueryClient } from '../test/queryClient';
import { WELCOME_SAVED_MESSAGE, WELCOME_SKIPPED_MESSAGE, WelcomePage } from './WelcomePage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const GENRES = ['Acción', 'Animación', 'Ciencia ficción', 'Comedia', 'Drama', 'Terror'].map((name, index) => ({
  id: index + 1,
  name,
}));

const pendingUser = makeUser({ genresOnboardingPending: true });

/** Ruta completa actual (con búsqueda): para comprobar que se vuelve exactamente a donde se iba. */
function FullPathProbe() {
  const location = useLocation();
  return <p>{`completa:${location.pathname}${location.search}`}</p>;
}

/** Mini aplicación: dos rutas protegidas, la bienvenida y el login, con la guarda real. */
function Routing() {
  return (
    <>
      <Routes>
        <Route
          path="/"
          element={
            <RequireAuth>
              <h1>Portada</h1>
              <Link to="/series?orden=titulo">Ir a series</Link>
            </RequireAuth>
          }
        />
        <Route
          path="/series"
          element={
            <RequireAuth>
              <h1>Series</h1>
              <Link to="/">Ir a la portada</Link>
            </RequireAuth>
          }
        />
        <Route
          path="/bienvenida"
          element={
            <RequireAuth>
              <WelcomePage />
            </RequireAuth>
          }
        />
        <Route path="/login" element={<h1>Login</h1>} />
      </Routes>
      <FullPathProbe />
    </>
  );
}

/** Bodies de los `PUT /api/users/me/genres` enviados. */
function savedBodies(): unknown[] {
  return fetchMock.mock.calls
    .filter(([url, init]) => String(url) === '/api/users/me/genres' && init?.method === 'PUT')
    .map(([, init]) => JSON.parse(String(init?.body)));
}

/** Monta la mini aplicación en `route` con un usuario pendiente (o el que se pase) y las rutas extra. */
function renderWelcome(
  { route = '/', user = pendingUser, routes = {} }: { route?: string; user?: ReturnType<typeof makeUser>; routes?: Parameters<typeof routeFetch>[1] } = {},
) {
  routeFetch(fetchMock, {
    [CURRENT_USER]: () => jsonResponse(user),
    [GENRE_OPTIONS]: () => jsonResponse(GENRES),
    [SAVE_FAVORITE_GENRES]: ({ init }) => {
      const { genreIds } = JSON.parse(String(init.body)) as { genreIds: number[] };
      return jsonResponse(GENRES.filter((genre) => genreIds.includes(genre.id)));
    },
    ...routes,
  });
  const queryClient = createTestQueryClient();
  const userEvents = userEvent.setup();
  renderWithProviders(<Routing />, { route, session: true, queryClient });
  return { user: userEvents, queryClient };
}

/** El grupo de géneros, cuando ya ha cargado. */
async function picker() {
  return screen.findByRole('group', { name: 'Tus géneros favoritos' });
}

describe('Redirección a /bienvenida', () => {
  it('un USER con la pregunta pendiente va a /bienvenida guardando a dónde iba; el foco, al título', async () => {
    renderWelcome({ route: '/series?orden=titulo' });

    const title = await screen.findByRole('heading', { level: 1, name: 'Cuéntanos qué te gusta' });
    expect(screen.getByText('ruta:/bienvenida')).toBeInTheDocument();
    expect(title).toHaveFocus();
    expect(screen.getByText('Hola, ana')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Elige entre 3 y 5 géneros y te recomendaremos películas y series desde el primer día. Podrás cambiarlos cuando quieras en tu perfil.',
      ),
    ).toBeInTheDocument();
  });

  it('quien no la tiene pendiente no es desviado, y si escribe /bienvenida vuelve al inicio sin aviso', async () => {
    renderWelcome({ route: '/bienvenida', user: makeUser() });

    expect(await screen.findByRole('heading', { level: 1, name: 'Portada' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
    expect(screen.queryByRole('alert', { name: /./ })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/genres/options')).toBe(false);
  });

  it('a un ADMIN nunca se le pregunta, aunque el servidor lo marcara pendiente', async () => {
    renderWelcome({ route: '/series', user: makeUser({ role: 'ADMIN', genresOnboardingPending: true }) });

    expect(await screen.findByRole('heading', { level: 1, name: 'Series' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Cuéntanos qué te gusta' })).not.toBeInTheDocument();
  });
});

describe('Elegir géneros', () => {
  it('«Guardar» solo con 3 a 5, con un contador verdadero; al guardar, PUT con los ids y vuelta al destino', async () => {
    const { user } = renderWelcome({ route: '/series?orden=titulo' });
    const group = await picker();
    const save = screen.getByRole('button', { name: 'Guardar' });

    expect(within(group).getAllByRole('checkbox')).toHaveLength(6);
    expect(save).toHaveAttribute('aria-disabled', 'true');
    expect(save).toHaveAccessibleDescription('Has elegido 0 de 5. Elige al menos 3 más.');
    await user.click(save);
    expect(savedBodies()).toEqual([]);

    await user.click(within(group).getByRole('checkbox', { name: 'Acción' }));
    await user.click(within(group).getByRole('checkbox', { name: 'Drama' }));
    expect(within(group).getByRole('status')).toHaveTextContent('Has elegido 2 de 5. Elige al menos 1 más.');
    expect(save).toHaveAttribute('aria-disabled', 'true');

    await user.click(within(group).getByRole('checkbox', { name: 'Comedia' }));
    expect(within(group).getByRole('status')).toHaveTextContent('Has elegido 3 de 5. Ya puedes guardar.');
    expect(save).not.toHaveAttribute('aria-disabled');

    await user.click(save);
    expect(await screen.findByRole('heading', { level: 1, name: 'Series' })).toBeInTheDocument();
    expect(screen.getByText('completa:/series?orden=titulo')).toBeInTheDocument();
    expect(savedBodies()).toEqual([{ genreIds: [1, 5, 4] }]);
    expect(await screen.findByText(WELCOME_SAVED_MESSAGE)).toBeInTheDocument();

    // Una sola vez: navegar después a otra ruta protegida ya no vuelve a preguntar.
    await user.click(screen.getByRole('link', { name: 'Ir a la portada' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Portada' })).toBeInTheDocument();
  });

  it('al llegar a 5 no deja marcar más y lo explica (las demás casillas siguen en el orden del teclado)', async () => {
    const { user } = renderWelcome();
    const group = await picker();

    for (const name of ['Acción', 'Animación', 'Ciencia ficción', 'Comedia', 'Drama']) {
      await user.click(within(group).getByRole('checkbox', { name }));
    }
    const terror = within(group).getByRole('checkbox', { name: 'Terror' });
    expect(within(group).getByRole('status')).toHaveTextContent(
      'Has elegido 5 de 5. Es el máximo: quita uno si quieres elegir otro.',
    );
    expect(terror).toHaveAttribute('aria-disabled', 'true');
    expect(terror).toHaveAccessibleDescription('Has elegido 5 de 5. Es el máximo: quita uno si quieres elegir otro.');
    expect(terror).not.toBeDisabled();

    await user.click(terror);
    expect(terror).not.toBeChecked();

    // Quitar uno libera el hueco.
    await user.click(within(group).getByRole('checkbox', { name: 'Drama' }));
    expect(terror).not.toHaveAttribute('aria-disabled');
    await user.click(terror);
    expect(terror).toBeChecked();
  });

  it('«Ahora no» envía la lista vacía y lleva al inicio con un aviso que dice dónde elegirlos después', async () => {
    const { user } = renderWelcome();
    await picker();

    await user.click(screen.getByRole('button', { name: 'Ahora no' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Portada' })).toBeInTheDocument();
    expect(savedBodies()).toEqual([{ genreIds: [] }]);
    expect(await screen.findByText(WELCOME_SKIPPED_MESSAGE)).toBeInTheDocument();
  });

  it('«Ahora no» explica que no se volverá a preguntar', async () => {
    renderWelcome();
    await picker();

    expect(screen.getByRole('button', { name: 'Ahora no' })).toHaveAccessibleDescription(
      'Con «Ahora no» no volveremos a preguntártelo; podrás elegirlos en tu perfil.',
    );
  });

  it('al terminar se descartan las recomendaciones en caché (la portada las vuelve a pedir con los gustos)', async () => {
    const { user, queryClient } = renderWelcome();
    queryClient.setQueryData(queryKeys.recommendations.list(12), makeRecommendations());
    const group = await picker();
    for (const name of ['Acción', 'Comedia', 'Drama']) {
      await user.click(within(group).getByRole('checkbox', { name }));
    }

    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    await screen.findByRole('heading', { level: 1, name: 'Portada' });
    expect(queryClient.getQueryData(queryKeys.recommendations.list(12))).toBeUndefined();
    expect(queryClient.getQueryData(queryKeys.favoriteGenres.mine)).toEqual([GENRES[0], GENRES[3], GENRES[4]]);
  });
});

describe('Sin géneros y errores', () => {
  it('sin ningún género elegible lo dice y solo ofrece «Continuar» (lista vacía), sin callejón sin salida', async () => {
    const { user } = renderWelcome({ routes: { [GENRE_OPTIONS]: () => jsonResponse([]) } });

    expect(await screen.findByText('Todavía no hay géneros que elegir')).toBeInTheDocument();
    // El título y la entradilla no piden lo que es imposible hacer.
    expect(screen.getByRole('heading', { level: 1, name: 'Te damos la bienvenida' })).toBeInTheDocument();
    expect(screen.queryByText(/Elige entre 3 y 5/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ahora no' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Continuar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Portada' })).toBeInTheDocument();
    expect(savedBodies()).toEqual([{ genreIds: [] }]);
  });

  it('sin ningún género elegible, el texto habla de títulos visibles (verdad también si hay series sin episodios)', async () => {
    renderWelcome({ routes: { [GENRE_OPTIONS]: () => jsonResponse([]) } });

    expect(
      await screen.findByText(
        'Ningún título visible tiene género todavía. Cuando haya títulos de al menos 3 géneros, podrás elegir tus favoritos en tu perfil, en «Tus gustos».',
      ),
    ).toBeInTheDocument();
    // Sin géneros con títulos, los «Me gusta» tampoco darían recomendaciones: no se promete.
    expect(screen.queryByText(/Me gusta/)).not.toBeInTheDocument();
  });

  it('con 1 o 2 géneros elegibles dice cuáles hay y que hacen falta 3; «Continuar» cierra la pregunta sin un aviso falso', async () => {
    const { user } = renderWelcome({ route: '/series', routes: { [GENRE_OPTIONS]: () => jsonResponse([GENRES[4]]) } });

    expect(await screen.findByText('Todavía no hay géneros suficientes')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Te damos la bienvenida' })).toBeInTheDocument();
    expect(screen.queryByText(/Elige entre 3 y 5/)).not.toBeInTheDocument();
    expect(
      screen.getByText(
        'Hacen falta títulos visibles de al menos 3 géneros y por ahora solo los hay de «Drama». Cuando haya títulos de al menos 3 géneros, podrás elegir tus favoritos en tu perfil, en «Tus gustos». Mientras tanto, los títulos que marques con «Me gusta» también sirven para recomendarte otros parecidos.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ahora no' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Continuar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Series' })).toBeInTheDocument();
    expect(savedBodies()).toEqual([{ genreIds: [] }]);
    // «Puedes elegir tus géneros cuando quieras» sería falso: no hay aviso (llegar al destino es la respuesta).
    expect(screen.queryByText(WELCOME_SKIPPED_MESSAGE)).not.toBeInTheDocument();
  });

  it('si no se pueden cargar los géneros: aviso con «Reintentar» y «Ahora no» sigue disponible', async () => {
    let fail = true;
    const { user } = renderWelcome({
      routes: { [GENRE_OPTIONS]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(GENRES)) },
    });

    const heading = await screen.findByRole('heading', { name: 'No se pudieron cargar los géneros' });
    expect(screen.getByRole('button', { name: 'Ahora no' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument();

    fail = false;
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    await user.click(within(alert!).getByRole('button', { name: 'Reintentar' }));
    expect(await picker()).toBeInTheDocument();
  });

  it('si falla el guardado (500): aviso, se queda para reintentar y además «Continuar sin guardar»', async () => {
    let fail = true;
    const { user } = renderWelcome({
      routes: {
        [SAVE_FAVORITE_GENRES]: () =>
          fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse([GENRES[0], GENRES[1], GENRES[2]]),
      },
    });
    const group = await picker();
    for (const name of ['Acción', 'Animación', 'Ciencia ficción']) {
      await user.click(within(group).getByRole('checkbox', { name }));
    }

    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByText('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.')).toBeInTheDocument();
    expect(screen.getByText('ruta:/bienvenida')).toBeInTheDocument();
    expect(
      screen.getByText('Si prefieres, sigue sin guardar: te lo volveremos a preguntar la próxima vez que entres.'),
    ).toBeInTheDocument();
    // La selección sigue ahí: reintentar con el mismo botón.
    expect(within(group).getByRole('checkbox', { name: 'Acción' })).toBeChecked();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Portada' })).toBeInTheDocument();
  });

  it('«Continuar sin guardar» lleva al destino y no vuelve a preguntar en esta visita', async () => {
    // Red caída: `fetch` rechaza.
    const { user } = renderWelcome({
      route: '/series',
      routes: { [SAVE_FAVORITE_GENRES]: () => Promise.reject(new TypeError('Failed to fetch')) },
    });
    await picker();

    await user.click(screen.getByRole('button', { name: 'Ahora no' }));
    await user.click(await screen.findByRole('button', { name: 'Continuar sin guardar' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Series' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Ir a la portada' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Portada' })).toBeInTheDocument();
  });

  it('un 400 de los géneros se enseña junto al grupo y se vuelven a pedir las opciones', async () => {
    const { user } = renderWelcome({
      routes: {
        [SAVE_FAVORITE_GENRES]: () =>
          errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos.', {
            validationErrors: { genreIds: 'Alguno de los géneros ya no tiene títulos.' },
          }),
      },
    });
    const group = await picker();
    for (const name of ['Acción', 'Animación', 'Terror']) {
      await user.click(within(group).getByRole('checkbox', { name }));
    }
    const optionCalls = () => fetchMock.mock.calls.filter(([url]) => String(url) === '/api/users/me/genres/options').length;
    expect(optionCalls()).toBe(1);

    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    expect(await within(group).findByRole('alert')).toHaveTextContent('Alguno de los géneros ya no tiene títulos.');
    expect(group).toHaveFocus();
    await waitFor(() => expect(optionCalls()).toBe(2));
    expect(screen.queryByRole('button', { name: 'Continuar sin guardar' })).not.toBeInTheDocument();
  });
});

/**
 * Revisión de QA: casos límite que dejaban al usuario sin forma de guardar sus
 * gustos (callejón sin salida, petición expresa del autor).
 */
describe('Revisión QA: sin callejones sin salida', () => {
  /**
   * Un género deja de tener títulos mientras se elige: el `PUT` da 400 y se
   * vuelven a pedir las opciones, que ya no lo traen. Su casilla desaparece, así
   * que el usuario ya NO puede desmarcarlo: si siguiera en la selección, el
   * contador diría «3 de 5. Ya puedes guardar» con solo 2 marcados a la vista y
   * cada «Guardar» volvería a enviar el id rechazado (400 sin fin). La
   * selección debe quedarse solo con lo que se ofrece, como ya hace el editor
   * del perfil (`visibleSelected`).
   */
  it('tras un 400 porque un género se quedó sin títulos, la selección deja de contar el que ya no se ofrece', async () => {
    let optionCalls = 0;
    const { user } = renderWelcome({
      routes: {
        [GENRE_OPTIONS]: () => {
          optionCalls += 1;
          return jsonResponse(optionCalls === 1 ? GENRES : GENRES.filter((genre) => genre.name !== 'Terror'));
        },
        [SAVE_FAVORITE_GENRES]: () =>
          errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos.', {
            validationErrors: { genreIds: 'Alguno de los géneros no existe o no tiene títulos.' },
          }),
      },
    });
    const group = await picker();
    for (const name of ['Acción', 'Animación', 'Terror']) {
      await user.click(within(group).getByRole('checkbox', { name }));
    }

    await user.click(screen.getByRole('button', { name: 'Guardar' }));
    await screen.findByText('Alguno de los géneros no existe o no tiene títulos.');
    await waitFor(() => expect(screen.queryByRole('checkbox', { name: 'Terror' })).not.toBeInTheDocument());

    const refreshed = await picker();
    expect(within(refreshed).getAllByRole('checkbox', { checked: true })).toHaveLength(2);
    expect(within(refreshed).getByRole('status')).toHaveTextContent('Has elegido 2 de 5. Elige al menos 1 más.');
    expect(screen.getByRole('button', { name: 'Guardar' })).toHaveAttribute('aria-disabled', 'true');
  });

  /**
   * Con menos de 3 géneros elegibles (catálogo pequeño: p. ej. solo Drama y
   * Comedia tienen títulos) es imposible llegar al mínimo. No debe ofrecerse un
   * «Guardar» que nunca se habilita con un contador que pide «Elige al menos 1
   * más» cuando no queda ninguno: igual que sin ningún género, hay que decirlo
   * y dejar solo la salida («Continuar»/«Ahora no»).
   */
  it('con menos de 3 géneros elegibles no ofrece un «Guardar» imposible', async () => {
    renderWelcome({ routes: { [GENRE_OPTIONS]: () => jsonResponse(GENRES.slice(0, 2)) } });

    await screen.findByRole('heading', { level: 1, name: 'Cuéntanos qué te gusta' });
    await waitFor(() => expect(screen.queryByText('Cargando géneros...')).not.toBeInTheDocument());

    expect(screen.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Elige al menos \d+ más/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^(Continuar|Ahora no)$/ })).toBeInTheDocument();
  });
});
