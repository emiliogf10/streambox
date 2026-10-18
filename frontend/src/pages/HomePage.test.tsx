/**
 * Tests de `HomePage` (portada) con el hook `useCatalog` real y `fetch` simulado.
 *
 * Protegen los tres estados obligatorios (cargando, vacío, error con reintento),
 * que la película del banner no se repita en las filas, el reparto en filas por
 * género y que un fallo de "Cargar más" no destruya lo que ya se ve. Y que lo
 * vacío diga la verdad a cada rol: sin películas no se da el catálogo por vacío
 * si hay series, y un administrador sabe por qué no ve la fila «Series».
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  CURRENT_USER,
  RECOMMENDATIONS,
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  makeRecommendations,
  makeSeries,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import type { QueryClient } from '@tanstack/react-query';
import { RECOMMENDATIONS_LIMIT } from '../hooks/useRecommendations';
import { queryKeys } from '../lib/queryKeys';
import type { TitleLists } from '../lib/titleLists';
import { createTestQueryClient } from '../test/queryClient';
import { UserStatusProbe } from '../test/UserStatusProbe';
import { HomePage } from './HomePage';

const fetchMock = vi.fn<typeof fetch>();

const PAGE_0 = 'GET /api/movies?page=0&size=20&sort=createdAt&direction=desc';
const PAGE_1 = 'GET /api/movies?page=1&size=20&sort=createdAt&direction=desc';

const terror = { id: 7, name: 'Terror' };

// El orden recibido es el del servidor: más reciente primero. La 1 será el banner.
const hero = makeMovie({ id: 1, title: 'Estreno estrella', genres: [terror] });
const others = [
  makeMovie({ id: 2, title: 'Segunda', genres: [terror] }),
  makeMovie({ id: 3, title: 'Tercera', genres: [terror] }),
  makeMovie({ id: 4, title: 'Cuarta', genres: [terror] }),
];

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Monta la portada; `queryClient`, para los tests que miran o siembran la caché. */
function renderHome(queryClient?: QueryClient) {
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <HomePage />
      <UserStatusProbe />
    </FavoritesProvider>,
    { session: true, queryClient },
  );
  return user;
}

/** La fila «Series» de la portada: las 12 series más recientes. */
const SERIES_ROW = 'GET /api/series?page=0&size=12&sort=createdAt&direction=desc';

/**
 * Lo que la portada pide además del catálogo: la lista (para «Mi lista») y la
 * fila «Series», vacía por defecto (así no aparece y los tests de películas no cambian).
 */
const FAVORITES = {
  'GET /api/users/me/favorites': () => jsonResponse([]),
  [SERIES_ROW]: () => jsonResponse(makePage([])),
};

describe('HomePage: estados', () => {
  it('muestra "cargando" (con role="status") mientras espera y siempre tiene un único h1', () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => new Promise<Response>(() => {}) });

    renderHome();

    expect(screen.getByText('Cargando catálogo...')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('sin películas ni series, a un usuario: «El catálogo está vacío» (las dos cosas), sin banner ni botón', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([])) });

    renderHome();

    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'El catálogo está vacío' })).toBeInTheDocument();
    expect(screen.getByText('Todavía no hay películas ni series. Vuelve más tarde.')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('Estreno reciente')).not.toBeInTheDocument();
  });

  it('sin películas PERO con series: no dice que el catálogo esté vacío, enseña la fila «Series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([])),
      [SERIES_ROW]: () => jsonResponse(makePage([makeSeries({ id: 10, title: 'Serie nueva' })])),
    });

    renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(within(row).getByRole('link', { name: /^Serie nueva/ })).toHaveAttribute('href', '/series/10');
    expect(screen.queryByRole('heading', { name: 'El catálogo está vacío' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('sin películas ni series visibles, a un administrador: qué hace visible cada cosa y «Gestionar catálogo»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [PAGE_0]: () => jsonResponse(makePage([])),
    });

    renderHome();

    expect(await screen.findByRole('link', { name: 'Gestionar catálogo' })).toHaveAttribute('href', '/admin/peliculas');
    expect(screen.getByRole('heading', { level: 2, name: 'Todavía no hay nada visible en el catálogo' })).toBeInTheDocument();
    expect(screen.getByText(/las series, cuando tienen al menos un episodio/)).toBeInTheDocument();
    // «Vacío» sería falso si tiene series ocultas; y el aviso pequeño de la fila no se suma al grande.
    expect(screen.queryByRole('heading', { name: 'El catálogo está vacío' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
  });

  it('error: muestra el mensaje y "Reintentar" recupera el catálogo', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderHome();

    expect(await screen.findByRole('heading', { name: 'No se pudo cargar el catálogo' })).toBeInTheDocument();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();

    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Estreno estrella' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No se pudo cargar el catálogo' })).not.toBeInTheDocument();
  });
});

describe('HomePage: contenido', () => {
  beforeEach(() => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
  });

  it('la película más reciente es el banner y NO se repite en las filas', async () => {
    renderHome();

    const banner = await screen.findByRole('region', { name: 'Estreno estrella' });
    expect(within(banner).getByRole('link', { name: /Ver ahora Estreno estrella/ })).toBeInTheDocument();

    const news = screen.getByRole('region', { name: 'Novedades' });
    expect(within(news).queryByText('Estreno estrella')).not.toBeInTheDocument();
    expect(within(news).getAllByRole('link', { name: /^(Segunda|Tercera|Cuarta)/ })).toHaveLength(3);
  });

  it('jerarquía de encabezados: un único h1 y, debajo, banner y filas como h2 (sin h3 sueltos)', async () => {
    renderHome();
    await screen.findByRole('region', { name: 'Novedades' });

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Terror',
    ]);
    // Los antiguos paneles "Sinopsis / Reparto / Ficha" (h3) ya no existen.
    expect(screen.queryByRole('heading', { level: 3 })).not.toBeInTheDocument();
    expect(screen.queryByText(/Información no disponible/)).not.toBeInTheDocument();
  });

  it('crea una fila por género con al menos 3 películas (sin contar el banner)', async () => {
    renderHome();

    const row = await screen.findByRole('region', { name: 'Terror' });

    expect(within(row).getAllByRole('link', { name: /^(Segunda|Tercera|Cuarta)/ })).toHaveLength(3);
  });

  it('indica cuántas películas se muestran del total', async () => {
    renderHome();

    expect(await screen.findByText('Mostrando 4 de 4 películas')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });

  it('cada tarjeta es un enlace a la página de su película y pulsarla lleva a ella (ya no abre un diálogo)', async () => {
    const user = renderHome();

    const news = await screen.findByRole('region', { name: 'Novedades' });
    const card = within(news).getByRole('link', { name: /^Tercera/ });
    expect(card).toHaveAttribute('href', '/peliculas/3');
    await user.click(card);

    expect(screen.getByText('ruta:/peliculas/3')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('«Más información» del banner lleva a la página de la película destacada', async () => {
    renderHome();

    const banner = await screen.findByRole('region', { name: hero.title });
    expect(within(banner).getByRole('link', { name: /^Más información/ })).toHaveAttribute('href', `/peliculas/${hero.id}`);
  });
});

describe('HomePage: cargar más', () => {
  const withNext = () => jsonResponse(makePage([hero, ...others], { totalElements: 5, totalPages: 2, hasNext: true }));

  it('añade la página siguiente al final sin duplicados y retira el botón al llegar al final', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: withNext,
      // El servidor repite la "Cuarta" (límites que se mueven) y añade una nueva.
      [PAGE_1]: () => jsonResponse(makePage([others[2], makeMovie({ id: 5, title: 'Quinta' })], { page: 1, totalElements: 5, hasNext: false })),
    });
    const user = renderHome();

    await user.click(await screen.findByRole('button', { name: 'Cargar más películas' }));

    expect(await screen.findByText('Mostrando 5 de 5 películas')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /^Cuarta/ })).toHaveLength(2); // una en Novedades y otra en Terror, no cuatro
    expect(screen.getAllByRole('link', { name: /^Quinta/ }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });

  it('si falla conserva lo cargado, avisa y el botón pasa a "Reintentar carga"', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: withNext,
      [PAGE_1]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = renderHome();

    await user.click(await screen.findByRole('button', { name: 'Cargar más películas' }));

    expect(await screen.findByRole('button', { name: 'Reintentar carga' })).toBeEnabled();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(screen.getByText('Mostrando 4 de 5 películas')).toBeInTheDocument();
  });
});

describe('HomePage: fila «Series»', () => {
  const latest = [
    makeSeries({ id: 10, title: 'Serie nueva', endYear: null, seasonCount: 1 }),
    makeSeries({ id: 11, title: 'Serie antigua' }),
  ];

  it('con series, aparece justo después de «Novedades», con tarjetas-enlace a cada serie y «Ver todas»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => jsonResponse(makePage(latest)),
    });
    renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Series',
      'Terror',
    ]);
    const cards = within(row).getAllByRole('link', { name: /^Serie/ });
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/series/10', '/series/11']);
    expect(cards[0]).toHaveAccessibleName('Serie nueva 2019– · 1 temporada');
    expect(within(row).getByRole('link', { name: /^Ver todas\s*las series$/ })).toHaveAttribute('href', '/series');
  });

  it('sin series, a un usuario no le aparece (ni un hueco ni un aviso)', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
    renderHome();

    await screen.findByRole('region', { name: 'Novedades' });
    // Espera a que termine la carga de la fila (su esqueleto anuncia «Cargando series...») y a que se sepa el rol.
    await waitFor(() => expect(screen.queryByText('Cargando series...')).not.toBeInTheDocument());
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Series' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Series' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
  });

  it('sin series visibles, a un administrador le deja un aviso de una línea con el motivo y «Gestionar series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
    });
    renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(row).toHaveTextContent(
      'Todavía no hay series visibles, así que los usuarios no ven esta fila. Una serie aparece en cuanto tiene al menos un episodio.',
    );
    expect(within(row).getByRole('link', { name: 'Gestionar series' })).toHaveAttribute('href', '/admin/series');
    // En su sitio de siempre (tras «Novedades») y sin tapar el resto de la portada.
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Series',
      'Terror',
    ]);
  });

  it('si falla, la fila lo dice con «Reintentar» sin tapar el resto de la portada, y reintentar la recupera', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(within(row).getByRole('alert')).toHaveTextContent(/No se pudieron cargar las series\./);
    expect(screen.getByRole('region', { name: 'Novedades' })).toBeInTheDocument();

    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => jsonResponse(makePage(latest)),
    });
    await user.click(within(row).getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('link', { name: /^Serie nueva/ })).toBeInTheDocument();
  });

  it('mientras carga, la fila reserva su hueco con un esqueleto (las filas de debajo no saltan al llegar)', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => new Promise<Response>(() => {}),
    });
    renderHome();

    await screen.findByRole('region', { name: 'Novedades' });
    expect(screen.getByText('Cargando series...')).toBeInTheDocument();
  });
});

describe('HomePage: «Recomendadas para ti»', () => {
  const liked = makeRecommendations({
    basedOnLikes: true,
    movies: [makeMovie({ id: 20, title: 'Recomendada', genres: [terror] })],
    series: [makeSeries({ id: 21, title: 'Serie recomendada' })],
  });

  it('con «Me gusta», las dos filas van justo después de «Novedades» y antes de la fila «Series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => jsonResponse(makePage([makeSeries({ id: 10, title: 'Serie nueva' })])),
      [RECOMMENDATIONS]: () => jsonResponse(liked),
    });
    renderHome();

    const movies = await screen.findByRole('region', { name: 'Películas recomendadas para ti' });
    expect(within(movies).getByRole('link', { name: /^Recomendada 2020 ·/ })).toHaveAttribute('href', '/peliculas/20');
    await screen.findByRole('region', { name: 'Series' });
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Películas recomendadas para ti',
      'Series recomendadas para ti',
      'Series',
      'Terror',
    ]);
  });

  it.each([
    ['un usuario', 'USER'],
    ['un administrador', 'ADMIN'],
  ] as const)(
    'a %s sin ningún «Me gusta» no se le enseña ni se le anuncia nada de las recomendaciones en ningún momento',
    async (_who, role) => {
      let answer: (response: Response) => void = () => {};
      routeFetch(fetchMock, {
        ...FAVORITES,
        [CURRENT_USER]: () => jsonResponse(makeUser({ role })),
        [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
        [RECOMMENDATIONS]: () => new Promise<Response>((resolve) => (answer = resolve)),
      });
      // Cualquier cambio del DOM que deje a la vista (o en el texto que lee un lector de pantalla,
      // `sr-only` incluido) algo de las recomendaciones queda anotado: así se cubre TODO el recorrido,
      // no solo los instantes en que el test mira.
      const seen: string[] = [];
      const mentionsRecommendations = () => /recomend/i.test(document.body.textContent ?? '');
      const observer = new MutationObserver(() => {
        if (mentionsRecommendations()) seen.push(document.body.textContent ?? '');
      });
      observer.observe(document.body, { childList: true, subtree: true, characterData: true });
      const queryClient = createTestQueryClient();
      try {
        renderHome(queryClient);

        // Cargando: la portada ya está y la petición ha salido, pero no hay hueco ni aviso.
        await screen.findByRole('region', { name: 'Novedades' });
        await waitFor(() =>
          expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/recommendations?limit=12')).toBe(
            true,
          ),
        );
        expect(screen.queryByText('Cargando recomendaciones...')).not.toBeInTheDocument();

        // Llega la respuesta (`basedOnLikes: false`): tampoco aparece nada.
        answer(jsonResponse(makeRecommendations()));
        await waitFor(() =>
          expect(queryClient.getQueryState(queryKeys.recommendations.list(RECOMMENDATIONS_LIMIT))?.status).toBe(
            'success',
          ),
        );
        expect(screen.queryByRole('region', { name: /recomendadas para ti/ })).not.toBeInTheDocument();
        expect(mentionsRecommendations()).toBe(false);
        expect(seen).toEqual([]);
      } finally {
        observer.disconnect();
      }
    },
  );

  it('la petición sale a la vez que la del catálogo, no cuando este ya ha llegado (así no empuja la portada)', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => new Promise<Response>(() => {}),
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations()),
    });
    renderHome();

    expect(await screen.findByText('Cargando catálogo...')).toBeInTheDocument();
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/recommendations?limit=12')).toBe(true),
    );
  });

  it('si la caché ya sabe que le gusta algo (vuelve tras un «Me gusta»), reserva el hueco y anuncia la carga', async () => {
    const queryClient = createTestQueryClient();
    const likes: TitleLists = { movie: [makeMovie({ id: 30, title: 'Me encantó', genres: [terror] })], series: [] };
    routeFetch(fetchMock, {
      ...FAVORITES,
      // Se siembra al responder el catálogo: la sesión ya está resuelta (al resolverse se vacía la caché),
      // como cuando se vuelve a la portada desde la página de un título con los «Me gusta» ya cargados.
      [PAGE_0]: () => {
        queryClient.setQueryData(queryKeys.likes.all, likes);
        return jsonResponse(makePage([hero, ...others]));
      },
      [RECOMMENDATIONS]: () => new Promise<Response>(() => {}),
    });
    renderHome(queryClient);

    await screen.findByRole('region', { name: 'Novedades' });
    const skeleton = await screen.findByText('Cargando recomendaciones...');
    expect(skeleton.closest('[role="status"]')).toBeInTheDocument();
  });

  it('si la caché sabe que no le gusta nada (listas vacías), tampoco reserva el hueco', async () => {
    const queryClient = createTestQueryClient();
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => {
        queryClient.setQueryData<TitleLists>(queryKeys.likes.all, { movie: [], series: [] });
        return jsonResponse(makePage([hero, ...others]));
      },
      [RECOMMENDATIONS]: () => new Promise<Response>(() => {}),
    });
    renderHome(queryClient);

    await screen.findByRole('region', { name: 'Novedades' });
    await waitFor(() => expect(screen.getByRole('region', { name: 'Terror' })).toBeInTheDocument());
    expect(screen.queryByText('Cargando recomendaciones...')).not.toBeInTheDocument();
  });

  it('si fallan, un aviso compacto con «Reintentar» y el resto de la portada sigue ahí', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [RECOMMENDATIONS]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    renderHome();

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar tus recomendaciones' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Novedades' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Terror' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
  });

  it('sin películas en el catálogo, las series recomendadas salen antes de la fila «Series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([])),
      [SERIES_ROW]: () => jsonResponse(makePage([makeSeries({ id: 10, title: 'Serie nueva' })])),
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basedOnLikes: true, series: liked.series })),
    });
    renderHome();

    await screen.findByRole('region', { name: 'Series recomendadas para ti' });
    await screen.findByRole('region', { name: 'Series' });
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Series recomendadas para ti',
      'Series',
    ]);
  });
});
