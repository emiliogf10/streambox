/**
 * Tests de la gestión de episodios de una serie en el panel
 * (`SeriesEpisodesSection` + `EpisodeFormDialog`).
 *
 * Protegen: la lista agrupada por temporada con nombres accesibles únicos; los
 * valores que propone el diálogo al añadir (y su recálculo al cambiar de
 * temporada, salvo número escrito a mano); crear, editar y borrar con la serie
 * refrescada del servidor tras cada cambio; el 409 `EPISODE_ALREADY_EXISTS`
 * junto a temporada/número; la validación de cliente con los mensajes exactos
 * del servidor; el 404 de un episodio que ya no existe; los avisos de "ya es
 * visible" / "vuelve a estar oculta"; y adónde va el foco. `fetch` está
 * simulado; avisos, sesión y diálogos son los reales.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Episode, Season, SeriesDetail } from '../../lib/types';
import {
  errorResponse,
  jsonResponse,
  makeEpisode,
  makeSeriesDetail,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { SeriesEpisodesSection } from './SeriesEpisodesSection';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const SERIES_ID = 7;
const DETAIL_ROUTE = `GET /api/admin/series/${SERIES_ID}`;
const EPISODES_ROUTE = `/api/series/${SERIES_ID}/episodes`;

/** Temporada con episodios de los números indicados (títulos «Episodio N»). */
function season(seasonNumber: number, numbers: number[], overrides: Partial<Episode> = {}): Season {
  return {
    seasonNumber,
    episodes: numbers.map((episodeNumber) => makeEpisode({ seasonNumber, episodeNumber, ...overrides })),
  };
}

/** Serie de prueba: por defecto, temporada 1 con 3 episodios y temporada 2 con 2. */
function detail(seasons: Season[] = [season(1, [1, 2, 3]), season(2, [1, 2])]): SeriesDetail {
  return makeSeriesDetail({ id: SERIES_ID, title: 'Dark', seasons });
}

/** Monta la sección como lo hace `SeriesFormPage`: guardando la serie que le devuelve tras cada cambio. */
function Harness({ initial }: { initial: SeriesDetail }) {
  const [series, setSeries] = useState(initial);
  return <SeriesEpisodesSection series={series} onSeriesChange={setSeries} />;
}

function renderSection(initial: SeriesDetail) {
  return renderWithProviders(<Harness initial={initial} />, { token: 'jwt' });
}

const setupUser = () => userEvent.setup({ delay: null });
const section = () => screen.getByRole('region', { name: 'Episodios' });
const addButton = () => within(section()).getByRole('button', { name: 'Añadir episodio' });
const dialog = (name: string | RegExp) => screen.getByRole('dialog', { name });
const field = (scope: HTMLElement, label: string) => within(scope).getByLabelText(label);

/** Sustituye el contenido de un campo. */
async function fill(user: ReturnType<typeof setupUser>, input: HTMLElement, text: string) {
  await user.clear(input);
  if (text) await user.type(input, text);
}

/** Cuerpo JSON de la petición `método url`. */
function sentBody(method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(([input, init]) => String(input) === url && init?.method === method);
  if (!call) throw new Error(`No se envió ${method} ${url}`);
  return JSON.parse(String(call[1]?.body));
}

/** Mensaje que `apiFetch` da a cualquier 5xx (no se enseña el texto del servidor). */
const SERVER_ERROR = 'El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.';

const detailRequests = () => fetchMock.mock.calls.filter(([input]) => String(input) === `/api/admin/series/${SERIES_ID}`).length;

describe('SeriesEpisodesSection: lista', () => {
  it('agrupa por temporada, con recuentos, visibilidad y acciones con nombre único', () => {
    routeFetch(fetchMock, {});
    const series = detail([
      season(1, [1, 2, 3]),
      season(2, [1], { title: 'Piloto', duration: 62, description: null }),
      season(3, [1]),
    ]);
    renderSection(series);

    expect(within(section()).getByText('5 episodios en 3 temporadas')).toBeInTheDocument();
    expect(within(section()).getByText('Visible para los usuarios')).toBeInTheDocument();
    expect(within(section()).getAllByRole('heading', { level: 4 }).map((h) => h.textContent)).toEqual([
      'Temporada 1 · 3 episodios',
      'Temporada 2 · 1 episodio',
      'Temporada 3 · 1 episodio',
    ]);
    const first = within(section()).getByRole('list', { name: 'Temporada 1 · 3 episodios' });
    expect(within(first).getAllByRole('listitem')).toHaveLength(3);
    const second = within(section()).getByRole('list', { name: 'Temporada 2 · 1 episodio' });
    const item = within(second).getByRole('listitem');
    expect(item).toHaveTextContent('T2:E1');
    expect(item).toHaveTextContent('Piloto');
    expect(item).toHaveTextContent('1h 2m · Sin sinopsis');
    expect(within(item).getByRole('button', { name: 'Editar episodio T2:E1 Piloto' })).toBeInTheDocument();
    expect(within(item).getByRole('button', { name: 'Borrar episodio T2:E1 Piloto' })).toBeInTheDocument();
    // «Episodio 1» se llama igual en las temporadas 1 y 3: el código hace único cada nombre.
    expect(within(first).getByRole('button', { name: 'Editar episodio T1:E1 Episodio 1' })).toBeInTheDocument();
    expect(within(section()).getByRole('button', { name: 'Editar episodio T3:E1 Episodio 1' })).toBeInTheDocument();
  });

  it('sin episodios: explica que está oculta y «Añadir episodio» es la acción principal', () => {
    routeFetch(fetchMock, {});
    renderSection(detail([]));

    expect(within(section()).getByText(/los usuarios no la ven/)).toBeInTheDocument();
    expect(within(section()).queryByText('Visible para los usuarios')).not.toBeInTheDocument();
    expect(within(section()).queryAllByRole('heading', { level: 4 })).toHaveLength(0);
    expect(addButton()).toBeEnabled();
  });
});

describe('SeriesEpisodesSection: añadir', () => {
  it('propone la última temporada y el siguiente número; lo recalcula al cambiar de temporada', async () => {
    routeFetch(fetchMock, {});
    const user = setupUser();
    renderSection(detail([season(1, [1, 2, 3]), season(2, [1, 2])]));

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    expect(form).toHaveAccessibleDescription('Se proponen la última temporada y el siguiente número libre; puedes cambiarlos.');
    expect(field(form, 'Temporada')).toHaveValue('2');
    expect(field(form, 'Número')).toHaveValue('3');
    // El foco va al título: temporada y número ya vienen puestos.
    expect(field(form, 'Título')).toHaveFocus();

    await fill(user, field(form, 'Temporada'), '1');
    expect(field(form, 'Número')).toHaveValue('4');
    await fill(user, field(form, 'Temporada'), '5');
    expect(field(form, 'Número')).toHaveValue('1');
    // Temporada no válida (a medio escribir): no se toca el número.
    await fill(user, field(form, 'Temporada'), '');
    expect(field(form, 'Número')).toHaveValue('1');

    // Un número escrito a mano no se sobrescribe al cambiar de temporada.
    await fill(user, field(form, 'Número'), '9');
    await fill(user, field(form, 'Temporada'), '1');
    expect(field(form, 'Número')).toHaveValue('9');
  });

  it('una serie vacía propone T1:E1; el primer episodio avisa de que ya es visible', async () => {
    const created = makeEpisode({ id: 50, seasonNumber: 1, episodeNumber: 1, title: 'Comienzo', description: null });
    routeFetch(fetchMock, {
      [`POST ${EPISODES_ROUTE}`]: () => jsonResponse(created, 201),
      [DETAIL_ROUTE]: () => jsonResponse(detail([{ seasonNumber: 1, episodes: [created] }])),
    });
    const user = setupUser();
    renderSection(detail([]));

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    expect(field(form, 'Temporada')).toHaveValue('1');
    expect(field(form, 'Número')).toHaveValue('1');
    await user.type(field(form, 'Título'), 'Comienzo');
    await user.type(field(form, 'Duración (minutos)'), '50');
    await user.type(field(form, 'URL del vídeo'), 'https://v.example.com/1');
    await user.click(within(form).getByRole('button', { name: 'Añadir episodio' }));

    expect(
      await screen.findByText('Se ha añadido el episodio T1:E1 «Comienzo». La serie ya es visible para los usuarios.'),
    ).toBeInTheDocument();
    expect(within(section()).getByText('Visible para los usuarios')).toBeInTheDocument();
    expect(within(section()).queryByText(/los usuarios no la ven/)).not.toBeInTheDocument();
  });

  it('crea con los datos limpios, espera al refresco antes de cerrar y devuelve el foco a «Añadir episodio»', async () => {
    const created = makeEpisode({ id: 99, seasonNumber: 2, episodeNumber: 3, title: 'Nuevo', duration: 48 });
    let resolvePost: (response: Response) => void = () => {};
    routeFetch(fetchMock, {
      [`POST ${EPISODES_ROUTE}`]: () => new Promise<Response>((resolve) => (resolvePost = resolve)),
      [DETAIL_ROUTE]: () => jsonResponse(detail([season(1, [1, 2, 3]), { ...season(2, [1, 2]), episodes: [...season(2, [1, 2]).episodes, created] }])),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    await user.type(field(form, 'Título'), '  Nuevo ');
    await user.type(field(form, 'Duración (minutos)'), '48');
    await user.type(field(form, 'Sinopsis (opcional)'), '   ');
    await user.type(field(form, 'URL del vídeo'), ' https://v.example.com/t2e3 ');
    await user.click(within(form).getByRole('button', { name: 'Añadir episodio' }));

    expect(within(form).getByRole('button', { name: 'Guardando...' })).toBeDisabled();
    expect(sentBody('POST', EPISODES_ROUTE)).toEqual({
      seasonNumber: 2,
      episodeNumber: 3,
      title: 'Nuevo',
      description: null,
      duration: 48,
      videoUrl: 'https://v.example.com/t2e3',
    });

    resolvePost(jsonResponse(created, 201));

    expect(await screen.findByText('Se ha añadido el episodio T2:E3 «Nuevo».')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(detailRequests()).toBe(1);
    expect(within(section()).getByText('Temporada 2 · 3 episodios')).toBeInTheDocument();
    expect(within(section()).getByRole('button', { name: 'Editar episodio T2:E3 Nuevo' })).toBeInTheDocument();
    await waitFor(() => expect(addButton()).toHaveFocus());
  });

  it('valida en el cliente con los mensajes del servidor, enfoca el primer error y no envía', async () => {
    routeFetch(fetchMock, {});
    const user = setupUser();
    renderSection(detail());

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    await fill(user, field(form, 'Número'), '0');
    await user.type(field(form, 'Duración (minutos)'), '601');
    await user.type(field(form, 'URL del vídeo'), 'http://v.example.com/x');
    await user.click(within(form).getByRole('button', { name: 'Añadir episodio' }));

    expect(field(form, 'Título')).toHaveFocus(); // el primero en pantalla
    expect(field(form, 'Número')).toHaveAccessibleDescription('El número de episodio debe estar entre 1 y 1000');
    expect(field(form, 'Título')).toHaveAccessibleDescription('El título es obligatorio');
    expect(field(form, 'Duración (minutos)')).toHaveAccessibleDescription('La duración debe estar entre 1 y 600 minutos');
    expect(field(form, 'URL del vídeo')).toHaveAccessibleDescription('La URL del vídeo debe empezar por https://');
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);

    // Al corregir un campo, su error desaparece.
    await fill(user, field(form, 'Número'), '3');
    expect(field(form, 'Número')).not.toHaveAccessibleDescription();
  });

  it('409 EPISODE_ALREADY_EXISTS: el mensaje del servidor junto a temporada y número, con el foco allí', async () => {
    routeFetch(fetchMock, {
      [`POST ${EPISODES_ROUTE}`]: () =>
        errorResponse(409, 'EPISODE_ALREADY_EXISTS', 'Ya existe el episodio 2 de la temporada 2'),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    await fill(user, field(form, 'Número'), '2');
    await user.type(field(form, 'Título'), 'Repetido');
    await user.type(field(form, 'Duración (minutos)'), '40');
    await user.type(field(form, 'URL del vídeo'), 'https://v.example.com/r');
    await user.click(within(form).getByRole('button', { name: 'Añadir episodio' }));

    const number = field(form, 'Número');
    await waitFor(() => expect(number).toHaveFocus());
    expect(number).toHaveAccessibleDescription('Ya existe el episodio 2 de la temporada 2');
    expect(number).toHaveAttribute('aria-invalid', 'true');
    expect(field(form, 'Temporada')).toHaveAttribute('aria-invalid', 'true');
    expect(field(form, 'Temporada')).toHaveAccessibleDescription('Ya existe el episodio 2 de la temporada 2');
    expect(within(form).getByRole('button', { name: 'Añadir episodio' })).toBeEnabled();
    expect(detailRequests()).toBe(0);

    // Cambiar la temporada quita el aviso de los dos campos (ya no se sabe si está ocupado).
    await fill(user, field(form, 'Temporada'), '3');
    expect(number).not.toHaveAttribute('aria-invalid');
    expect(field(form, 'Temporada')).not.toHaveAttribute('aria-invalid');
  });

  it('400 del servidor: cada error junto a su campo y foco al primero', async () => {
    routeFetch(fetchMock, {
      [`POST ${EPISODES_ROUTE}`]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
          validationErrors: { videoUrl: 'La URL del vídeo debe empezar por https://', duration: 'La duración debe estar entre 1 y 600 minutos' },
        }),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    await user.type(field(form, 'Título'), 'Algo');
    await user.type(field(form, 'Duración (minutos)'), '40');
    await user.type(field(form, 'URL del vídeo'), 'https://v.example.com/a');
    await user.click(within(form).getByRole('button', { name: 'Añadir episodio' }));

    await waitFor(() => expect(field(form, 'Duración (minutos)')).toHaveFocus());
    expect(field(form, 'Duración (minutos)')).toHaveAccessibleDescription('La duración debe estar entre 1 y 600 minutos');
    expect(field(form, 'URL del vídeo')).toHaveAccessibleDescription('La URL del vídeo debe empezar por https://');
    expect(within(form).getByText('Revisa los campos marcados.')).toBeInTheDocument();
  });

  it('«Cancelar» cierra sin enviar y devuelve el foco a «Añadir episodio»', async () => {
    routeFetch(fetchMock, {});
    const user = setupUser();
    renderSection(detail());

    await user.click(addButton());
    await user.click(within(dialog('Añadir episodio')).getByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(addButton()).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });
});

describe('SeriesEpisodesSection: editar', () => {
  it('carga el episodio, envía PUT y, si cambia de temporada, el foco sigue a su botón «Editar»', async () => {
    const original = makeEpisode({ id: 102, seasonNumber: 1, episodeNumber: 2 });
    const updated: Episode = { ...original, seasonNumber: 3, episodeNumber: 2, title: 'Reubicado', description: 'Ahora en la 3.' };
    routeFetch(fetchMock, {
      [`PUT ${EPISODES_ROUTE}/102`]: () => jsonResponse(updated),
      [DETAIL_ROUTE]: () =>
        jsonResponse(detail([season(1, [1, 3]), season(2, [1, 2]), { seasonNumber: 3, episodes: [updated] }])),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(within(section()).getByRole('button', { name: 'Editar episodio T1:E2 Episodio 2' }));
    const form = dialog('Editar episodio T1:E2');
    expect(form).toHaveAccessibleDescription('De la serie «Dark». Los cambios se ven en su página en cuanto los guardes.');
    expect(field(form, 'Temporada')).toHaveValue('1');
    expect(field(form, 'Número')).toHaveValue('2');
    expect(field(form, 'Título')).toHaveValue('Episodio 2');
    expect(field(form, 'Sinopsis (opcional)')).toHaveValue('Sinopsis del episodio 2.');
    expect(field(form, 'Duración (minutos)')).toHaveValue('45');
    expect(field(form, 'URL del vídeo')).toHaveValue('https://video.example/s1e2');

    // En la edición el número es el del episodio: cambiar de temporada no lo toca.
    await fill(user, field(form, 'Temporada'), '3');
    expect(field(form, 'Número')).toHaveValue('2');
    await fill(user, field(form, 'Título'), 'Reubicado');
    await fill(user, field(form, 'Sinopsis (opcional)'), 'Ahora en la 3.');
    await user.click(within(form).getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByText('Se han guardado los cambios del episodio T3:E2 «Reubicado».')).toBeInTheDocument();
    expect(sentBody('PUT', `${EPISODES_ROUTE}/102`)).toEqual({
      seasonNumber: 3,
      episodeNumber: 2,
      title: 'Reubicado',
      description: 'Ahora en la 3.',
      duration: 45,
      videoUrl: 'https://video.example/s1e2',
    });
    expect(within(section()).getAllByRole('heading', { level: 4 }).map((h) => h.textContent)).toEqual([
      'Temporada 1 · 2 episodios',
      'Temporada 2 · 2 episodios',
      'Temporada 3 · 1 episodio',
    ]);
    const moved = within(section()).getByRole('button', { name: 'Editar episodio T3:E2 Reubicado' });
    await waitFor(() => expect(moved).toHaveFocus());
  });

  it('404 al guardar (lo borró otra persona): cierra, lo explica, refresca y lleva el foco al encabezado', async () => {
    routeFetch(fetchMock, {
      [`PUT ${EPISODES_ROUTE}/101`]: () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Episodio no encontrado'),
      [DETAIL_ROUTE]: () => jsonResponse(detail([season(1, [2, 3]), season(2, [1, 2])])),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(within(section()).getByRole('button', { name: 'Editar episodio T1:E1 Episodio 1' }));
    await user.click(within(dialog('Editar episodio T1:E1')).getByRole('button', { name: 'Guardar cambios' }));

    expect(
      await screen.findByText('Ese episodio ya no existe: puede que lo haya borrado otra persona. Se ha actualizado la lista.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(within(section()).queryByRole('button', { name: 'Editar episodio T1:E1 Episodio 1' })).not.toBeInTheDocument();
    await waitFor(() => expect(within(section()).getByRole('heading', { level: 3, name: 'Episodios' })).toHaveFocus());
  });
});

describe('SeriesEpisodesSection: borrar', () => {
  it('pide confirmación, borra, refresca y lleva el foco al encabezado', async () => {
    let resolveDelete: (response: Response) => void = () => {};
    routeFetch(fetchMock, {
      [`DELETE ${EPISODES_ROUTE}/103`]: () => new Promise<Response>((resolve) => (resolveDelete = resolve)),
      [DETAIL_ROUTE]: () => jsonResponse(detail([season(1, [1, 2]), season(2, [1, 2])])),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(within(section()).getByRole('button', { name: 'Borrar episodio T1:E3 Episodio 3' }));
    const confirm = screen.getByRole('alertdialog', { name: '¿Borrar el episodio T1:E3 «Episodio 3»?' });
    expect(confirm).toHaveAccessibleDescription('Esta acción no se puede deshacer.');
    expect(within(confirm).getByRole('button', { name: 'Cancelar' })).toHaveFocus();

    await user.click(within(confirm).getByRole('button', { name: 'Sí, borrar episodio' }));
    expect(within(confirm).getByRole('button', { name: 'Procesando...' })).toBeDisabled();
    // No es optimista: la fila sigue hasta que responde el servidor.
    expect(within(section()).getByText('Temporada 1 · 3 episodios')).toBeInTheDocument();

    resolveDelete(noContentResponse());

    expect(await screen.findByText('Se ha borrado el episodio T1:E3 «Episodio 3».')).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(within(section()).getByText('Temporada 1 · 2 episodios')).toBeInTheDocument();
    await waitFor(() => expect(within(section()).getByRole('heading', { level: 3, name: 'Episodios' })).toHaveFocus());
  });

  it('borrar el último avisa (antes y después) de que la serie vuelve a estar oculta', async () => {
    routeFetch(fetchMock, {
      [`DELETE ${EPISODES_ROUTE}/101`]: () => noContentResponse(),
      [DETAIL_ROUTE]: () => jsonResponse(detail([])),
    });
    const user = setupUser();
    renderSection(detail([season(1, [1])]));

    await user.click(within(section()).getByRole('button', { name: 'Borrar episodio T1:E1 Episodio 1' }));
    const confirm = screen.getByRole('alertdialog');
    expect(confirm).toHaveAccessibleDescription(
      'Es el único episodio: la serie volverá a estar oculta para los usuarios. Esta acción no se puede deshacer.',
    );
    await user.click(within(confirm).getByRole('button', { name: 'Sí, borrar episodio' }));

    expect(
      await screen.findByText('Se ha borrado el episodio T1:E1 «Episodio 1». La serie ya no tiene episodios: vuelve a estar oculta.'),
    ).toBeInTheDocument();
    expect(within(section()).getByText(/los usuarios no la ven/)).toBeInTheDocument();
  });

  it('404 = ya estaba borrado: se informa sin tono de error y se refresca', async () => {
    routeFetch(fetchMock, {
      [`DELETE ${EPISODES_ROUTE}/201`]: () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Episodio no encontrado'),
      [DETAIL_ROUTE]: () => jsonResponse(detail([season(1, [1, 2, 3]), season(2, [2])])),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(within(section()).getByRole('button', { name: 'Borrar episodio T2:E1 Episodio 1' }));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Sí, borrar episodio' }));

    expect(await screen.findByText('El episodio T2:E1 «Episodio 1» ya no existía. Se ha actualizado la lista.')).toBeInTheDocument();
    expect(within(section()).getByText('Temporada 2 · 1 episodio')).toBeInTheDocument();
  });

  it('otro error: lo avisa, no refresca y la fila sigue', async () => {
    routeFetch(fetchMock, {
      [`DELETE ${EPISODES_ROUTE}/101`]: () => errorResponse(500, 'INTERNAL_ERROR', 'Error interno'),
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(within(section()).getByRole('button', { name: 'Borrar episodio T1:E1 Episodio 1' }));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Sí, borrar episodio' }));

    // Un 5xx no enseña el texto del servidor: `apiFetch` lo cambia por uno genérico.
    expect(await screen.findByText(SERVER_ERROR)).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(within(section()).getByRole('button', { name: 'Borrar episodio T1:E1 Episodio 1' })).toBeInTheDocument();
    expect(detailRequests()).toBe(0);
  });
});

describe('SeriesEpisodesSection: refresco fallido', () => {
  it('el cambio ya está guardado: se confirma, se avisa de que la lista puede estar desactualizada y «Reintentar» la pide', async () => {
    const created = makeEpisode({ id: 60, seasonNumber: 2, episodeNumber: 3, title: 'Tres' });
    let detailCalls = 0;
    routeFetch(fetchMock, {
      [`POST ${EPISODES_ROUTE}`]: () => jsonResponse(created, 201),
      [DETAIL_ROUTE]: () => {
        detailCalls += 1;
        return detailCalls === 1
          ? errorResponse(503, 'SERVICE_UNAVAILABLE', 'Servicio no disponible')
          : jsonResponse(detail([season(1, [1, 2, 3]), season(2, [1, 2, 3])]));
      },
    });
    const user = setupUser();
    renderSection(detail());

    await user.click(addButton());
    const form = dialog('Añadir episodio');
    await user.type(field(form, 'Título'), 'Tres');
    await user.type(field(form, 'Duración (minutos)'), '45');
    await user.type(field(form, 'URL del vídeo'), 'https://v.example.com/3');
    await user.click(within(form).getByRole('button', { name: 'Añadir episodio' }));

    expect(await screen.findByText('Se ha añadido el episodio T2:E3 «Tres».')).toBeInTheDocument();
    const alert = within(section()).getByRole('alert');
    expect(alert).toHaveTextContent(
      `No se pudo actualizar la lista de episodios: puede estar desactualizada. ${SERVER_ERROR}`,
    );

    await user.click(within(alert).getByRole('button', { name: 'Reintentar' }));

    expect(await within(section()).findByText('Temporada 2 · 3 episodios')).toBeInTheDocument();
    expect(within(section()).queryByRole('alert')).not.toBeInTheDocument();
  });
});
