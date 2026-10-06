/**
 * Tests de `SeriesDetailPage` (`/series/:id?temporada=N`) con el hook real y `fetch` simulado.
 *
 * Protegen: la cabecera (h1, datos, sinopsis completa, «Empezar a ver», «Mi
 * lista»), la temporada en la URL (por defecto la primera; un valor inválido no
 * rompe nada), el selector de temporada (enlaces con `aria-current`, el foco se
 * queda), la lista de episodios con su «Ver» seguro (URL validada; insegura →
 * desactivado), y los estados: cargando, 404 «Serie no encontrada» y error con
 * reintento.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeEpisode,
  makeSeriesDetail,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
import type { SeriesDetail } from '../lib/types';
import { SeriesDetailPage } from './SeriesDetailPage';

const fetchMock = vi.fn<typeof fetch>();

const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };
const DETAIL = 'GET /api/series/7';

/** Serie de dos temporadas (3 + 2 episodios) con una sinopsis larga. */
const series = makeSeriesDetail({
  id: 7,
  title: 'La casa de cristal',
  description: 'Una sinopsis larga. '.repeat(20).trim(),
  releaseYear: 2019,
  endYear: 2022,
  genres: [{ id: 1, name: 'Drama' }],
});

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Escribe la búsqueda de la URL (`?temporada=2`); `LocationProbe` solo escribe la ruta. */
function SearchProbe() {
  return <p>{`busqueda:${useLocation().search}`}</p>;
}

/**
 * Monta la página en la ruta indicada, con la serie (o la respuesta) dada para
 * `GET /api/series/7` y las rutas extra que necesite el test (p. ej. otro rol).
 */
function renderDetail(
  route = '/series/7',
  detail: () => Response | Promise<Response> = () => jsonResponse(series),
  extra: Parameters<typeof routeFetch>[1] = {},
) {
  routeFetch(fetchMock, { ...FAVORITES, [DETAIL]: detail, ...extra });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <Routes>
        <Route path="/series/:id" element={<SeriesDetailPage />} />
      </Routes>
      <SearchProbe />
      <UserStatusProbe />
    </FavoritesProvider>,
    { token: 'jwt', route },
  );
  return user;
}

/** Lista de episodios que se está mostrando. */
const episodeList = () => screen.getByRole('list', { name: /^Episodios de la temporada/ });

describe('SeriesDetailPage: cabecera', () => {
  it('título como único h1, datos, sinopsis COMPLETA y título de la pestaña', async () => {
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('list', { name: 'Datos de la serie' })).toHaveTextContent(
      /2019–2022\s*2 temporadas\s*5 episodios\s*Drama/,
    );
    // En la página de la serie no hay otro sitio donde leer la sinopsis: no se recorta.
    expect(screen.getByText(series.description)).not.toHaveClass('line-clamp-3');
    await waitFor(() => expect(document.title).toBe('La casa de cristal — StreamBox'));
  });

  it('«Empezar a ver» abre el primer episodio de la primera temporada en una pestaña nueva', async () => {
    renderDetail();

    const start = await screen.findByRole('link', {
      name: 'Empezar a ver La casa de cristal: T1:E1 Episodio 1 (se abre en una pestaña nueva)',
    });
    expect(start).toHaveAttribute('href', 'https://video.example/s1e1');
    expect(start).toHaveAttribute('target', '_blank');
    expect(start).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('tiene «Mi lista» para la serie', async () => {
    renderDetail();

    expect(await screen.findByRole('button', { name: /^Mi lista\s*—\s*La casa de cristal$/ })).toBeInTheDocument();
  });
});

describe('SeriesDetailPage: temporada y episodios', () => {
  it('sin ?temporada muestra la PRIMERA, marcada como actual en el selector', async () => {
    renderDetail();

    await screen.findByRole('heading', { level: 1 });
    const picker = screen.getByRole('navigation', { name: 'Temporadas' });
    expect(within(picker).getByRole('link', { name: 'Temporada 1' })).toHaveAttribute('aria-current', 'true');
    expect(within(picker).getByRole('link', { name: 'Temporada 2' })).not.toHaveAttribute('aria-current');
    expect(screen.getByText('Temporada 1 · 3 episodios')).toHaveAttribute('role', 'status');
    expect(within(episodeList()).getAllByRole('listitem')).toHaveLength(3);
  });

  it('cada episodio: «N. Título» como h3, duración, sinopsis y «Ver» con nombre único y URL segura', async () => {
    renderDetail();

    const list = await screen.findByRole('list', { name: 'Episodios de la temporada 1' });
    const items = within(list).getAllByRole('listitem');
    expect(within(items[1]).getByRole('heading', { level: 3, name: '2. Episodio 2' })).toBeInTheDocument();
    expect(items[1]).toHaveTextContent('45m');
    expect(items[1]).toHaveTextContent('Sinopsis del episodio 2.');
    const watch = within(items[1]).getByRole('link', { name: 'Ver T1:E2 Episodio 2 (se abre en una pestaña nueva)' });
    expect(watch).toHaveAttribute('href', 'https://video.example/s1e2');
    expect(watch).toHaveAttribute('target', '_blank');
    expect(watch).toHaveAttribute('rel', 'noopener noreferrer');
    // Sin miniatura por episodio: los episodios no tienen imagen propia.
    expect(list.querySelector('img')).toBeNull();
  });

  it('elegir «Temporada 2» la pone en la URL, cambia la lista y el foco se queda en el enlace', async () => {
    const user = renderDetail();
    await screen.findByRole('heading', { level: 1 });

    const second = screen.getByRole('link', { name: 'Temporada 2' });
    await user.click(second);

    expect(screen.getByText('busqueda:?temporada=2')).toBeInTheDocument();
    expect(screen.getByText('ruta:/series/7')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Temporada 2' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('link', { name: 'Temporada 1' })).not.toHaveAttribute('aria-current');
    expect(screen.getByText('Temporada 2 · 2 episodios')).toHaveAttribute('role', 'status');
    expect(within(episodeList()).getByRole('link', { name: /^Ver T2:E1 Episodio 1/ })).toBeInTheDocument();
    expect(second).toHaveFocus();
  });

  it('el selector se maneja con teclado: Tab hasta «Temporada 2» e Intro', async () => {
    const user = renderDetail();
    await screen.findByRole('heading', { level: 1 });

    screen.getByRole('link', { name: 'Temporada 1' }).focus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Temporada 2' })).toHaveFocus();
    await user.keyboard('{Enter}');

    expect(screen.getByText('busqueda:?temporada=2')).toBeInTheDocument();
    expect(screen.getByText('Temporada 2 · 2 episodios')).toHaveAttribute('role', 'status');
  });

  it('entrar con ?temporada=2 muestra directamente la segunda', async () => {
    renderDetail('/series/7?temporada=2');

    expect(await screen.findByRole('list', { name: 'Episodios de la temporada 2' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Temporada 2' })).toHaveAttribute('aria-current', 'true');
  });

  it.each([['?temporada=99'], ['?temporada=abc'], ['?temporada=0'], ['?temporada=']])(
    'una temporada inválida en la URL (%s) muestra la primera sin romper nada',
    async (search) => {
      renderDetail(`/series/7${search}`);

      expect(await screen.findByRole('list', { name: 'Episodios de la temporada 1' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Temporada 1' })).toHaveAttribute('aria-current', 'true');
    },
  );

  it('con una sola temporada no hay selector (no hay nada que elegir)', async () => {
    const single: SeriesDetail = makeSeriesDetail({
      id: 7,
      title: 'Miniserie',
      seasons: [{ seasonNumber: 1, episodes: [makeEpisode({ episodeNumber: 1 })] }],
    });
    renderDetail('/series/7', () => jsonResponse(single));

    expect(await screen.findByRole('list', { name: 'Episodios de la temporada 1' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Temporadas' })).not.toBeInTheDocument();
    expect(screen.getByText('Temporada 1 · 1 episodio')).toHaveAttribute('role', 'status');
  });

  it('un episodio con URL insegura no es un enlace: «Ver» desactivado con su nombre y el motivo', async () => {
    const unsafe = makeSeriesDetail({
      id: 7,
      title: 'Serie',
      seasons: [
        {
          seasonNumber: 1,
          episodes: [
            makeEpisode({ episodeNumber: 1, videoUrl: 'javascript:alert(1)', description: null }),
            makeEpisode({ episodeNumber: 2 }),
          ],
        },
      ],
    });
    renderDetail('/series/7', () => jsonResponse(unsafe));

    const list = await screen.findByRole('list', { name: 'Episodios de la temporada 1' });
    const [first] = within(list).getAllByRole('listitem');
    expect(within(first).queryByRole('link')).not.toBeInTheDocument();
    const disabled = within(first).getByRole('button', { name: 'Ver T1:E1 Episodio 1' });
    expect(disabled).toHaveAttribute('aria-disabled', 'true');
    expect(disabled).toHaveAccessibleDescription(/No disponible: este episodio no tiene un vídeo/);
    // Sin sinopsis no se pinta un párrafo vacío (solo título y duración).
    expect(first.querySelectorAll('p')).toHaveLength(0);
    // «Empezar a ver» apunta al mismo episodio, así que también queda desactivado.
    expect(screen.getByRole('button', { name: /^Empezar a ver Serie: T1:E1/ })).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('SeriesDetailPage: estados', () => {
  it('mientras carga: «Cargando serie...» y un h1 (oculto)', () => {
    renderDetail('/series/7', () => new Promise<Response>(() => {}));

    expect(screen.getByText('Cargando serie...')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('404: «Serie no encontrada» (h1) con enlace a /series y sin «Reintentar»', async () => {
    renderDetail('/series/7', () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada con id 7'));

    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'Serie no encontrada' })).toBeInTheDocument();
    expect(screen.getByText('Puede que la dirección no sea correcta o que la serie ya no esté disponible.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver todas las series' })).toHaveAttribute('href', '/series');
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe('Serie no encontrada — StreamBox'));
  });

  it('404 a un administrador: nombra la serie sin episodios (que da el mismo 404) y lleva a «Gestionar series»', async () => {
    renderDetail('/series/7', () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada con id 7'), {
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
    });

    expect(await screen.findByRole('link', { name: 'Gestionar series' })).toHaveAttribute('href', '/admin/series');
    expect(screen.getByRole('heading', { level: 1, name: 'Serie no encontrada' })).toBeInTheDocument();
    expect(screen.getByText(/o que todavía no tenga episodios: las series sin episodios solo se ven en el panel/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ver todas las series' })).not.toBeInTheDocument();
  });

  it('un id que no es un número es «no encontrada» sin llegar a pedir nada', () => {
    renderDetail('/series/abc');

    expect(screen.getByRole('heading', { level: 1, name: 'Serie no encontrada' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/series'))).toBe(false);
  });

  it('error del servidor: mensaje y «Reintentar» vuelve a pedirla', async () => {
    let fail = true;
    const user = renderDetail('/series/7', () =>
      fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(series),
    );

    expect(await screen.findByRole('heading', { name: 'No se pudo cargar la serie' })).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' })).toBeInTheDocument();
  });
});
