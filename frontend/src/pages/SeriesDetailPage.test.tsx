/**
 * Tests de `SeriesDetailPage` (`/series/:id?temporada=N`) con el hook real y `fetch` simulado.
 *
 * Protegen: la cabecera (h1, datos, sinopsis completa, «Empezar a ver», «Mi
 * lista»), la temporada en la URL (por defecto la primera; un valor inválido no
 * rompe nada), el selector de temporada (enlaces con `aria-current`, el foco se
 * queda), la lista de episodios con su «Ver» seguro (URL validada; sin vídeo o
 * insegura → no aparece), «Empezar a ver» (primer episodio CON vídeo; sin
 * ninguno, no aparece), «Ver tráiler» (solo con tráiler), la ficha (emisión y estado con palabras, géneros en texto
 * porque `/series` aún no se filtra) y los estados: cargando, 404 «Serie no
 * encontrada» y error con reintento. También «Volver a series» y «Editar»
 * SOLO para un administrador, con la vuelta a esta página desde el formulario.
 */
import { act, screen, waitFor, within } from '@testing-library/react';
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
import { SeriesFormPage } from './admin/SeriesFormPage';
import { SeriesDetailPage } from './SeriesDetailPage';

const fetchMock = vi.fn<typeof fetch>();

const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };
const DETAIL = 'GET /api/series/7';
const AS_ADMIN = { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) };
/** Tráiler con la forma normalizada que devuelve la API. */
const TRAILER = 'https://www.youtube.com/watch?v=zSWdZVtXT7E';

/** Serie de una temporada cuyos episodios tienen los vídeos indicados (`null` = sin vídeo). */
function seriesWithVideos(videos: (string | null)[], overrides: Partial<SeriesDetail> = {}): SeriesDetail {
  return makeSeriesDetail({
    id: 7,
    title: 'Serie',
    seasons: [
      {
        seasonNumber: 1,
        episodes: videos.map((videoUrl, index) => makeEpisode({ episodeNumber: index + 1, videoUrl })),
      },
    ],
    ...overrides,
  });
}

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
    { session: true, route },
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

  // Incidencia QA trailer-3: si el principio de la serie no tiene vídeo, el
  // botón no dice «Empezar a ver» (no empieza): dice qué episodio abre.
  it('si el primer episodio no tiene vídeo, el botón dice «Ver T1:E2» (no «Empezar a ver») y abre ese', async () => {
    renderDetail('/series/7', () => jsonResponse(seriesWithVideos([null, 'https://video.example/s1e2'])));

    const start = await screen.findByRole('link', {
      name: 'Ver T1:E2 de Serie: Episodio 2 (se abre en una pestaña nueva)',
    });
    expect(start).toHaveTextContent(/^Ver T1:E2$/);
    expect(start).toHaveAttribute('href', 'https://video.example/s1e2');
    expect(screen.queryByText(/Empezar a ver/)).not.toBeInTheDocument();
  });

  it('si el primer episodio con vídeo está en otra temporada, «Ver T2:E3»', async () => {
    const dark = makeSeriesDetail({
      id: 7,
      title: 'Dark',
      seasons: [
        { seasonNumber: 1, episodes: [makeEpisode({ id: 11, episodeNumber: 1, videoUrl: null })] },
        {
          seasonNumber: 2,
          episodes: [
            makeEpisode({ id: 21, seasonNumber: 2, episodeNumber: 1, videoUrl: null }),
            makeEpisode({ id: 22, seasonNumber: 2, episodeNumber: 3, title: 'El regreso', videoUrl: 'https://video.example/s2e3' }),
          ],
        },
      ],
    });
    renderDetail('/series/7', () => jsonResponse(dark));

    const start = await screen.findByRole('link', { name: /^Ver T2:E3 de Dark: El regreso/ });
    expect(start).toHaveTextContent(/^Ver T2:E3$/);
    expect(start).toHaveAttribute('href', 'https://video.example/s2e3');
    expect(start).toHaveClass('bg-white');
  });

  it('si ningún episodio tiene vídeo, no hay «Empezar a ver» ni ningún «Ver», pero sí los episodios', async () => {
    renderDetail('/series/7', () => jsonResponse(seriesWithVideos([null, null])));

    await screen.findByRole('heading', { level: 1, name: 'Serie' });
    expect(screen.queryByText(/Empezar a ver/)).not.toBeInTheDocument();
    expect(within(episodeList()).getAllByRole('listitem')).toHaveLength(2);
    expect(within(episodeList()).queryByRole('link')).not.toBeInTheDocument();
    expect(within(episodeList()).queryByRole('button')).not.toBeInTheDocument();
    // «Mi lista» sigue, y es la única acción: no queda ningún botón que prometa ver algo.
    expect(screen.getByRole('button', { name: /^Mi lista/ })).toBeInTheDocument();
  });

  it('con tráiler y sin vídeos: «Ver tráiler» es la acción principal y abre el diálogo', async () => {
    const user = renderDetail('/series/7', () =>
      jsonResponse(seriesWithVideos([null], { title: 'Dark', trailerUrl: TRAILER })),
    );

    const trailer = await screen.findByRole('button', { name: 'Ver tráiler de Dark' });
    expect(trailer).toHaveClass('bg-white');
    await user.click(trailer);

    const dialog = screen.getByRole('dialog', { name: 'Tráiler de Dark' });
    expect(dialog.querySelector('iframe')).toHaveAttribute(
      'src',
      'https://www.youtube-nocookie.com/embed/zSWdZVtXT7E?autoplay=1&rel=0',
    );
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar el tráiler' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trailer).toHaveFocus();
  });

  it('con tráiler y con vídeos: «Empezar a ver» principal y «Ver tráiler» secundaria', async () => {
    renderDetail('/series/7', () => jsonResponse({ ...series, trailerUrl: TRAILER }));

    const start = await screen.findByRole('link', { name: /^Empezar a ver/ });
    const trailer = screen.getByRole('button', { name: 'Ver tráiler de La casa de cristal' });
    expect(start).toHaveClass('bg-white');
    expect(trailer).toHaveClass('border');
  });

  it('sin tráiler no hay «Ver tráiler»', async () => {
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' });
    expect(screen.queryByRole('button', { name: /Ver tráiler/ })).not.toBeInTheDocument();
  });

  it('tiene «Mi lista» para la serie', async () => {
    renderDetail();

    expect(await screen.findByRole('button', { name: /^Mi lista\s*—\s*La casa de cristal$/ })).toBeInTheDocument();
  });

  it('«Volver a series» lleva a /series y va antes del título (primera parada del contenido con Tab)', async () => {
    renderDetail();

    const heading = await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' });
    const back = screen.getByRole('link', { name: 'Volver a series' });
    expect(back).toHaveAttribute('href', '/series');
    expect(back.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

/**
 * Incidencia QA trailer-5: sin tráiler y sin ningún episodio con vídeo, la
 * cabecera se quedaba solo con «Mi lista» y nada explicaba por qué. Ahora un
 * aviso breve lo dice, con el texto según el rol (usuario, también mientras
 * carga el rol; administrador, qué falta y dónde añadirlo).
 */
describe('SeriesDetailPage: aviso «sin vídeo ni tráiler» según el rol', () => {
  const USER_NOTE = 'Todavía no está disponible para ver.';
  const ADMIN_NOTE = 'No tiene vídeo ni tráiler: añade el tráiler o el vídeo de los episodios desde «Editar».';

  it('USER: «Todavía no está disponible para ver.»', async () => {
    renderDetail('/series/7', () => jsonResponse(seriesWithVideos([null, null])));

    expect(await screen.findByText(USER_NOTE)).toBeInTheDocument();
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(screen.queryByText(ADMIN_NOTE)).not.toBeInTheDocument();
  });

  it('ADMIN: dice qué falta y que se añade desde «Editar»', async () => {
    renderDetail('/series/7', () => jsonResponse(seriesWithVideos([null, null])), AS_ADMIN);

    expect(await screen.findByText(ADMIN_NOTE)).toBeInTheDocument();
    expect(screen.queryByText(USER_NOTE)).not.toBeInTheDocument();
  });

  it('mientras carga el rol, el texto de usuario (falla cerrado)', async () => {
    renderDetail('/series/7', () => jsonResponse(seriesWithVideos([null, null])), {
      [CURRENT_USER]: () => new Promise<Response>(() => {}),
    });

    expect(await screen.findByText(USER_NOTE)).toBeInTheDocument();
    expect(screen.getByText('estado:loading')).toBeInTheDocument();
  });

  it('el aviso va FUERA de la rejilla de acciones (no cuenta como una acción más)', async () => {
    renderDetail('/series/7', () => jsonResponse(seriesWithVideos([null, null])));

    const note = await screen.findByText(USER_NOTE);
    const actions = screen.getByRole('button', { name: /^Mi lista/ }).parentElement!;
    expect(actions.contains(note)).toBe(false);
  });

  it.each([
    ['con tráiler', seriesWithVideos([null], { trailerUrl: TRAILER })],
    ['con un episodio con vídeo', seriesWithVideos([null, 'https://video.example/s1e2'])],
  ])('%s no hay aviso', async (_caso, detail) => {
    renderDetail('/series/7', () => jsonResponse(detail));

    await screen.findByRole('heading', { level: 1, name: 'Serie' });
    expect(screen.queryByText(USER_NOTE)).not.toBeInTheDocument();
  });
});

describe('SeriesDetailPage: «Editar» según el rol', () => {
  it('un usuario normal no ve «Editar» (ni siquiera cuando ya se sabe su rol)', async () => {
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' });
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Editar/ })).not.toBeInTheDocument();
  });

  it('un administrador ve «Editar La casa de cristal», que lleva al formulario de la serie en el panel', async () => {
    renderDetail('/series/7', () => jsonResponse(series), AS_ADMIN);

    const edit = await screen.findByRole('link', { name: 'Editar La casa de cristal' });
    expect(edit).toHaveAttribute('href', '/admin/series/7/editar');
    expect(edit).toHaveTextContent('Editar');
  });

  it('mientras se carga el usuario (que resulta ser ADMIN) no aparece «Editar»; aparece al confirmarse el rol', async () => {
    let answerUser!: (response: Response) => void;
    renderDetail('/series/7', () => jsonResponse(series), {
      [CURRENT_USER]: () => new Promise<Response>((resolve) => (answerUser = resolve)),
    });

    expect(await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' })).toBeInTheDocument();
    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Editar/ })).not.toBeInTheDocument();

    await act(async () => answerUser(jsonResponse(makeUser({ role: 'ADMIN' }))));

    expect(await screen.findByRole('link', { name: 'Editar La casa de cristal' })).toBeInTheDocument();
  });

  it('«Editar» pasa esta página como vuelta: el formulario ofrece «Volver a la serie» y lleva de nuevo aquí', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      ...AS_ADMIN,
      [DETAIL]: () => jsonResponse(series),
      'GET /api/admin/series/7': () => jsonResponse(series),
      'GET /api/genres': () => jsonResponse(series.genres),
    });
    const user = userEvent.setup();
    renderWithProviders(
      <FavoritesProvider>
        <Routes>
          <Route path="/series/:id" element={<SeriesDetailPage />} />
          <Route path="/admin/series" element={<p>Listado de series</p>} />
          <Route path="/admin/series/:id/editar" element={<SeriesFormPage />} />
        </Routes>
      </FavoritesProvider>,
      { session: true, route: '/series/7' },
    );

    await user.click(await screen.findByRole('link', { name: 'Editar La casa de cristal' }));
    await screen.findByRole('heading', { name: 'Editar «La casa de cristal»' });
    // El de arriba y el de junto a «Guardar cambios»: los dos vuelven a la serie, ninguno al listado.
    const backLinks = screen.getAllByRole('link', { name: 'Volver a la serie' });
    expect(backLinks).toHaveLength(2);
    for (const link of backLinks) expect(link).toHaveAttribute('href', '/series/7');
    expect(screen.queryByRole('link', { name: 'Volver al listado' })).not.toBeInTheDocument();

    await user.click(backLinks[0]);

    expect(await screen.findByRole('heading', { level: 1, name: 'La casa de cristal' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/series/7')).toBeInTheDocument();
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

  it('un episodio con URL insegura no tiene «Ver» (ni enlace ni botón desactivado); «Empezar a ver» lo salta', async () => {
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
    const [first, second] = within(list).getAllByRole('listitem');
    expect(within(first).queryByRole('link')).not.toBeInTheDocument();
    expect(within(first).queryByRole('button')).not.toBeInTheDocument();
    expect(within(first).getByRole('heading', { level: 3, name: '1. Episodio 1' })).toBeInTheDocument();
    // Sin sinopsis no se pinta un párrafo vacío (solo título y duración).
    expect(first.querySelectorAll('p')).toHaveLength(0);
    expect(within(second).getByRole('link', { name: /^Ver T1:E2 Episodio 2/ })).toBeInTheDocument();
    // La cabecera no lleva a un `javascript:`: salta al primer episodio con vídeo seguro (y dice cuál).
    expect(screen.getByRole('link', { name: /^Ver T1:E2 de Serie: Episodio 2/ })).toHaveAttribute(
      'href',
      'https://video.example/s1e2',
    );
  });
});

/** Pares «nombre: valor» de la ficha, en orden. */
async function factSheet(): Promise<string[]> {
  const sheet = await screen.findByRole('region', { name: 'Ficha' });
  return within(sheet)
    .getAllByRole('term')
    .map((term) => `${term.textContent}: ${factText(term.nextElementSibling)}`);
}

/** Texto de un valor de la ficha; si es una lista (los géneros, que son enlaces), sus elementos separados por comas. */
function factText(dd: Element | null): string {
  const items = dd ? Array.from(dd.querySelectorAll('li')) : [];
  return items.length > 0 ? items.map((item) => item.textContent).join(', ') : (dd?.textContent ?? '');
}

/** Datos del banner («Datos de la serie»), en orden. */
function bannerFacts(): string[] {
  return within(screen.getByRole('list', { name: 'Datos de la serie' }))
    .getAllByRole('listitem')
    .map((item) => item.textContent ?? '');
}

describe('SeriesDetailPage: ficha', () => {
  it('serie terminada: años, «Finalizada», temporadas, episodios, géneros y fecha de alta', async () => {
    renderDetail('/series/7', () =>
      jsonResponse({ ...series, genres: [{ id: 1, name: 'Drama' }, { id: 2, name: 'Thriller' }], createdAt: '2026-10-03T12:00:00Z' }),
    );

    expect(await factSheet()).toEqual([
      'Emisión: 2019–2022',
      'Estado: Finalizada',
      'Temporadas: 2',
      'Episodios: 5',
      'Géneros: Drama, Thriller',
      'En el catálogo desde: 3 de octubre de 2026',
    ]);
  });

  it('los géneros enlazan a /series filtrada por ese género (no a películas), con un nombre que empieza por el texto visible', async () => {
    renderDetail('/series/7', () =>
      jsonResponse({ ...series, genres: [{ id: 1, name: 'Drama' }, { id: 2, name: 'Thriller' }] }),
    );

    const sheet = await screen.findByRole('region', { name: 'Ficha' });
    const genres = within(sheet).getAllByRole('link');
    expect(genres.map((link) => link.getAttribute('href'))).toEqual(['/series?genero=1', '/series?genero=2']);
    expect(genres[0]).toHaveAccessibleName('Drama, ver todas las series del género');
    expect(genres[1]).toHaveAccessibleName('Thriller, ver todas las series del género');
  });

  it('pulsar un género de la ficha lleva a /series con ese filtro', async () => {
    const user = renderDetail('/series/7', () => jsonResponse({ ...series, genres: [{ id: 1, name: 'Drama' }] }));

    await user.click(
      within(await screen.findByRole('region', { name: 'Ficha' })).getByRole('link', {
        name: 'Drama, ver todas las series del género',
      }),
    );

    expect(screen.getByText('ruta:/series')).toBeInTheDocument();
    expect(screen.getByText('busqueda:?genero=1')).toBeInTheDocument();
  });

  it('serie en emisión: «Desde 2021» y «En emisión» (no la raya sola de «2021–»)', async () => {
    renderDetail('/series/7', () => jsonResponse({ ...series, releaseYear: 2021, endYear: null }));

    const facts = await factSheet();
    expect(facts).toContain('Emisión: Desde 2021');
    expect(facts).toContain('Estado: En emisión');
  });

  /**
   * Revisión QA: casos límite de la ficha de una serie. Una serie con una sola
   * temporada y un solo episodio (sin selector de temporada, singular en el
   * banner y en el estado), una que empieza y termina el mismo año (sin raya
   * «2021–2021») y una sin géneros y con fecha inválida (sin datos vacíos).
   */
  it('una temporada y un episodio, en emisión: ficha con «1», singular en el resto y sin selector', async () => {
    // Sin copiar `series` entera: sus `seasonCount`/`episodeCount` (2 y 5) ganarían a los calculados.
    const tiny = makeSeriesDetail({
      id: 7,
      title: series.title,
      genres: series.genres,
      releaseYear: 2025,
      endYear: null,
      seasons: [{ seasonNumber: 1, episodes: [makeEpisode({ seasonNumber: 1, episodeNumber: 1 })] }],
    });
    renderDetail('/series/7', () => jsonResponse(tiny));

    expect(await factSheet()).toEqual([
      'Emisión: Desde 2025',
      'Estado: En emisión',
      'Temporadas: 1',
      'Episodios: 1',
      'Géneros: Drama',
      'En el catálogo desde: 1 de enero de 2026',
    ]);
    expect(screen.getByRole('list', { name: 'Datos de la serie' })).toHaveTextContent(/1 temporada\s*1 episodio(?!s)/);
    expect(screen.getByText('Temporada 1 · 1 episodio')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Temporadas' })).not.toBeInTheDocument();
  });

  it('empieza y termina el mismo año: «Emisión: 2021» (sin raya) y «Finalizada»', async () => {
    renderDetail('/series/7', () => jsonResponse({ ...series, releaseYear: 2021, endYear: 2021 }));

    const facts = await factSheet();
    expect(facts).toContain('Emisión: 2021');
    expect(facts).toContain('Estado: Finalizada');
  });

  /**
   * El backend admite un año de fin futuro (hasta 2100): una serie con el final
   * ya anunciado no puede salir «Finalizada» antes de tiempo. La ficha da el
   * rango anunciado y el estado «En emisión», y el banner dice lo mismo.
   */
  it('año de fin futuro: «Emisión: 2021–<año siguiente>» y «En emisión» (no «Finalizada»), también en el banner', async () => {
    const nextYear = new Date().getFullYear() + 1;
    renderDetail('/series/7', () => jsonResponse({ ...series, releaseYear: 2021, endYear: nextYear }));

    const facts = await factSheet();
    expect(facts).toContain(`Emisión: 2021–${nextYear}`);
    expect(facts).toContain('Estado: En emisión');
    expect(facts).not.toContain('Estado: Finalizada');
    expect(bannerFacts()).toEqual([`2021–${nextYear}`, 'En emisión', '2 temporadas', '5 episodios', 'Drama']);
  });

  it('año de fin = el actual: ya cuenta como «Finalizada»', async () => {
    const thisYear = new Date().getFullYear();
    renderDetail('/series/7', () => jsonResponse({ ...series, releaseYear: 2021, endYear: thisYear }));

    const facts = await factSheet();
    expect(facts).toContain(`Emisión: 2021–${thisYear}`);
    expect(facts).toContain('Estado: Finalizada');
    expect(bannerFacts()).not.toContain('En emisión');
  });

  it('sin géneros y con una fecha de alta inválida: esos dos datos no aparecen (nunca un valor vacío)', async () => {
    renderDetail('/series/7', () => jsonResponse({ ...series, genres: [], createdAt: 'no-es-una-fecha' }));

    expect(await factSheet()).toEqual(['Emisión: 2019–2022', 'Estado: Finalizada', 'Temporadas: 2', 'Episodios: 5']);
    expect(screen.getByRole('region', { name: 'Ficha' })).not.toHaveTextContent('Invalid Date');
  });

  it('jerarquía de encabezados: h1 (título), h2 «Episodios» con sus h3 y después el h2 «Ficha»', async () => {
    renderDetail();

    await screen.findByRole('region', { name: 'Ficha' });
    const headings = screen.getAllByRole('heading').map((heading) => `${heading.tagName}:${heading.textContent}`);
    expect(headings).toEqual([
      'H1:La casa de cristal',
      'H2:Episodios',
      'H3:1. Episodio 1',
      'H3:2. Episodio 2',
      'H3:3. Episodio 3',
      'H2:Ficha',
    ]);
  });
});

describe('SeriesDetailPage: estados', () => {
  it('mientras carga: la silueta de la página con «Cargando serie...» y un h1 (oculto)', () => {
    renderDetail('/series/7', () => new Promise<Response>(() => {}));

    // Anunciado en una región `role="status"` (las formas del esqueleto son decorativas).
    expect(screen.getByText('Cargando serie...').closest('[role="status"]')).not.toBeNull();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('sr-only');
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
