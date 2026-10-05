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
  VIDEO_URL_FORMAT_MESSAGE,
  VIDEO_URL_SIZE_MESSAGE,
  getPreviewImageUrl,
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

  it('vacía y demasiado larga tienen su propio mensaje', () => {
    expect(validateVideoUrl('')).toBe('La URL del vídeo es obligatoria');
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

  it('vacío: informa de TODOS los campos a la vez', () => {
    expect(Object.keys(validateMovieForm(EMPTY_MOVIE_FORM)).sort()).toEqual(
      ['description', 'duration', 'genreIds', 'imageUrl', 'releaseYear', 'title', 'videoUrl'].sort(),
    );
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
      genreIds: [3, 1],
    });
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
