/**
 * Tests de `LikeButton` con el hook real `useLikes` (y `fetch` simulado).
 *
 * Protegen: el estado de alternar (`aria-pressed`) y los nombres accesibles
 * («Me gusta — …» / «Te gusta — …», distintos de «Mi lista»); el cambio
 * optimista con bloqueo por `aria-disabled` (sin perder el foco) y sin dobles
 * peticiones; la vuelta atrás si el servidor falla; el 404 al dar «Me gusta»
 * (el título ya no existe: se dice así y se invalida su página); que película y
 * serie con el mismo id no se confunden; que cada cambio descarta las
 * recomendaciones; y que pulsar mientras la lista carga es seguro.
 */
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { likedMessage, likeGoneMessage, unlikedMessage } from '../hooks/useLikes';
import { queryKeys } from '../lib/queryKeys';
import {
  LIKED_MOVIES,
  LIKED_SERIES,
  errorResponse,
  jsonResponse,
  makeMovie,
  makeRecommendations,
  makeSeries,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { createTestQueryClient } from '../test/queryClient';
import { LikeButton } from './LikeButton';

const fetchMock = vi.fn<typeof fetch>();
let queryClient = createTestQueryClient();

const m1 = makeMovie({ id: 1, title: 'Uno' });
// Misma id que la película: son tablas distintas en el servidor.
const s1 = makeSeries({ id: 1, title: 'Serie Uno' });

// Nombre accesible = texto visible + título. Expresión regular porque jsdom calcula el nombre sin el
// espacio que el navegador sí deja antes del guion largo (como en los tests de «Mi lista»).
const MOVIE_NOT_LIKED = /^Me gusta\s*—\s*Uno$/;
const MOVIE_LIKED = /^Te gusta\s*—\s*Uno$/;
const SERIES_NOT_LIKED = /^Me gusta\s*—\s*Serie Uno$/;
const SERIES_LIKED = /^Te gusta\s*—\s*Serie Uno$/;

const PUT_MOVIE = 'PUT /api/users/me/likes/movies/1';
const DELETE_MOVIE = 'DELETE /api/users/me/likes/movies/1';
const PUT_SERIES = 'PUT /api/users/me/likes/series/1';

/** Promesa controlable a mano para observar el estado «petición en curso». */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Llamadas a `fetch` con ese método y esa URL. */
function callsTo(method: string, url: string) {
  return fetchMock.mock.calls.filter(([input, init]) => String(input) === url && (init?.method ?? 'GET') === method);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  queryClient = createTestQueryClient();
});

/** Monta los dos botones (película 1 y serie 1) con las listas indicadas y espera a que carguen. */
async function setup(
  { movies = [], series = [] }: { movies?: (typeof m1)[]; series?: (typeof s1)[] } = {},
  extra: Parameters<typeof routeFetch>[1] = {},
) {
  routeFetch(fetchMock, {
    [LIKED_MOVIES]: () => jsonResponse(movies),
    [LIKED_SERIES]: () => jsonResponse(series),
    ...extra,
  });
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <LikeButton movie={m1} />
      <LikeButton series={s1} />
    </>,
    { session: true, queryClient },
  );
  await waitFor(() => expect(queryClient.getQueryState(queryKeys.likes.all)?.status).toBe('success'));
  return user;
}

describe('LikeButton: estado y nombres', () => {
  it('refleja lo cargado con aria-pressed, texto visible propio y el título en el nombre', async () => {
    await setup({ movies: [m1] });

    const movie = screen.getByRole('button', { name: MOVIE_LIKED });
    expect(movie).toHaveAttribute('aria-pressed', 'true');
    expect(movie).toHaveTextContent(/^Te gusta/);
    const series = screen.getByRole('button', { name: SERIES_NOT_LIKED });
    expect(series).toHaveAttribute('aria-pressed', 'false');
    expect(series).toHaveTextContent(/^Me gusta/);
    // Nada que ver con «Mi lista»: ni su texto ni su nombre.
    expect(screen.queryByRole('button', { name: /mi lista/i })).not.toBeInTheDocument();
  });
});

describe('LikeButton: dar y quitar «Me gusta»', () => {
  it('dar «Me gusta» es optimista: cambia al instante, se bloquea sin perder el foco, ignora el doble clic y avisa', async () => {
    const pending = deferred<Response>();
    const user = await setup({}, { [PUT_MOVIE]: () => pending.promise });

    await user.click(screen.getByRole('button', { name: MOVIE_NOT_LIKED }));

    // El servidor aún no ha respondido, pero el botón ya dice «Te gusta».
    const button = screen.getByRole('button', { name: MOVIE_LIKED });
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(button).toHaveAttribute('aria-disabled', 'true');
    // `aria-disabled` y no `disabled`: el foco sigue en el botón.
    expect(button).not.toBeDisabled();
    expect(button).toHaveFocus();
    await user.click(button);
    expect(callsTo('PUT', '/api/users/me/likes/movies/1')).toHaveLength(1);
    expect(callsTo('DELETE', '/api/users/me/likes/movies/1')).toHaveLength(0);

    await act(async () => pending.resolve(noContentResponse()));

    expect(screen.getByRole('button', { name: MOVIE_LIKED })).not.toHaveAttribute('aria-disabled');
    expect(await screen.findByText(likedMessage('Uno'))).toBeInTheDocument();
    // Pasa por `apiFetch`: cookie de sesión y cabecera anti-CSRF.
    const [, init] = callsTo('PUT', '/api/users/me/likes/movies/1')[0];
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
    expect(init?.credentials).toBe('same-origin');
  });

  it('dos botones del mismo título (dos componentes) comparten el estado y el bloqueo: un solo PUT', async () => {
    const pending = deferred<Response>();
    routeFetch(fetchMock, { [PUT_MOVIE]: () => pending.promise });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <LikeButton movie={m1} />
        <LikeButton movie={m1} />
      </>,
      { session: true, queryClient },
    );
    await waitFor(() => expect(queryClient.getQueryState(queryKeys.likes.all)?.status).toBe('success'));

    const [first, second] = screen.getAllByRole('button', { name: MOVIE_NOT_LIKED });
    await user.click(first);

    // El otro botón ya dice «Te gusta» y está bloqueado: pulsarlo no manda un DELETE que deshaga el PUT.
    expect(second).toHaveAttribute('aria-pressed', 'true');
    expect(second).toHaveAttribute('aria-disabled', 'true');
    await user.click(second);
    expect(callsTo('PUT', '/api/users/me/likes/movies/1')).toHaveLength(1);
    expect(callsTo('DELETE', '/api/users/me/likes/movies/1')).toHaveLength(0);

    await act(async () => pending.resolve(noContentResponse()));
    expect(second).not.toHaveAttribute('aria-disabled');
  });

  it('quitar «Me gusta» envía DELETE y avisa', async () => {
    const user = await setup({ movies: [m1] }, { [DELETE_MOVIE]: () => noContentResponse() });

    await user.click(screen.getByRole('button', { name: MOVIE_LIKED }));

    expect(await screen.findByText(unlikedMessage('Uno'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: MOVIE_NOT_LIKED })).toHaveAttribute('aria-pressed', 'false');
    expect(callsTo('DELETE', '/api/users/me/likes/movies/1')).toHaveLength(1);
  });

  it('si el servidor falla (500) al dar «Me gusta», vuelve atrás y avisa del error', async () => {
    const user = await setup({}, { [PUT_MOVIE]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });

    await user.click(screen.getByRole('button', { name: MOVIE_NOT_LIKED }));

    expect(
      await screen.findByText('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.'),
    ).toBeInTheDocument();
    const button = screen.getByRole('button', { name: MOVIE_NOT_LIKED });
    expect(button).toHaveAttribute('aria-pressed', 'false');
    expect(button).not.toHaveAttribute('aria-disabled');
    expect(screen.queryByText(likedMessage('Uno'))).not.toBeInTheDocument();
  });

  it('si quitar falla (red caída), el «Me gusta» vuelve y se avisa', async () => {
    const user = await setup({ movies: [m1] }, { [DELETE_MOVIE]: () => Promise.reject(new TypeError('Failed to fetch')) });

    await user.click(screen.getByRole('button', { name: MOVIE_LIKED }));

    expect(await screen.findByText(/No se pudo conectar/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: MOVIE_LIKED })).toHaveAttribute('aria-pressed', 'true');
  });

  it.each([
    ['película', PUT_MOVIE, MOVIE_NOT_LIKED, likeGoneMessage('movie', 'Uno'), queryKeys.movies.detail(1)],
    ['serie', PUT_SERIES, SERIES_NOT_LIKED, likeGoneMessage('series', 'Serie Uno'), queryKeys.series.detail(1)],
  ])(
    'un 404 al dar «Me gusta» a una %s: vuelve atrás, dice que ya no está y vuelve a preguntar por su página',
    async (_kind, route, name, message, detailKey) => {
      queryClient.setQueryData(detailKey, { id: 1 });
      const user = await setup({}, { [route]: () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'No encontrado') });

      await user.click(screen.getByRole('button', { name }));

      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'false');
      expect(queryClient.getQueryState(detailKey)?.isInvalidated).toBe(true);
    },
  );

  it('los textos del 404 dicen la verdad de cada tipo', () => {
    expect(likeGoneMessage('movie', 'Uno')).toBe('«Uno» ya no está en el catálogo, así que no se ha guardado tu «Me gusta».');
    expect(likeGoneMessage('series', 'Dark')).toBe('«Dark» ya no está disponible, así que no se ha guardado tu «Me gusta».');
  });

  it('una película y una serie con el mismo id no se confunden', async () => {
    const user = await setup({}, { [PUT_SERIES]: () => noContentResponse() });

    await user.click(screen.getByRole('button', { name: SERIES_NOT_LIKED }));

    expect(await screen.findByText(likedMessage('Serie Uno'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: SERIES_LIKED })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: MOVIE_NOT_LIKED })).toHaveAttribute('aria-pressed', 'false');
    expect(callsTo('PUT', '/api/users/me/likes/series/1')).toHaveLength(1);
    expect(callsTo('PUT', '/api/users/me/likes/movies/1')).toHaveLength(0);
  });
});

describe('LikeButton: caché', () => {
  it('cada cambio descarta las recomendaciones (dependen de lo que te gusta), también si falla', async () => {
    const recommendations = queryKeys.recommendations.list(12);
    const user = await setup(
      { movies: [m1] },
      { [PUT_SERIES]: () => noContentResponse(), [DELETE_MOVIE]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') },
    );

    queryClient.setQueryData(recommendations, makeRecommendations({ basedOnLikes: true, movies: [makeMovie({ id: 9 })] }));
    await user.click(screen.getByRole('button', { name: SERIES_NOT_LIKED }));
    await screen.findByText(likedMessage('Serie Uno'));
    await waitFor(() => expect(queryClient.getQueryData(recommendations)).toBeUndefined());

    // Un fallo también: si el servidor llegó a aplicarlo, las de antes serían falsas.
    queryClient.setQueryData(recommendations, makeRecommendations({ basedOnLikes: true }));
    await user.click(screen.getByRole('button', { name: MOVIE_LIKED }));
    await screen.findByText(/El servidor ha tenido un problema/);
    await waitFor(() => expect(queryClient.getQueryData(recommendations)).toBeUndefined());
  });

  it('pulsar mientras la lista carga envía un PUT (idempotente) y después vuelve a pedir la lista', async () => {
    const firstLoad = deferred<Response>();
    let loads = 0;
    routeFetch(fetchMock, {
      [LIKED_MOVIES]: () => {
        loads += 1;
        // La primera carga se queda colgada; la segunda trae lo que hay ya en el servidor.
        return loads === 1 ? firstLoad.promise : jsonResponse([m1]);
      },
      [PUT_MOVIE]: () => noContentResponse(),
    });
    const user = userEvent.setup();
    renderWithProviders(<LikeButton movie={m1} />, { session: true, queryClient });

    await user.click(await screen.findByRole('button', { name: MOVIE_NOT_LIKED }));

    expect(await screen.findByText(likedMessage('Uno'))).toBeInTheDocument();
    expect(callsTo('PUT', '/api/users/me/likes/movies/1')).toHaveLength(1);
    expect(await screen.findByRole('button', { name: MOVIE_LIKED })).toHaveAttribute('aria-pressed', 'true');
    expect(loads).toBe(2);
  });
});
