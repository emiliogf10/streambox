/**
 * Tests del formulario de películas del panel (`MovieFormPage`), alta y edición.
 *
 * Protegen que no salga hacia el servidor nada que este rechazaría (URL
 * `http://`, `javascript:`, credenciales, sin géneros...), que lo que sí sale
 * vaya limpio (textos recortados, números como números), que los errores del
 * servidor aparezcan junto a su campo, que la vista previa nunca cargue una URL
 * inválida y que la edición cargue los datos (o explique que la película no
 * existe). `fetch` está simulado; sesión, avisos y router son los reales.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Genre } from '../../lib/types';
import { installManualTimers, passTime } from '../../test/fakeTimers';
import { errorResponse, jsonResponse, makeMovie, renderWithProviders, routeFetch } from '../../test/helpers';
import { MovieFormPage } from './MovieFormPage';

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

const interstellar = makeMovie({
  id: 7,
  title: 'Interstellar',
  description: 'Un viaje a través de un agujero de gusano.',
  duration: 169,
  releaseYear: 2014,
  imageUrl: '/covers/interstellar.webp',
  videoUrl: 'https://videos.example.com/interstellar',
  genres: [
    { id: 1, name: 'Ciencia ficción' },
    { id: 2, name: 'Drama' },
  ],
});

/** Escribe la búsqueda de la ruta actual para comprobar adónde se vuelve. */
function SearchProbe() {
  return <p>{`busqueda:${useLocation().search}`}</p>;
}

function renderForm(route: string, routeState?: unknown) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/admin/peliculas" element={<p>Listado de películas</p>} />
        <Route path="/admin/peliculas/nueva" element={<MovieFormPage />} />
        <Route path="/admin/peliculas/:id/editar" element={<MovieFormPage />} />
        <Route path="/admin/generos" element={<p>Pestaña de géneros</p>} />
      </Routes>
      <SearchProbe />
    </>,
    { route, session: true, routeState },
  );
}

const field = {
  title: () => screen.getByLabelText('Título'),
  description: () => screen.getByLabelText('Sinopsis'),
  duration: () => screen.getByLabelText('Duración (minutos)'),
  year: () => screen.getByLabelText('Año de estreno'),
  image: () => screen.getByLabelText('URL de la portada'),
  video: () => screen.getByLabelText('URL del vídeo'),
};
/**
 * `user-event` sin la pausa que mete por defecto entre acción y acción (`delay: null`). Este formulario es
 * grande y cada pulsación lo vuelve a pintar entero: con la pausa, los tests más largos se acercaban al
 * límite de 5 s cuando corre la suite completa en paralelo. No cambia qué se comprueba.
 */
const setupUser = () => userEvent.setup({ delay: null });

const genresGroup = () => screen.getByRole('group', { name: 'Géneros' });
const submitNew = () => screen.getByRole('button', { name: 'Crear película' });

/**
 * Sustituye el contenido de un campo pegando el texto. Más rápido que escribir letra a letra (con la suite
 * completa en paralelo, teclear siete campos rozaba el límite de 5 s por test) y aquí no se prueba nada
 * que dependa de cada pulsación.
 */
async function fill(user: ReturnType<typeof setupUser>, input: HTMLElement, text: string) {
  await user.clear(input);
  await user.click(input);
  await user.paste(text);
}

/** Rellena un alta válida (con espacios de más en los extremos, que deben recortarse al enviar). */
async function fillValid(user: ReturnType<typeof setupUser>) {
  await fill(user, field.title(), '  Interstellar ');
  await fill(user, field.description(), 'Un viaje a través de un agujero de gusano.');
  await fill(user, field.duration(), '169');
  await fill(user, field.year(), '2014');
  await fill(user, field.image(), ' /covers/interstellar.webp ');
  await fill(user, field.video(), 'https://videos.example.com/interstellar');
  await user.click(within(genresGroup()).getByRole('checkbox', { name: 'Ciencia ficción' }));
  await user.click(within(genresGroup()).getByRole('checkbox', { name: 'Drama' }));
}

/** Cuerpo JSON de la petición `método url`. */
function sentBody(method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(([input, init]) => String(input) === url && init?.method === method);
  if (!call) throw new Error(`No se envió ${method} ${url}`);
  return JSON.parse(String(call[1]?.body));
}

describe('MovieFormPage: alta', () => {
  it('muestra el formulario accesible con los géneros de la API, ordenados por nombre', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    renderForm('/admin/peliculas/nueva');

    expect(screen.getByRole('heading', { level: 2, name: 'Nueva película' })).toBeInTheDocument();
    expect(document.title).toBe('Nueva película · Administración — StreamBox');
    for (const input of Object.values(field)) expect(input()).toBeInTheDocument();
    expect(field.image()).toHaveAccessibleDescription(/https:\/\/ o una portada propia de la carpeta \/covers/);

    const checkboxes = await within(genresGroup()).findAllByRole('checkbox');
    expect(checkboxes.map((box) => box.closest('label')?.textContent)).toEqual(['Acción', 'Ciencia ficción', 'Drama']);
    expect(genresGroup()).toHaveAccessibleDescription('Elige al menos uno.');
  });

  it('enviar vacío marca todos los campos, enfoca el primero y no llama al servidor', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    await within(genresGroup()).findAllByRole('checkbox');

    await user.click(submitNew());

    expect(field.title()).toHaveAccessibleDescription('El título es obligatorio');
    expect(field.description()).toHaveAccessibleDescription(/La sinopsis es obligatoria/);
    expect(field.duration()).toHaveAccessibleDescription('Indica la duración en minutos');
    expect(field.year()).toHaveAccessibleDescription('Indica el año de estreno');
    expect(field.image()).toHaveAccessibleDescription('La URL de la portada es obligatoria');
    expect(field.video()).toHaveAccessibleDescription('La URL del vídeo es obligatoria');
    expect(genresGroup()).toHaveAccessibleDescription('Elige al menos un género');
    expect(screen.getByText('Elige al menos un género')).toHaveAttribute('role', 'alert');
    expect(field.title()).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it('enviar mientras cargan los géneros: los errores siguen a la vista cuando llegan las casillas', async () => {
    // Los géneros llegan aparte y pueden tardar: el formulario no debe reiniciar sus errores (ni volver a
    // montarse) cuando por fin se pintan las casillas. Era una de las hipótesis del fallo intermitente del
    // E2E responsive a 375 px; la causa resultó ser otra (el clic caía durante el salto de maquetación).
    let resolveGenres: (response: Response) => void = () => {};
    routeFetch(fetchMock, { 'GET /api/genres': () => new Promise<Response>((resolve) => (resolveGenres = resolve)) });
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    expect(within(genresGroup()).getByText('Cargando géneros...')).toBeInTheDocument();

    await user.click(submitNew());
    expect(genresGroup()).toHaveAccessibleDescription('Elige al menos un género');
    expect(field.title()).toHaveFocus();

    resolveGenres(jsonResponse(GENRES));

    expect(await within(genresGroup()).findByRole('checkbox', { name: 'Drama' })).toBeInTheDocument();
    expect(genresGroup()).toHaveAccessibleDescription('Elige al menos un género');
    expect(field.title()).toHaveAccessibleDescription('El título es obligatorio');
    expect(field.title()).toHaveFocus();
  });

  it.each([
    ['portada http://', 'image', 'http://img.example.com/a.jpg', 'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)'],
    ['portada javascript:', 'image', 'javascript:alert(1)', 'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)'],
    ['portada con credenciales', 'image', 'https://user:pw@img.example.com/a.jpg', 'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)'],
    ['portada fuera de /covers', 'image', '/covers/../x', 'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)'],
    ['vídeo ftp:', 'video', 'ftp://videos.example.com/x', 'La URL del vídeo debe empezar por https://'],
    ['vídeo http://', 'video', 'http://videos.example.com/x', 'La URL del vídeo debe empezar por https://'],
  ] as const)('rechaza %s junto al campo y enfoca ese campo', async (_caso, which, url, message) => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    const input = which === 'image' ? field.image() : field.video();
    await fill(user, input, url);
    await user.click(submitNew());

    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(message);
    expect(input).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it('URL de más de 500 caracteres: avisa de la longitud', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');

    await user.click(field.image());
    await user.paste(`https://img.example.com/${'a'.repeat(480)}`);
    await user.click(submitNew());

    expect(field.image()).toHaveAccessibleDescription('La URL de la imagen no puede superar los 500 caracteres');
  });

  it('crea la película con los datos limpios, bloquea el botón mientras envía, avisa y vuelve al listado', async () => {
    let resolvePost: (response: Response) => void = () => {};
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'POST /api/movies': () => new Promise<Response>((resolve) => (resolvePost = resolve)),
    });
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await user.click(submitNew());

    expect(screen.getByRole('button', { name: 'Guardando...' })).toBeDisabled();
    expect(sentBody('POST', '/api/movies')).toEqual({
      title: 'Interstellar',
      description: 'Un viaje a través de un agujero de gusano.',
      duration: 169,
      releaseYear: 2014,
      imageUrl: '/covers/interstellar.webp',
      videoUrl: 'https://videos.example.com/interstellar',
      genreIds: [1, 2],
    });

    resolvePost(jsonResponse(interstellar, 201));

    expect(await screen.findByText('Listado de películas')).toBeInTheDocument();
    expect(screen.getByText('«Interstellar» se ha añadido al catálogo.')).toBeInTheDocument();
  });

  it('400 del servidor: cada mensaje junto a su campo, aviso general y foco al primero', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'POST /api/movies': () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
          validationErrors: {
            videoUrl: 'La URL del vídeo debe empezar por https://',
            title: 'El título no puede superar los 150 caracteres',
          },
        }),
    });
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await user.click(submitNew());

    expect(await screen.findByText('Revisa los campos marcados.')).toHaveAttribute('role', 'alert');
    expect(field.title()).toHaveAccessibleDescription('El título no puede superar los 150 caracteres');
    expect(field.video()).toHaveAccessibleDescription('La URL del vídeo debe empezar por https://');
    expect(field.title()).toHaveFocus();
    expect(screen.queryByText('Listado de películas')).not.toBeInTheDocument();
  });

  it('un 403 (ya no es administrador) se explica en el formulario sin perder lo escrito', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'POST /api/movies': () => errorResponse(403, 'ACCESS_DENIED', 'Acceso denegado'),
    });
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    await within(genresGroup()).findAllByRole('checkbox');
    await fillValid(user);

    await user.click(submitNew());

    expect(await screen.findByText('No tienes permisos para realizar esta acción.')).toHaveAttribute('role', 'alert');
    expect(field.title()).toHaveValue('  Interstellar ');
    expect(submitNew()).toBeEnabled();
  });

  it('sin géneros: explica que hay que crear uno y enlaza a la pestaña Géneros', async () => {
    routeFetch(fetchMock, { 'GET /api/genres': () => jsonResponse([]) });
    renderForm('/admin/peliculas/nueva');

    const link = await within(genresGroup()).findByRole('link', { name: 'Crea uno en la pestaña Géneros' });
    expect(link).toHaveAttribute('href', '/admin/generos');
    expect(within(genresGroup()).queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('si fallan los géneros, «Reintentar» los vuelve a pedir', async () => {
    routeFetch(fetchMock, { 'GET /api/genres': () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');

    expect(await within(genresGroup()).findByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    routeFetch(fetchMock, GENRES_ROUTE);
    await user.click(within(genresGroup()).getByRole('button', { name: 'Reintentar' }));

    expect(await within(genresGroup()).findByRole('checkbox', { name: 'Drama' })).toBeInTheDocument();
  });

  it('el contador de la sinopsis avisa (con texto, no solo color) al pasarse de 1000 caracteres', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');

    expect(field.description()).toHaveAccessibleDescription('0 de 1000 caracteres');
    await user.click(field.description());
    await user.paste('s'.repeat(1003));

    expect(field.description()).toHaveAccessibleDescription('1003 de 1000 caracteres: sobran 3');
    expect(screen.getByText('1003 / 1000 · sobran 3')).toBeInTheDocument();
  });

  it('«Cancelar» vuelve al listado sin enviar nada', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');

    await user.click(screen.getByRole('link', { name: 'Cancelar' }));

    expect(screen.getByText('Listado de películas')).toBeInTheDocument();
  });
});

describe('MovieFormPage: vista previa de la portada', () => {
  /** Espera tras la última tecla antes de cargar la portada (`PREVIEW_DEBOUNCE_MS` de `MovieFormPage`). */
  const PREVIEW_DEBOUNCE_MS = 400;
  const preview = () => screen.getByRole('complementary', { name: 'Vista previa de la portada' });

  // El retraso de la vista previa va con reloj falso: se comprueba exacto (nada a los 399 ms, la imagen a
  // los 400) y sin depender de lo cargada que esté la máquina. Antes se esperaba el retraso REAL dentro de
  // un `waitFor` de 1 s, el mismo patrón que hacía inestable el buscador del listado (ver `test/fakeTimers.ts`).
  // `setupUser` ya crea user-event con `delay: null`, como exige el reloj falso.
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());

  it('carga la portada (con un pequeño retraso) cuando la URL es válida, también una portada propia', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');
    expect(within(preview()).getByText('Escribe la URL de la portada para verla aquí.')).toBeInTheDocument();

    await fill(user, field.title(), 'Interstellar');
    await fill(user, field.image(), '/covers/ok.webp');

    // Mientras no pasan 400 ms sin escribir no se intenta descargar nada...
    passTime(PREVIEW_DEBOUNCE_MS - 1);
    expect(preview().querySelector('img')).toBeNull();
    // ...y después se carga la portada escrita.
    passTime(1);
    expect(preview().querySelector('img')).toHaveAttribute('src', '/covers/ok.webp');
    expect(within(preview()).getByText(/Así se verá en el catálogo/)).toBeInTheDocument();
  });

  it('con una URL inválida (http://) NO la carga: muestra el respaldo con el título', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    const user = setupUser();
    renderForm('/admin/peliculas/nueva');

    await fill(user, field.title(), 'Interstellar');
    await fill(user, field.image(), 'http://img.example.com/a.jpg');
    passTime(PREVIEW_DEBOUNCE_MS);

    expect(within(preview()).getByText(/La URL aún no es válida/)).toBeInTheDocument();
    expect(preview().querySelector('img')).toBeNull();
    // El respaldo de MoviePoster ocupa su lugar (con el mismo nombre accesible que tendría la imagen).
    expect(within(preview()).getByRole('img', { name: 'Vista previa de la portada' }).tagName).toBe('DIV');
  });
});

describe('MovieFormPage: edición', () => {
  it('carga la película: rellena los campos, marca sus géneros y titula con su nombre', async () => {
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/movies/7': () => jsonResponse(interstellar) });
    renderForm('/admin/peliculas/7/editar');

    expect(screen.getByText('Cargando la película...')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: 'Editar «Interstellar»' })).toBeInTheDocument();
    expect(document.title).toBe('Editar película · Administración — StreamBox');
    expect(field.title()).toHaveValue('Interstellar');
    expect(field.description()).toHaveValue('Un viaje a través de un agujero de gusano.');
    expect(field.duration()).toHaveValue('169');
    expect(field.year()).toHaveValue('2014');
    expect(field.image()).toHaveValue('/covers/interstellar.webp');
    expect(field.video()).toHaveValue('https://videos.example.com/interstellar');
    expect(await within(genresGroup()).findByRole('checkbox', { name: 'Ciencia ficción' })).toBeChecked();
    expect(within(genresGroup()).getByRole('checkbox', { name: 'Drama' })).toBeChecked();
    expect(within(genresGroup()).getByRole('checkbox', { name: 'Acción' })).not.toBeChecked();
    expect(field.image()).not.toHaveAttribute('aria-invalid');
  });

  it('guarda con PUT y vuelve al listado en la misma búsqueda y página de la que se vino', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'GET /api/movies/7': () => jsonResponse(interstellar),
      'PUT /api/movies/7': () => jsonResponse({ ...interstellar, duration: 170 }),
    });
    const user = setupUser();
    renderForm('/admin/peliculas/7/editar', { returnTo: '/admin/peliculas?q=inter&page=2' });
    await screen.findByRole('heading', { name: 'Editar «Interstellar»' });
    await within(genresGroup()).findAllByRole('checkbox');

    await user.clear(field.duration());
    await user.type(field.duration(), '170');
    await user.click(within(genresGroup()).getByRole('checkbox', { name: 'Drama' }));
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByText('Listado de películas')).toBeInTheDocument();
    expect(screen.getByText('busqueda:?q=inter&page=2')).toBeInTheDocument();
    expect(sentBody('PUT', '/api/movies/7')).toMatchObject({ duration: 170, genreIds: [1] });
    expect(screen.getByText('Se han guardado los cambios de «Interstellar».')).toBeInTheDocument();
  });

  it('una "vuelta" que no es del listado se ignora (vuelve al listado por defecto)', async () => {
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/movies/7': () => jsonResponse(interstellar) });
    const user = setupUser();
    renderForm('/admin/peliculas/7/editar', { returnTo: 'https://malo.example/' });
    await screen.findByRole('heading', { name: 'Editar «Interstellar»' });

    await user.click(screen.getByRole('link', { name: 'Cancelar' }));

    expect(screen.getByText('Listado de películas')).toBeInTheDocument();
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
  });

  it('una película antigua con URL http:// se marca nada más abrirla, para que se corrija antes de guardar', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'GET /api/movies/7': () =>
        jsonResponse({ ...interstellar, imageUrl: 'http://img.example.com/a.jpg', videoUrl: 'http://v.example.com/x' }),
    });
    const user = setupUser();
    renderForm('/admin/peliculas/7/editar');

    await screen.findByRole('heading', { name: 'Editar «Interstellar»' });
    expect(field.image()).toHaveAccessibleDescription(
      'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)',
    );
    expect(field.video()).toHaveAccessibleDescription('La URL del vídeo debe empezar por https://');

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
    expect(field.image()).toHaveFocus();
  });

  it('404 al cargar: explica que no existe y enlaza al listado', async () => {
    routeFetch(fetchMock, {
      ...GENRES_ROUTE,
      'GET /api/movies/99': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Película no encontrada'),
    });
    renderForm('/admin/peliculas/99/editar');

    expect(await screen.findByRole('heading', { name: 'No se encontró la película' })).toBeInTheDocument();
    // El enlace de arriba y la acción del estado vacío llevan al listado.
    for (const link of screen.getAllByRole('link', { name: 'Volver al listado' })) {
      expect(link).toHaveAttribute('href', '/admin/peliculas');
    }
    expect(screen.queryByRole('button', { name: 'Guardar cambios' })).not.toBeInTheDocument();
  });

  it('un id que no es un número ni siquiera se pide al servidor', async () => {
    routeFetch(fetchMock, GENRES_ROUTE);
    renderForm('/admin/peliculas/abc/editar');

    expect(screen.getByRole('heading', { name: 'No se encontró la película' })).toBeInTheDocument();
    await waitFor(() => expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain('/api/genres'));
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/movies'))).toBe(false);
  });

  it('otro error al cargar: «Reintentar» vuelve a pedir la película', async () => {
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/movies/7': () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = setupUser();
    renderForm('/admin/peliculas/7/editar');

    expect(await screen.findByRole('heading', { name: 'No se pudo cargar la película' })).toBeInTheDocument();
    routeFetch(fetchMock, { ...GENRES_ROUTE, 'GET /api/movies/7': () => jsonResponse(interstellar) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { name: 'Editar «Interstellar»' })).toBeInTheDocument();
  });
});
