/**
 * Tests del formulario de series del panel (`SeriesFormPage`), alta y edición.
 *
 * Protegen lo propio de las series frente al formulario de películas: los
 * mensajes exactos de `SeriesRequest`, el año de fin opcional (vacío = en
 * emisión, `null` en la petición) y su error de "anterior al estreno", que el
 * botón no se pueda pulsar hasta tener los géneros, que **al crear se aterrice
 * en la edición de la serie nueva** con el aviso de añadir episodios, que al
 * guardar cambios se quede en la página, que la edición cargue la vista de
 * gestión (`GET /api/admin/series/{id}`, que devuelve también las series
 * vacías) y que monte la sección «Episodios». `fetch` está simulado; sesión,
 * avisos y router son los reales.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Genre } from '../../lib/types';
import { installManualTimers, passTime } from '../../test/fakeTimers';
import { errorResponse, jsonResponse, makeSeriesDetail, renderWithProviders, routeFetch } from '../../test/helpers';
import { SeriesFormPage } from './SeriesFormPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const GENRES: Genre[] = [
  { id: 2, name: 'Drama' },
  { id: 1, name: 'Ciencia ficción' },
  { id: 3, name: 'Acción' },
];
const GENRES_ROUTE = { 'GET /api/genres': () => jsonResponse(GENRES) };

const dark = makeSeriesDetail({
  id: 4,
  title: 'Dark',
  description: 'Un pueblo alemán y sus secretos.',
  releaseYear: 2017,
  endYear: 2020,
  imageUrl: '/covers/dark.webp',
  genres: [
    { id: 1, name: 'Ciencia ficción' },
    { id: 2, name: 'Drama' },
  ],
});

function renderForm(route: string, routeState?: unknown) {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/series" element={<p>Listado de series</p>} />
      <Route path="/admin/series/nueva" element={<SeriesFormPage />} />
      <Route path="/admin/series/:id/editar" element={<SeriesFormPage />} />
      <Route path="/admin/generos" element={<p>Pestaña de géneros</p>} />
    </Routes>,
    { route, session: true, routeState },
  );
}

const field = {
  title: () => screen.getByLabelText('Título'),
  description: () => screen.getByLabelText('Sinopsis'),
  releaseYear: () => screen.getByLabelText('Año de estreno'),
  endYear: () => screen.getByLabelText('Año de fin (opcional)'),
  image: () => screen.getByLabelText('URL de la portada'),
};
/** user-event sin pausas entre acciones (formulario grande; ver `MovieFormPage.test.tsx`). */
const setupUser = () => userEvent.setup({ delay: null });

const genresGroup = () => screen.getByRole('group', { name: 'Géneros' });
const submitNew = () => screen.getByRole('button', { name: 'Crear serie' });

/** Sustituye el contenido de un campo pegando el texto (más rápido que teclear letra a letra). */
async function fill(user: ReturnType<typeof setupUser>, input: HTMLElement, text: string) {
  await user.clear(input);
  await user.click(input);
  await user.paste(text);
}

/** Rellena un alta válida, en emisión (con espacios de más en los extremos, que deben recortarse al enviar). */
async function fillValid(user: ReturnType<typeof setupUser>) {
  await fill(user, field.title(), '  Severance ');
  await fill(user, field.description(), 'Empleados con la memoria dividida.');
  await fill(user, field.releaseYear(), '2022');
  await fill(user, field.image(), ' https://img.example.com/severance.webp ');
  await user.click(within(genresGroup()).getByRole('checkbox', { name: 'Drama' }));
}

/** Cuerpo JSON de la petición `método url`. */
function sentBody(method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(([input, init]) => String(input) === url && init?.method === method);
  if (!call) throw new Error(`No se envió ${method} ${url}`);
  return JSON.parse(String(call[1]?.body));
}

const sentAny = (method: string) => fetchMock.mock.calls.some(([, init]) => init?.method === method);

describe('SeriesFormPage: alta', () => {
  it('muestra el formulario accesible: año de fin opcional con su ayuda y géneros ordenados', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    renderForm('/admin/series/nueva');

    expect(screen.getByRole('heading', { level: 2, name: 'Nueva serie' })).toBeInTheDocument();
    expect(document.title).toBe('Nueva serie · Administración — StreamBox');
    for (const input of Object.values(field)) expect(input()).toBeInTheDocument();
    expect(field.endYear()).toHaveAccessibleDescription('Déjalo vacío si sigue en emisión.');

    const checkboxes = await within(genresGroup()).findAllByRole('checkbox');
    expect(checkboxes.map((box) => box.closest('label')?.textContent)).toEqual(['Acción', 'Ciencia ficción', 'Drama']);
    // Ni rastro de la sección de episodios: no existe hasta que se crea la serie.
    expect(screen.queryByRole('heading', { name: 'Episodios' })).not.toBeInTheDocument();
  });

  it('no deja enviar hasta que cargan los géneros, y lo explica', async () => {
    let resolveGenres: (response: Response) => void = () => {};
    routeFetch(fetchMock, { 'GET /api/genres': () => new Promise<Response>((resolve) => (resolveGenres = resolve)) });
    renderForm('/admin/series/nueva');

    expect(submitNew()).toBeDisabled();
    expect(submitNew()).toHaveAccessibleDescription('Podrás guardar cuando se carguen los géneros.');

    resolveGenres(jsonResponse(GENRES));

    await waitFor(() => expect(submitNew()).toBeEnabled());
    expect(screen.queryByText('Podrás guardar cuando se carguen los géneros.')).not.toBeInTheDocument();
  });

  it('enviar vacío marca cada campo con el mensaje exacto del servidor, enfoca el primero y no envía', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/series/nueva');
    await within(genresGroup()).findAllByRole('checkbox');

    await user.click(submitNew());

    expect(field.title()).toHaveAccessibleDescription('El título es obligatorio');
    expect(field.description()).toHaveAccessibleDescription(/La descripción es obligatoria/);
    expect(field.releaseYear()).toHaveAccessibleDescription('El año de estreno es obligatorio');
    // El año de fin vacío es válido: conserva su ayuda.
    expect(field.endYear()).not.toHaveAttribute('aria-invalid');
    expect(field.image()).toHaveAccessibleDescription('La URL de la imagen es obligatoria');
    expect(genresGroup()).toHaveAccessibleDescription('Indica al menos un género');
    expect(field.title()).toHaveFocus();
    expect(sentAny('POST')).toBe(false);
  });

  it('un año de fin anterior al estreno se marca en el año de fin y lo enfoca', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/series/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await fill(user, field.endYear(), '2019');
    await user.click(submitNew());

    expect(field.endYear()).toHaveAttribute('aria-invalid', 'true');
    expect(field.endYear()).toHaveAccessibleDescription('El año de finalización no puede ser anterior al año de estreno');
    expect(field.endYear()).toHaveFocus();
    expect(sentAny('POST')).toBe(false);
  });

  it('una portada http:// se rechaza junto al campo con el mismo mensaje que en películas', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/series/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await fill(user, field.image(), 'http://img.example.com/a.jpg');
    await user.click(submitNew());

    expect(field.image()).toHaveAccessibleDescription(
      'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)',
    );
    expect(field.image()).toHaveFocus();
  });

  it('crea la serie con los datos limpios (sin año de fin = null) y aterriza en su edición con el aviso', async () => {
    const created = makeSeriesDetail({
      id: 9,
      title: 'Severance',
      description: 'Empleados con la memoria dividida.',
      releaseYear: 2022,
      endYear: null,
      imageUrl: 'https://img.example.com/severance.webp',
      genres: [{ id: 2, name: 'Drama' }],
      seasons: [],
    });
    let resolvePost: (response: Response) => void = () => {};
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'POST /api/series': () => new Promise<Response>((resolve) => (resolvePost = resolve)),
      'GET /api/admin/series/9': () => jsonResponse(created),
    });
    const user = setupUser();
    renderForm('/admin/series/nueva', { returnTo: '/admin/series?q=sev' });
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await user.click(submitNew());

    expect(screen.getByRole('button', { name: 'Guardando...' })).toBeDisabled();
    expect(sentBody('POST', '/api/series')).toEqual({
      title: 'Severance',
      description: 'Empleados con la memoria dividida.',
      releaseYear: 2022,
      endYear: null,
      imageUrl: 'https://img.example.com/severance.webp',
      genreIds: [2],
    });

    resolvePost(jsonResponse(created, 201));

    expect(await screen.findByText('ruta:/admin/series/9/editar')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: 'Editar «Severance»' })).toBeInTheDocument();
    expect(screen.getByText('Serie creada. Añade episodios para que sea visible.')).toBeInTheDocument();
    // La edición carga los datos recién guardados y explica que, sin episodios, nadie la ve.
    expect(field.title()).toHaveValue('Severance');
    expect(field.endYear()).toHaveValue('');
    const episodes = screen.getByRole('region', { name: 'Episodios' });
    expect(within(episodes).getByText(/los usuarios no la ven/)).toBeInTheDocument();
    // Se conserva la vuelta al listado del que se vino.
    for (const link of screen.getAllByRole('link', { name: 'Volver al listado' })) {
      expect(link).toHaveAttribute('href', '/admin/series?q=sev');
    }
  });

  it('400 del servidor: el error de cada campo junto a él (también el del año de fin) y foco al primero', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'POST /api/series': () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
          validationErrors: { endYear: 'El año de finalización no puede ser anterior al año de estreno' },
        }),
    });
    const user = setupUser();
    renderForm('/admin/series/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await user.click(submitNew());

    expect(await screen.findByText('Revisa los campos marcados.')).toHaveAttribute('role', 'alert');
    expect(field.endYear()).toHaveAccessibleDescription('El año de finalización no puede ser anterior al año de estreno');
    expect(field.endYear()).toHaveFocus();
    expect(screen.getByText('ruta:/admin/series/nueva')).toBeInTheDocument();
  });

  it('sin géneros: explica que cada serie necesita uno y enlaza a la pestaña Géneros', async () => {
    routeFetch(fetchMock, { 'GET /api/genres': () => jsonResponse([]) });
    renderForm('/admin/series/nueva');

    const link = await within(genresGroup()).findByRole('link', { name: 'Crea uno en la pestaña Géneros' });
    expect(link).toHaveAttribute('href', '/admin/generos');
    expect(genresGroup()).toHaveTextContent('Todavía no hay géneros y cada serie necesita al menos uno.');
  });

  it('«Cancelar» vuelve al listado de series sin enviar nada', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/series/nueva');

    await user.click(screen.getByRole('link', { name: 'Cancelar' }));

    expect(screen.getByText('Listado de series')).toBeInTheDocument();
    expect(sentAny('POST')).toBe(false);
  });
});

describe('SeriesFormPage: vista previa de la portada', () => {
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());

  it('carga la portada válida tras 400 ms sin escribir (la misma vista previa que películas)', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/series/nueva');
    const preview = () => screen.getByRole('complementary', { name: 'Vista previa de la portada' });

    await fill(user, field.image(), '/covers/dark.webp');
    passTime(399);
    expect(preview().querySelector('img')).toBeNull();
    passTime(1);
    expect(preview().querySelector('img')).toHaveAttribute('src', '/covers/dark.webp');
  });
});

describe('SeriesFormPage: edición', () => {
  it('carga la serie de la vista de gestión: campos, géneros, título y resumen de episodios', async () => {
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/admin/series/4': () => jsonResponse(dark) });
    renderForm('/admin/series/4/editar');

    expect(screen.getByText('Cargando la serie...')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: 'Editar «Dark»' })).toBeInTheDocument();
    expect(document.title).toBe('Editar serie · Administración — StreamBox');
    expect(field.title()).toHaveValue('Dark');
    expect(field.description()).toHaveValue('Un pueblo alemán y sus secretos.');
    expect(field.releaseYear()).toHaveValue('2017');
    expect(field.endYear()).toHaveValue('2020');
    expect(field.image()).toHaveValue('/covers/dark.webp');
    expect(await within(genresGroup()).findByRole('checkbox', { name: 'Ciencia ficción' })).toBeChecked();
    expect(within(genresGroup()).getByRole('checkbox', { name: 'Acción' })).not.toBeChecked();

    // Sección «Episodios» bajo el formulario: resumen y temporadas (su gestión, en `SeriesEpisodesSection.test.tsx`).
    const episodes = screen.getByRole('region', { name: 'Episodios' });
    expect(within(episodes).getByText('5 episodios en 2 temporadas')).toBeInTheDocument();
    expect(within(episodes).getAllByRole('heading', { level: 4 }).map((heading) => heading.textContent)).toEqual([
      'Temporada 1 · 3 episodios',
      'Temporada 2 · 2 episodios',
    ]);
  });

  it('guarda con PUT (año de fin vaciado = null) y se queda en la página con el título nuevo', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'GET /api/admin/series/4': () => jsonResponse(dark),
      'PUT /api/series/4': ({ init }) => {
        const body = JSON.parse(String(init.body)) as { title: string; endYear: number | null };
        return jsonResponse({ ...dark, title: body.title, endYear: body.endYear });
      },
    });
    const user = setupUser();
    renderForm('/admin/series/4/editar', { returnTo: '/admin/series?page=2' });
    await screen.findByRole('heading', { name: 'Editar «Dark»' });
    await within(genresGroup()).findAllByRole('checkbox');

    await fill(user, field.title(), 'Dark (versión extendida)');
    await user.clear(field.endYear());
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByText('Se han guardado los cambios de «Dark (versión extendida)».')).toBeInTheDocument();
    expect(sentBody('PUT', '/api/series/4')).toMatchObject({ title: 'Dark (versión extendida)', endYear: null, genreIds: [1, 2] });
    expect(screen.getByRole('heading', { level: 2, name: 'Editar «Dark (versión extendida)»' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/admin/series/4/editar')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeEnabled();
    // La vuelta al listado sigue apuntando a donde se estaba.
    expect(screen.getAllByRole('link', { name: 'Volver al listado' })[0]).toHaveAttribute('href', '/admin/series?page=2');
  });

  it('404 al cargar: explica que no existe y enlaza al listado de series', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'GET /api/admin/series/99': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada'),
    });
    renderForm('/admin/series/99/editar');

    expect(await screen.findByRole('heading', { name: 'No se encontró la serie' })).toBeInTheDocument();
    for (const link of screen.getAllByRole('link', { name: 'Volver al listado' })) {
      expect(link).toHaveAttribute('href', '/admin/series');
    }
    expect(screen.queryByRole('button', { name: 'Guardar cambios' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Episodios' })).not.toBeInTheDocument();
  });

  it('un id que no es un número ni siquiera se pide al servidor', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    renderForm('/admin/series/abc/editar');

    expect(screen.getByRole('heading', { name: 'No se encontró la serie' })).toBeInTheDocument();
    await waitFor(() => expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain('/api/genres'));
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/admin/series'))).toBe(false);
  });

  it('otro error al cargar: «Reintentar» vuelve a pedir la serie', async () => {
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/admin/series/4': () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = setupUser();
    renderForm('/admin/series/4/editar');

    expect(await screen.findByRole('heading', { name: 'No se pudo cargar la serie' })).toBeInTheDocument();
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/admin/series/4': () => jsonResponse(dark) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { name: 'Editar «Dark»' })).toBeInTheDocument();
  });

  it('404 al guardar (la serie se borró mientras tanto): se explica en el formulario sin perder lo escrito', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'GET /api/admin/series/4': () => jsonResponse(dark),
      'PUT /api/series/4': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada'),
    });
    const user = setupUser();
    renderForm('/admin/series/4/editar');
    await screen.findByRole('heading', { name: 'Editar «Dark»' });
    await within(genresGroup()).findAllByRole('checkbox');

    await fill(user, field.title(), 'Otro título');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByText('Serie no encontrada')).toHaveAttribute('role', 'alert');
    expect(field.title()).toHaveValue('Otro título');
  });
});
