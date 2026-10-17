/**
 * Tests de `movieValidation.ts`.
 *
 * Las reglas de URL son una barrera de seguridad (qué acaba en un `src` o un
 * `href` de la aplicación) y un contrato con el backend (`HttpsUrlValidator`):
 * si el cliente fuera más estricto, bloquearía datos válidos; si fuera más
 * laxo, el usuario no se enteraría hasta enviar. Se prueban los casos que pide
 * el contrato (`http://`, `ftp:`, `javascript:`, credenciales, portadas propias,
 * `..`, más de 500 caracteres) y los límites de cada campo.
 */
import { describe, expect, it } from 'vitest';
import {
  EMPTY_MOVIE_FORM,
  GENRE_NAME_SIZE_MESSAGE,
  IMAGE_URL_FORMAT_MESSAGE,
  IMAGE_URL_SIZE_MESSAGE,
  MOVIE_DESCRIPTION_CONTROL_CHARACTERS_MESSAGE,
  TITLE_CONTROL_CHARACTERS_MESSAGE,
  VIDEO_URL_FORMAT_MESSAGE,
  VIDEO_URL_SIZE_MESSAGE,
  getPreviewImageUrl,
  hasControlCharacters,
  hasDisallowedMultilineControlCharacters,
  toMovieRequest,
  validateGenreName,
  validateImageUrl,
  validateMovieForm,
  validateVideoUrl,
} from './movieValidation';
import type { MovieFormValues } from './movieValidation';

/** Formulario válido de partida; cada test cambia solo lo que le importa. */
const valid: MovieFormValues = {
  title: 'Interstellar',
  description: 'Un grupo de exploradores viaja a través de un agujero de gusano.',
  duration: '169',
  releaseYear: '2014',
  imageUrl: '/covers/interstellar.webp',
  videoUrl: 'https://videos.example.com/interstellar',
  trailerUrl: 'https://youtu.be/zSWdZVtXT7E',
  genreIds: [1],
};

describe('validateImageUrl', () => {
  it.each([
    'https://image.tmdb.org/t/p/w500/portada.jpg',
    'https://example.com',
    'https://example.com:8443/a/b.webp?w=500#x',
    'https://[::1]/portada.webp',
    '/covers/ok.webp',
    '/covers/cover_matrix_1790887046226.jpg',
    '/covers/A-b.c_d',
  ])('acepta %j', (url) => {
    expect(validateImageUrl(url)).toBeUndefined();
  });

  it.each([
    ['http (sin cifrar)', 'http://example.com/a.jpg'],
    ['ftp', 'ftp://example.com/a.jpg'],
    ['javascript', 'javascript:alert(1)'],
    ['data', 'data:image/png;base64,AAAA'],
    ['HTTPS en mayúsculas (el backend exige la forma canónica)', 'HTTPS://example.com/a.jpg'],
    ['relativa al protocolo', '//example.com/a.jpg'],
    ['sin host', 'https://'],
    ['sin host y con ruta', 'https:///ruta/a.jpg'],
    ['con credenciales', 'https://usuario:clave@example.com/a.jpg'],
    ['con usuario', 'https://usuario@example.com/a.jpg'],
    ['suplantación con @', 'https://banco.com@malo.example/a.jpg'],
    ['con espacio interior', 'https://example.com/mi portada.jpg'],
    ['con carácter no ASCII invisible', 'https://example.com/a​.jpg'],
    ['portada fuera de la carpeta', '/covers/../x'],
    ['portada que es ".."', '/covers/..'],
    ['portada oculta (empieza por punto)', '/covers/.secreto.webp'],
    ['portada en subcarpeta', '/covers/sub/x.webp'],
    ['portada con parámetros', '/covers/x.webp?v=2'],
    ['portada con fragmento', '/covers/x.webp#a'],
    ['portada codificada', '/covers/%2e%2e'],
    ['portada sin archivo', '/covers/'],
    ['otra carpeta local', '/images/x.webp'],
    ['ruta relativa', 'covers/x.webp'],
  ])('rechaza %s', (_caso, url) => {
    expect(validateImageUrl(url)).toBe(IMAGE_URL_FORMAT_MESSAGE);
  });

  it('vacía: pide la URL (un mensaje propio, no el de formato)', () => {
    expect(validateImageUrl('')).toBe('La URL de la portada es obligatoria');
  });

  it('más de 500 caracteres: SOLO el mensaje de longitud (aunque además tenga mal formato)', () => {
    const ok500 = `https://example.com/${'a'.repeat(500 - 'https://example.com/'.length)}`;
    expect(ok500).toHaveLength(500);
    expect(validateImageUrl(ok500)).toBeUndefined();
    expect(validateImageUrl(`${ok500}a`)).toBe(IMAGE_URL_SIZE_MESSAGE);
    expect(validateImageUrl(`http://${'a'.repeat(600)}`)).toBe(IMAGE_URL_SIZE_MESSAGE);
  });

  it('los mensajes son los del contrato con el backend, literalmente', () => {
    expect(IMAGE_URL_FORMAT_MESSAGE).toBe(
      'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)',
    );
    expect(IMAGE_URL_SIZE_MESSAGE).toBe('La URL de la imagen no puede superar los 500 caracteres');
  });
});

describe('validateVideoUrl', () => {
  it('acepta una URL https absoluta', () => {
    expect(validateVideoUrl('https://videos.example.com/watch?v=1')).toBeUndefined();
  });

  it.each([
    ['http', 'http://videos.example.com/x'],
    ['ftp', 'ftp://videos.example.com/x'],
    ['javascript', 'javascript:alert(1)'],
    ['credenciales', 'https://a:b@videos.example.com/x'],
    // Las portadas propias valen para la imagen, NO para el vídeo.
    ['portada propia', '/covers/ok.webp'],
  ])('rechaza %s', (_caso, url) => {
    expect(validateVideoUrl(url)).toBe(VIDEO_URL_FORMAT_MESSAGE);
  });

  it('vacía es válida (el vídeo es opcional: sin él no hay «Ver ahora»)', () => {
    expect(validateVideoUrl('')).toBeUndefined();
  });

  it('demasiado larga tiene su propio mensaje', () => {
    expect(validateVideoUrl(`https://example.com/${'v'.repeat(500)}`)).toBe(VIDEO_URL_SIZE_MESSAGE);
    expect(VIDEO_URL_FORMAT_MESSAGE).toBe('La URL del vídeo debe empezar por https://');
    expect(VIDEO_URL_SIZE_MESSAGE).toBe('La URL del vídeo no puede superar los 500 caracteres');
  });
});

describe('getPreviewImageUrl', () => {
  it('devuelve la URL (recortada) solo si es válida: la vista previa nunca carga una URL rechazable', () => {
    expect(getPreviewImageUrl('  /covers/ok.webp ')).toBe('/covers/ok.webp');
    expect(getPreviewImageUrl('https://img.example/a.jpg')).toBe('https://img.example/a.jpg');
    expect(getPreviewImageUrl('http://img.example/a.jpg')).toBeNull();
    expect(getPreviewImageUrl('javascript:alert(1)')).toBeNull();
    expect(getPreviewImageUrl('')).toBeNull();
  });
});

describe('validateMovieForm', () => {
  it('un formulario correcto no produce errores', () => {
    expect(validateMovieForm(valid)).toEqual({});
  });

  it('vacío: informa de TODOS los campos obligatorios a la vez (vídeo y tráiler son opcionales)', () => {
    expect(Object.keys(validateMovieForm(EMPTY_MOVIE_FORM)).sort()).toEqual(
      ['description', 'duration', 'genreIds', 'imageUrl', 'releaseYear', 'title'].sort(),
    );
  });

  it('sin vídeo ni tráiler (vacíos o solo espacios) la película es válida', () => {
    expect(validateMovieForm({ ...valid, videoUrl: '', trailerUrl: '' })).toEqual({});
    expect(validateMovieForm({ ...valid, videoUrl: '   ', trailerUrl: '  ' })).toEqual({});
  });

  it('el tráiler solo admite YouTube, con el mensaje del contrato', () => {
    expect(validateMovieForm({ ...valid, trailerUrl: 'https://vimeo.com/123' }).trailerUrl).toBe(
      'El tráiler debe ser un enlace de un vídeo de YouTube',
    );
    expect(validateMovieForm({ ...valid, trailerUrl: ' https://www.youtube.com/watch?v=zSWdZVtXT7E ' })).toEqual({});
  });

  it('título y sinopsis: obligatorios (no valen solo espacios) y con límite de 150 / 1000', () => {
    expect(validateMovieForm({ ...valid, title: '   ' }).title).toBe('El título es obligatorio');
    expect(validateMovieForm({ ...valid, title: 't'.repeat(150) }).title).toBeUndefined();
    expect(validateMovieForm({ ...valid, title: 't'.repeat(151) }).title).toBe(
      'El título no puede superar los 150 caracteres',
    );
    expect(validateMovieForm({ ...valid, description: '' }).description).toBe('La sinopsis es obligatoria');
    expect(validateMovieForm({ ...valid, description: 'd'.repeat(1000) }).description).toBeUndefined();
    expect(validateMovieForm({ ...valid, description: 'd'.repeat(1001) }).description).toBe(
      'La sinopsis no puede superar los 1000 caracteres',
    );
  });

  it.each([
    ['', 'Indica la duración en minutos'],
    ['0', 'La duración debe ser un número entero de minutos mayor que 0'],
    ['-5', 'La duración debe ser un número entero de minutos mayor que 0'],
    ['1.5', 'La duración debe ser un número entero de minutos mayor que 0'],
    ['1e3', 'La duración debe ser un número entero de minutos mayor que 0'],
    ['noventa', 'La duración debe ser un número entero de minutos mayor que 0'],
    ['99999999999', 'La duración debe ser un número entero de minutos mayor que 0'],
  ])('duración %j → %j', (duration, message) => {
    expect(validateMovieForm({ ...valid, duration }).duration).toBe(message);
  });

  it('duración: 1 minuto ya es válido', () => {
    expect(validateMovieForm({ ...valid, duration: '1' }).duration).toBeUndefined();
  });

  it.each([
    ['1887', true],
    ['1888', false],
    ['2100', false],
    ['2101', true],
    ['20x4', true],
  ])('año %j, ¿error? %s', (releaseYear, hasError) => {
    const error = validateMovieForm({ ...valid, releaseYear }).releaseYear;
    expect(error !== undefined).toBe(hasError);
    if (hasError) expect(error).toBe('El año debe estar entre 1888 y 2100');
  });

  it('los géneros son obligatorios (al menos uno)', () => {
    expect(validateMovieForm({ ...valid, genreIds: [] }).genreIds).toBe('Elige al menos un género');
  });

  it('las URL se validan recortadas: un espacio pegado sin querer no es un error', () => {
    expect(validateMovieForm({ ...valid, imageUrl: ' /covers/ok.webp ', videoUrl: ' https://v.example/x ' })).toEqual({});
  });
});

/**
 * Caracteres de control: el contrato de `ControlCharacters` en el backend.
 * Títulos sin ninguno (`[^\p{Cc}]*`); sinopsis, solo tabulador, `\n` y `\r`.
 * Los casos se escriben con escapes (`\u0000`) porque esos caracteres no se
 * ven: un literal invisible en el test sería imposible de revisar.
 */
describe('caracteres de control', () => {
  it('los mensajes son los del contrato con el backend, literalmente', () => {
    expect(TITLE_CONTROL_CHARACTERS_MESSAGE).toBe('El título no puede contener caracteres de control');
    expect(MOVIE_DESCRIPTION_CONTROL_CHARACTERS_MESSAGE).toBe('La sinopsis contiene caracteres de control no permitidos');
  });

  it.each([
    ['NUL (U+0000)', 'Inter\u0000stellar'],
    ['campana (U+0007)', 'Inter\u0007stellar'],
    ['tabulador', 'Inter\tstellar'],
    ['salto de línea', 'Inter\nstellar'],
    ['retorno de carro', 'Inter\rstellar'],
    ['ESC (U+001B)', 'Inter\u001bstellar'],
    ['DEL (U+007F)', 'Inter\u007fstellar'],
    ['NEL (U+0085, control C1)', 'Inter\u0085stellar'],
    ['U+009F (último control C1)', 'Inter\u009fstellar'],
  ])('título con %s → error de caracteres de control', (_name, title) => {
    expect(validateMovieForm({ ...valid, title }).title).toBe(TITLE_CONTROL_CHARACTERS_MESSAGE);
  });

  it('título: un NUL al final también es error (trim no lo quita y llegaría al servidor)', () => {
    expect(validateMovieForm({ ...valid, title: 'Interstellar\u0000' }).title).toBe(TITLE_CONTROL_CHARACTERS_MESSAGE);
  });

  it('título: un tabulador o salto de línea en un extremo no es error (se recorta al enviar y el servidor no lo ve)', () => {
    const values = { ...valid, title: '\tInterstellar\n' };
    expect(validateMovieForm(values).title).toBeUndefined();
    expect(toMovieRequest(values).title).toBe('Interstellar');
  });

  it('título: los caracteres de formato invisibles (U+200B, categoría Cf) no son de control y se aceptan, como en el servidor', () => {
    expect(validateMovieForm({ ...valid, title: 'Inter​stellar' }).title).toBeUndefined();
    expect(validateMovieForm({ ...valid, title: 'Amélie · 東京 🎬' }).title).toBeUndefined();
    // U+00A0 (espacio duro) va justo detrás de los controles C1: no es de control.
    expect(validateMovieForm({ ...valid, title: 'Inter stellar' }).title).toBeUndefined();
  });

  it('título: la longitud se comprueba antes (UN mensaje por campo)', () => {
    expect(validateMovieForm({ ...valid, title: `${'t'.repeat(151)}\u0000` }).title).toBe(
      'El título no puede superar los 150 caracteres',
    );
  });

  it.each([
    ['tabulador', 'Primera parte:\tel viaje.'],
    ['salto de línea', 'Primer párrafo.\nSegundo párrafo.'],
    ['retorno de carro y salto (texto pegado de Windows)', 'Primer párrafo.\r\nSegundo párrafo.'],
    ['espacio de ancho cero (U+200B)', 'Un viaje​ por el espacio.'],
  ])('sinopsis con %s → válida (se escribe en un <textarea>)', (_name, description) => {
    expect(validateMovieForm({ ...valid, description }).description).toBeUndefined();
  });

  it.each([
    ['NUL (U+0000)', 'Un viaje\u0000 por el espacio.'],
    ['campana (U+0007)', 'Un viaje\u0007 por el espacio.'],
    ['tabulador vertical (U+000B)', 'Un viaje\u000b por el espacio.'],
    ['ESC (U+001B)', 'Un viaje\u001b por el espacio.'],
    ['NEL (U+0085)', 'Un viaje\u0085 por el espacio.'],
  ])('sinopsis con %s → error de caracteres de control', (_name, description) => {
    expect(validateMovieForm({ ...valid, description }).description).toBe(MOVIE_DESCRIPTION_CONTROL_CHARACTERS_MESSAGE);
  });

  it('las funciones de detección siguen la regla de cada tipo de texto', () => {
    expect(hasControlCharacters('Interstellar')).toBe(false);
    expect(hasControlCharacters('a\tb')).toBe(true);
    expect(hasDisallowedMultilineControlCharacters('a\tb\nc\r\nd')).toBe(false);
    expect(hasDisallowedMultilineControlCharacters('a\u0000b')).toBe(true);
    expect(hasDisallowedMultilineControlCharacters('a\u007fb')).toBe(true);
  });
});

describe('toMovieRequest', () => {
  it('recorta los textos y convierte duración y año a número', () => {
    expect(
      toMovieRequest({
        ...valid,
        title: '  Interstellar ',
        duration: ' 169 ',
        releaseYear: '2014',
        imageUrl: ' /covers/interstellar.webp',
        genreIds: [3, 1],
      }),
    ).toEqual({
      title: 'Interstellar',
      description: valid.description,
      duration: 169,
      releaseYear: 2014,
      imageUrl: '/covers/interstellar.webp',
      videoUrl: valid.videoUrl,
      trailerUrl: valid.trailerUrl,
      genreIds: [3, 1],
    });
  });

  it('vídeo y tráiler vacíos (o solo espacios) se envían como null: «no tiene»', () => {
    const body = toMovieRequest({ ...valid, videoUrl: '  ', trailerUrl: '' });
    expect(body.videoUrl).toBeNull();
    expect(body.trailerUrl).toBeNull();
  });

  it('el tráiler se envía recortado, tal como se escribió (lo normaliza el servidor)', () => {
    expect(toMovieRequest({ ...valid, trailerUrl: ' https://youtu.be/zSWdZVtXT7E?si=x ' }).trailerUrl).toBe(
      'https://youtu.be/zSWdZVtXT7E?si=x',
    );
  });
});

describe('validateGenreName', () => {
  it.each(['Drama', 'ab', 'g'.repeat(50), 'ciencia FICCIÓN'])('acepta %j', (name) => {
    expect(validateGenreName(name)).toBeUndefined();
  });

  it('vacío o solo espacios: es obligatorio', () => {
    expect(validateGenreName('   ')).toBe('El nombre del género es obligatorio');
  });

  it('mide como el servidor: tras recortar y colapsar espacios', () => {
    expect(validateGenreName('  a  ')).toBe(GENRE_NAME_SIZE_MESSAGE);
    expect(validateGenreName(`a${' '.repeat(10)}b`)).toBeUndefined(); // "a b" = 3
    expect(validateGenreName('g'.repeat(51))).toBe(GENRE_NAME_SIZE_MESSAGE);
    expect(GENRE_NAME_SIZE_MESSAGE).toBe('El nombre del género debe tener entre 2 y 50 caracteres');
  });
});
