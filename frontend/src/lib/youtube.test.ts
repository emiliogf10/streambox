/**
 * Tests de `youtube.ts`.
 *
 * Es una barrera de seguridad: lo que valida acaba incrustado en la página como
 * `<iframe>`. Se prueban las formas que acepta el contrato con el backend, las
 * que debe rechazar (otros esquemas y hosts, hosts parecidos, credenciales,
 * puertos, ids de longitud incorrecta, `javascript:`) y que la dirección del
 * reproductor se construye SOLO desde la forma normalizada y con un id revalidado.
 */
import { describe, expect, it } from 'vitest';
import {
  TRAILER_URL_FORMAT_MESSAGE,
  TRAILER_URL_SIZE_MESSAGE,
  getTrailer,
  getYouTubeEmbedUrl,
  normalizeYouTubeUrl,
  validateTrailerUrl,
} from './youtube';

const ID = 'zSWdZVtXT7E';
const NORMALIZED = `https://www.youtube.com/watch?v=${ID}`;

describe('normalizeYouTubeUrl: formas aceptadas', () => {
  it.each([
    [`https://www.youtube.com/watch?v=${ID}`],
    [`https://youtube.com/watch?v=${ID}`],
    [`https://m.youtube.com/watch?v=${ID}`],
    // Otros parámetros, antes o después, se ignoran.
    [`https://www.youtube.com/watch?v=${ID}&t=30s`],
    [`https://www.youtube.com/watch?feature=share&v=${ID}&list=PL123`],
    [`https://youtu.be/${ID}`],
    [`https://youtu.be/${ID}?si=AbCdEf123`],
    [`https://www.youtube.com/embed/${ID}`],
    [`https://www.youtube.com/embed/${ID}?start=10`],
    [`https://www.youtube-nocookie.com/embed/${ID}`],
  ])('%s → forma normalizada', (url) => {
    expect(normalizeYouTubeUrl(url)).toBe(NORMALIZED);
  });

  it('ids con guion y guion bajo también valen', () => {
    expect(normalizeYouTubeUrl('https://youtu.be/a-b_c-d_e-f')).toBe('https://www.youtube.com/watch?v=a-b_c-d_e-f');
  });
});

describe('normalizeYouTubeUrl: formas rechazadas', () => {
  it.each([
    ['http sin cifrar', `http://www.youtube.com/watch?v=${ID}`],
    ['HTTPS en mayúsculas', `HTTPS://www.youtube.com/watch?v=${ID}`],
    ['host en mayúsculas', `https://www.YouTube.com/watch?v=${ID}`],
    ['relativa al protocolo', `//www.youtube.com/watch?v=${ID}`],
    ['sin esquema', `www.youtube.com/watch?v=${ID}`],
    ['javascript:', 'javascript:alert(1)'],
    ['javascript: disfrazado con el id', `javascript:alert('${ID}')`],
    ['data:', 'data:text/html,<script>alert(1)</script>'],
    ['otro host', `https://vimeo.com/${ID}`],
    ['host que contiene youtube.com', `https://www.youtube.com.evil.com/watch?v=${ID}`],
    ['host que termina en youtube.com', `https://evilyoutube.com/watch?v=${ID}`],
    ['subdominio no admitido', `https://music.youtube.com/watch?v=${ID}`],
    ['youtu.be con www', `https://www.youtu.be/${ID}`],
    ['nocookie sin www', `https://youtube-nocookie.com/embed/${ID}`],
    ['nocookie con watch', `https://www.youtube-nocookie.com/watch?v=${ID}`],
    ['credenciales', `https://usuario:clave@www.youtube.com/watch?v=${ID}`],
    ['usuario delante (suplantación)', `https://www.youtube.com@evil.com/watch?v=${ID}`],
    ['puerto', `https://www.youtube.com:443/watch?v=${ID}`],
    ['puerto en youtu.be', `https://youtu.be:8443/${ID}`],
    ['id de 10 caracteres', 'https://youtu.be/zSWdZVtXT7'],
    ['id de 12 caracteres', 'https://youtu.be/zSWdZVtXT7EE'],
    ['id de 10 caracteres en watch', 'https://www.youtube.com/watch?v=zSWdZVtXT7'],
    ['id de 12 caracteres en watch', 'https://www.youtube.com/watch?v=zSWdZVtXT7EE'],
    ['id con caracteres no válidos', 'https://youtu.be/zSWdZVt%T7E'],
    ['watch sin v', 'https://www.youtube.com/watch?t=30s'],
    ['watch con dos v', `https://www.youtube.com/watch?v=${ID}&v=aaaaaaaaaaa`],
    ['parámetro que solo termina en v', `https://www.youtube.com/watch?vv=${ID}`],
    ['fragmento', `https://youtu.be/${ID}#t=30`],
    ['ruta de más', `https://youtu.be/${ID}/extra`],
    ['barra final', `https://www.youtube.com/embed/${ID}/`],
    ['shorts', `https://www.youtube.com/shorts/${ID}`],
    ['canal', 'https://www.youtube.com/@canal'],
    ['espacio interior', `https://youtu.be/ ${ID}`],
    ['carácter invisible', `https://youtu.be/${ID}​`],
    ['vacío', ''],
  ])('rechaza: %s', (_caso, url) => {
    expect(normalizeYouTubeUrl(url)).toBeNull();
  });

  it('más de 500 caracteres: rechazado aunque la forma encaje', () => {
    expect(normalizeYouTubeUrl(`https://youtu.be/${ID}?si=${'a'.repeat(500)}`)).toBeNull();
  });

  // Mismo criterio que java.net.URI en el backend: si el cliente aceptara estos
  // caracteres en los parámetros, el formulario daría por bueno un enlace al que
  // el servidor responde 400. Un `%` solo vale seguido de dos hexadecimales.
  it.each(['<', '>', '"', '{', '}', '|', '^', '`', '\\', '%', '%4', '%zz', '%G0', ' ','\t', '​', 'ñ'])(
    'rechaza %j en los parámetros (en todas las formas)',
    (char) => {
      expect(normalizeYouTubeUrl(`https://youtu.be/${ID}?a=${char}`)).toBeNull();
      expect(normalizeYouTubeUrl(`https://www.youtube.com/embed/${ID}?a=${char}`)).toBeNull();
      expect(normalizeYouTubeUrl(`https://www.youtube.com/watch?v=${ID}&a=${char}`)).toBeNull();
    },
  );

  // Corchetes: java.net.URI los admite en la query, así que el cliente también
  // (antes los rechazaba y el formulario bloqueaba un enlace que la API acepta).
  it('acepta corchetes en los parámetros (en todas las formas)', () => {
    expect(normalizeYouTubeUrl(`https://youtu.be/${ID}?a[]=1`)).toBe(NORMALIZED);
    expect(normalizeYouTubeUrl(`https://www.youtube.com/embed/${ID}?x=[]`)).toBe(NORMALIZED);
    expect(normalizeYouTubeUrl(`https://www.youtube.com/watch?[=1&v=${ID}`)).toBe(NORMALIZED);
  });

  it('acepta parámetros normales (t, si, list, %XX)', () => {
    expect(normalizeYouTubeUrl(`https://youtu.be/${ID}?si=Ab-_9&t=10`)).toBe(NORMALIZED);
    expect(normalizeYouTubeUrl(`https://www.youtube.com/watch?v=${ID}&list=PL1%20x&t=1m`)).toBe(NORMALIZED);
  });
});

describe('validateTrailerUrl', () => {
  it('vacío es válido: el tráiler es opcional', () => {
    expect(validateTrailerUrl('')).toBeUndefined();
  });

  it('acepta las formas del contrato', () => {
    expect(validateTrailerUrl(`https://youtu.be/${ID}`)).toBeUndefined();
    expect(validateTrailerUrl(NORMALIZED)).toBeUndefined();
  });

  it('rechaza lo demás con el mensaje del contrato, literalmente', () => {
    expect(validateTrailerUrl('https://vimeo.com/1')).toBe(TRAILER_URL_FORMAT_MESSAGE);
    expect(validateTrailerUrl('javascript:alert(1)')).toBe(TRAILER_URL_FORMAT_MESSAGE);
    expect(TRAILER_URL_FORMAT_MESSAGE).toBe('El tráiler debe ser un enlace de un vídeo de YouTube');
  });

  it('más de 500 caracteres: SOLO el mensaje de longitud (como el servidor)', () => {
    const ok500 = `https://youtu.be/${ID}?si=${'a'.repeat(500 - `https://youtu.be/${ID}?si=`.length)}`;
    expect(ok500).toHaveLength(500);
    expect(validateTrailerUrl(ok500)).toBeUndefined();
    expect(validateTrailerUrl(`${ok500}a`)).toBe(TRAILER_URL_SIZE_MESSAGE);
    expect(validateTrailerUrl(`http://${'a'.repeat(600)}`)).toBe(TRAILER_URL_SIZE_MESSAGE);
    expect(TRAILER_URL_SIZE_MESSAGE).toBe('La URL del tráiler no puede superar los 500 caracteres');
  });
});

describe('getTrailer / getYouTubeEmbedUrl', () => {
  it('de la forma normalizada construye el reproductor sin cookies y el enlace a YouTube', () => {
    expect(getTrailer(NORMALIZED)).toEqual({
      embedUrl: `https://www.youtube-nocookie.com/embed/${ID}?autoplay=1&rel=0`,
      watchUrl: NORMALIZED,
    });
    expect(getYouTubeEmbedUrl(NORMALIZED)).toBe(`https://www.youtube-nocookie.com/embed/${ID}?autoplay=1&rel=0`);
  });

  it.each([
    [null],
    [undefined],
    [''],
    // Formas válidas al escribirlas en el panel, pero la API solo devuelve la normalizada: el cliente no las «arregla».
    [`https://youtu.be/${ID}`],
    [`https://www.youtube.com/embed/${ID}`],
    [`https://www.youtube.com/watch?v=${ID}&t=30s`],
    // Nada de lo que venga detrás del id llega al iframe.
    [`https://www.youtube.com/watch?v=${ID}"onload="alert(1)`],
    [`https://www.youtube.com/watch?v=${ID}#x`],
    [`https://www.youtube.com/watch?v=${ID} `],
    ['https://www.youtube.com/watch?v=zSWdZVtXT7'],
    ['https://www.youtube.com/watch?v=zSWdZVtXT7EE'],
    ['javascript:alert(1)'],
    [`http://www.youtube.com/watch?v=${ID}`],
  ])('%j → null (sin botón «Ver tráiler»)', (value) => {
    expect(getTrailer(value)).toBeNull();
    expect(getYouTubeEmbedUrl(value)).toBeNull();
  });
});
