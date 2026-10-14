/**
 * Tests de `MovieDetailPage` (`/peliculas/:id`) con el hook real y `fetch` simulado.
 *
 * Protegen: la cabecera (h1 único, datos, sinopsis completa, título de la
 * pestaña), «Ver ahora» seguro (URL validada; insegura → desactivado), «Mi
 * lista» de verdad (petición y aviso), «Editar» SOLO para un administrador, y
 * los estados: cargando (silueta del banner), 404 «Película no encontrada» con
 * el mismo texto para los dos roles, id mal formado sin petición y error con
 * reintento. Y los casos límite del final: rol sin confirmar (falla cerrado),
 * ids enormes y el 401 que pasa por la renovación de la sesión.
 */
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeMovie,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
import { MovieFormPage } from './admin/MovieFormPage';
import { MovieDetailPage } from './MovieDetailPage';

const fetchMock = vi.fn<typeof fetch>();

const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };
const DETAIL = 'GET /api/movies/7';
const AS_ADMIN = { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) };

/** Película con una sinopsis larga (la página no la recorta). */
const movie = makeMovie({
  id: 7,
  title: 'Interstellar',
  description: 'Una sinopsis larga. '.repeat(20).trim(),
  releaseYear: 2014,
  duration: 169,
  videoUrl: 'https://video.example/interstellar',
  genres: [
    { id: 1, name: 'Ciencia ficción' },
    { id: 2, name: 'Drama' },
  ],
});

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/**
 * Monta la página en la ruta indicada, con la película (o la respuesta) dada
 * para `GET /api/movies/7` y las rutas extra que necesite el test (otro rol...).
 */
function renderDetail(
  route = '/peliculas/7',
  detail: () => Response | Promise<Response> = () => jsonResponse(movie),
  extra: Parameters<typeof routeFetch>[1] = {},
) {
  routeFetch(fetchMock, { ...FAVORITES, [DETAIL]: detail, ...extra });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <Routes>
        <Route path="/peliculas/:id" element={<MovieDetailPage />} />
      </Routes>
      <UserStatusProbe />
    </FavoritesProvider>,
    { session: true, route },
  );
  return user;
}

describe('MovieDetailPage: contenido', () => {
  it('título como único h1, año, duración y géneros, sinopsis COMPLETA y título de la pestaña', async () => {
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('list', { name: 'Datos de la película' })).toHaveTextContent(
      /2014\s*2h 49m\s*Ciencia ficción\s*Drama/,
    );
    // Aquí no hay otro sitio donde leer la sinopsis: no se recorta.
    expect(screen.getByText(movie.description)).not.toHaveClass('line-clamp-3');
    // La página es la película: sin la marca «Estreno reciente» del banner de la portada.
    expect(screen.queryByText('Estreno reciente')).not.toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe('Interstellar — StreamBox'));
  });

  it('sin sinopsis lo dice en lugar de dejar el hueco vacío', async () => {
    renderDetail('/peliculas/7', () => jsonResponse({ ...movie, description: '' }));

    expect(await screen.findByText('Sin descripción disponible.')).toBeInTheDocument();
  });

  it('«Ver ahora» abre el vídeo en una pestaña nueva', async () => {
    renderDetail();

    const watch = await screen.findByRole('link', { name: 'Ver ahora Interstellar (se abre en una pestaña nueva)' });
    expect(watch).toHaveAttribute('href', 'https://video.example/interstellar');
    expect(watch).toHaveAttribute('target', '_blank');
    expect(watch).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('con una URL de vídeo insegura, «Ver ahora» no es un enlace: botón desactivado con el motivo', async () => {
    renderDetail('/peliculas/7', () => jsonResponse({ ...movie, videoUrl: 'javascript:alert(1)' }));

    const watch = await screen.findByRole('button', { name: /^Ver ahora/ });
    expect(watch).toHaveAttribute('aria-disabled', 'true');
    expect(watch).toHaveAccessibleDescription(/No disponible: esta película no tiene un vídeo/);
    expect(screen.queryByRole('link', { name: /^Ver ahora/ })).not.toBeInTheDocument();
  });

  it('«Mi lista» la añade de verdad (POST) y lo confirma con un aviso', async () => {
    const add = vi.fn(() => noContentResponse());
    const user = renderDetail('/peliculas/7', () => jsonResponse(movie), { 'POST /api/users/me/favorites/7': add });

    await user.click(await screen.findByRole('button', { name: /^Mi lista\s*—\s*Interstellar$/ }));

    expect(await screen.findByRole('button', { name: /^En mi lista\s*—\s*Interstellar$/ })).toBeInTheDocument();
    expect(await screen.findByText('«Interstellar» se ha añadido a tu lista.')).toBeInTheDocument();
    expect(add).toHaveBeenCalledTimes(1);
  });
});

describe('MovieDetailPage: «Editar» según el rol', () => {
  it('un usuario normal no ve «Editar» (ni siquiera cuando ya se sabe su rol)', async () => {
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Editar/ })).not.toBeInTheDocument();
  });

  it('un administrador ve «Editar Interstellar», que lleva al formulario del panel', async () => {
    renderDetail('/peliculas/7', () => jsonResponse(movie), AS_ADMIN);

    const edit = await screen.findByRole('link', { name: 'Editar Interstellar' });
    expect(edit).toHaveAttribute('href', '/admin/peliculas/7/editar');
    expect(edit).toHaveTextContent('Editar');
  });

  it('«Editar» y luego «Guardar cambios» vuelven a ESTA página, que ya muestra el dato nuevo', async () => {
    let current = movie;
    routeFetch(fetchMock, {
      ...FAVORITES,
      ...AS_ADMIN,
      [DETAIL]: () => jsonResponse(current),
      'GET /api/genres': () => jsonResponse(movie.genres),
      'PUT /api/movies/7': () => {
        current = { ...movie, title: 'Interstellar (versión extendida)' };
        return jsonResponse(current);
      },
    });
    const user = userEvent.setup();
    renderWithProviders(
      <FavoritesProvider>
        <Routes>
          <Route path="/peliculas/:id" element={<MovieDetailPage />} />
          <Route path="/admin/peliculas/:id/editar" element={<MovieFormPage />} />
        </Routes>
      </FavoritesProvider>,
      { session: true, route: '/peliculas/7' },
    );

    await user.click(await screen.findByRole('link', { name: 'Editar Interstellar' }));
    await screen.findByRole('heading', { name: 'Editar «Interstellar»' });
    // Se viene de la película: el enlace de arriba lo dice.
    expect(screen.getByRole('link', { name: 'Volver a la película' })).toHaveAttribute('href', '/peliculas/7');

    const title = screen.getByLabelText('Título');
    await user.clear(title);
    await user.type(title, 'Interstellar (versión extendida)');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Interstellar (versión extendida)' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/peliculas/7')).toBeInTheDocument();
  });
});

describe('MovieDetailPage: estados', () => {
  it('mientras carga: la silueta del banner con «Cargando película...» y un h1 (oculto)', () => {
    renderDetail('/peliculas/7', () => new Promise<Response>(() => {}));

    // Anunciado en una región `role="status"` (las formas del esqueleto son decorativas).
    expect(screen.getByText('Cargando película...').closest('[role="status"]')).not.toBeNull();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('sr-only');
  });

  it.each([
    ['un usuario', {}],
    ['un administrador', AS_ADMIN],
  ])('404 para %s: «Película no encontrada» (h1), enlace a /peliculas y sin «Reintentar»', async (_role, extra) => {
    renderDetail('/peliculas/7', () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Película no encontrada con id 7'), extra);

    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeInTheDocument();
    expect(
      screen.getByText('Puede que la dirección no sea correcta o que la película se haya retirado del catálogo.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver todas las películas' })).toHaveAttribute('href', '/peliculas');
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe('Película no encontrada — StreamBox'));
  });

  it.each([['abc'], ['0'], ['1.5'], ['-3']])('un id mal formado (%s) es «no encontrada» sin llegar a pedir nada', (id) => {
    renderDetail(`/peliculas/${id}`);

    expect(screen.getByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/movies'))).toBe(false);
  });

  it('error del servidor: mensaje y «Reintentar» vuelve a pedirla', async () => {
    let fail = true;
    const user = renderDetail('/peliculas/7', () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(movie)));

    expect(await screen.findByRole('heading', { name: 'No se pudo cargar la película' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
  });
});

/**
 * Casos límite añadidos en la revisión de QA: lo que la página promete en su
 * documentación (rol que falla cerrado, ids que no se pueden representar, la
 * renovación de la sesión) y que los tests anteriores no fijaban.
 */
describe('MovieDetailPage: casos límite', () => {
  it('mientras se carga el usuario (que resulta ser ADMIN) no aparece «Editar»; aparece al confirmarse el rol', async () => {
    let answerUser!: (response: Response) => void;
    renderDetail('/peliculas/7', () => jsonResponse(movie), {
      [CURRENT_USER]: () => new Promise<Response>((resolve) => (answerUser = resolve)),
    });

    // La película ya está en pantalla, pero el rol aún no se sabe: se trata como USER.
    expect(await screen.findByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Editar/ })).not.toBeInTheDocument();

    await act(async () => answerUser(jsonResponse(makeUser({ role: 'ADMIN' }))));

    expect(await screen.findByRole('link', { name: 'Editar Interstellar' })).toBeInTheDocument();
  });

  it('si no se puede saber el rol (/users/me da 500), «Editar» no aparece: falla cerrado', async () => {
    renderDetail('/peliculas/7', () => jsonResponse(movie), {
      [CURRENT_USER]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    expect(await screen.findByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
    expect(await screen.findByText('estado:error')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Editar/ })).not.toBeInTheDocument();
  });

  it.each([['99999999999999999999'], ['9007199254740993']])(
    'un id que JavaScript no representa con exactitud (%s) es «no encontrada» sin pedir OTRA película',
    (id) => {
      renderDetail(`/peliculas/${id}`);

      expect(screen.getByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeInTheDocument();
      expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/movies'))).toBe(false);
    },
  );

  it('un 401 del detalle pasa por la renovación de la sesión (un solo refresh) y la petición se repite UNA vez', async () => {
    let first = true;
    const refresh = vi.fn(() => noContentResponse());
    renderDetail(
      '/peliculas/7',
      () => {
        if (!first) return jsonResponse(movie);
        first = false;
        return errorResponse(401, 'UNAUTHORIZED', 'No autenticado.');
      },
      { 'POST /api/auth/refresh': refresh },
    );

    expect(await screen.findByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/movies/7')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });
});
