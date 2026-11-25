/**
 * Vídeo y subtítulos de mentira para los E2E del reproductor: sirven
 * `fixtures/clip.webm` y `fixtures/subtitulos.vtt` en direcciones inventadas de
 * OTRO origen (`https://media.streambox.example/...`), que nunca salen a
 * Internet, y cambian en la respuesta real de la API la URL del vídeo y de los
 * subtítulos de un título sin tocar el catálogo compartido (la misma técnica que
 * `player.spec.ts`; ver allí el porqué).
 *
 * **CORS a elección.** Un `<track>` de otro origen solo se carga si el `<video>`
 * lleva `crossorigin`, y entonces el navegador pide TAMBIÉN el vídeo con CORS.
 * Por eso cada servidor de mentira puede autorizar o no a la aplicación
 * (`Access-Control-Allow-Origin`): así se prueba de verdad (con el Chromium de
 * Playwright, no con un simulacro) qué pasa cuando el servidor del vídeo o el de
 * los subtítulos no lo admite.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Page } from '@playwright/test';

/** El vídeo de prueba (WebM, 60 s, sin sonido; ver `fixtures/generar-video.mjs`). */
const CLIP = readFileSync(fileURLToPath(new URL('../fixtures/clip.webm', import.meta.url)));

/** Subtítulos de prueba (WebVTT, dos líneas: de 0 a 30 s y de 30 a 60 s). */
const SUBTITLES = readFileSync(fileURLToPath(new URL('../fixtures/subtitulos.vtt', import.meta.url)));

/** Dirección inventada del vídeo (otro origen que la aplicación). */
export const CLIP_URL = 'https://media.streambox.example/e2e/clip.webm';

/** Dirección inventada de los subtítulos: con consulta y en mayúsculas, como las que admite el panel. */
export const SUBTITLES_URL = 'https://media.streambox.example/e2e/SUBTITULOS.ES.VTT?firma=e2e';

/** Opciones de los servidores de mentira. */
export interface MediaServerOptions {
  /** `true` (por defecto) = autoriza a cualquier origen; `false` = solo a otro (ver {@link corsHeaders}). */
  cors?: boolean;
}

/**
 * Cabecera CORS. Con CORS, `*` basta: `crossorigin="anonymous"` no manda credenciales.
 *
 * Sin CORS **no se puede omitir la cabecera**: cuando una respuesta interceptada
 * (`route.fulfill`) no la lleva, Playwright añade él mismo
 * `Access-Control-Allow-Origin` con el origen de la página para que la
 * intercepción no rompa CORS, y el navegador la aceptaría (comprobado: el vídeo
 * cargaba con `crossorigin`). Por eso el servidor «sin CORS» responde que solo
 * admite OTRO origen, que para el navegador es lo mismo: no autoriza a StreamBox.
 */
function corsHeaders(cors: boolean): Record<string, string> {
  return { 'Access-Control-Allow-Origin': cors ? '*' : 'https://otra-web.example' };
}

/**
 * Sirve el clip en {@link CLIP_URL} como un servidor de vídeos de verdad, con
 * peticiones parciales (`Range` → 206): sin ellas Chromium no deja saltar a
 * otro segundo (ni al reanudar).
 */
export async function serveClip(page: Page, { cors = true }: MediaServerOptions = {}): Promise<void> {
  await page.route(CLIP_URL, (route) => {
    const range = /^bytes=(\d+)-(\d*)$/.exec(route.request().headers()['range'] ?? '');
    const headers = { 'Accept-Ranges': 'bytes', 'Content-Type': 'video/webm', ...corsHeaders(cors) };
    if (!range) return route.fulfill({ status: 200, headers, body: CLIP });
    const start = Number(range[1]);
    const end = range[2] ? Math.min(Number(range[2]), CLIP.length - 1) : CLIP.length - 1;
    return route.fulfill({
      status: 206,
      headers: { ...headers, 'Content-Range': `bytes ${start}-${end}/${CLIP.length}` },
      body: CLIP.subarray(start, end + 1),
    });
  });
}

/** Opciones de {@link serveSubtitles}. */
export interface SubtitlesServerOptions extends MediaServerOptions {
  /** Código de la respuesta: 200 (por defecto) con el `.vtt`, o un error (404...) sin cuerpo útil. */
  status?: number;
}

/**
 * Sirve los subtítulos en {@link SUBTITLES_URL}. Devuelve la lista de
 * peticiones que han llegado, para comprobar si el navegador los ha pedido.
 */
export async function serveSubtitles(
  page: Page,
  { cors = true, status = 200 }: SubtitlesServerOptions = {},
): Promise<string[]> {
  const requested: string[] = [];
  // Con una función y no con el texto: un patrón de texto es un *glob* y la `?` de la consulta tendría otro sentido.
  await page.route((url) => url.href === SUBTITLES_URL, (route) => {
    requested.push(route.request().url());
    const headers = { 'Content-Type': 'text/vtt; charset=utf-8', ...corsHeaders(cors) };
    return status === 200
      ? route.fulfill({ status, headers, body: SUBTITLES })
      : route.fulfill({ status, headers: corsHeaders(cors), body: 'No encontrado' });
  });
  return requested;
}

/**
 * Cambia en la respuesta real de `GET <path>` el vídeo (y, si se pasan, los
 * subtítulos) de la película o de cada episodio de la serie.
 *
 * @param subtitlesUrl subtítulos que se ponen; `undefined` deja los que diga el servidor
 */
export async function withMedia(page: Page, path: string, subtitlesUrl?: string | null): Promise<void> {
  await page.route(`**${path}`, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    const response = await route.fetch();
    type Playable = { videoUrl?: string | null; subtitlesUrl?: string | null };
    const body = (await response.json()) as Playable & { seasons?: { episodes: Playable[] }[] };
    const patch = (item: Playable) => {
      item.videoUrl = CLIP_URL;
      if (subtitlesUrl !== undefined) item.subtitlesUrl = subtitlesUrl;
    };
    if ('videoUrl' in body) patch(body);
    for (const season of body.seasons ?? []) season.episodes.forEach(patch);
    await route.fulfill({ response, json: body });
  });
}
