/**
 * Qué imagen se usa en cada sitio que necesita una HORIZONTAL (16:9): el
 * `poster` del reproductor y la miniatura de un episodio.
 *
 * El catálogo tiene tres imágenes posibles: la portada vertical (`imageUrl`,
 * obligatoria en películas y series), la imagen de fondo horizontal de un
 * título (`backdropUrl`, opcional) y la imagen propia de un episodio
 * (`Episode.imageUrl`, opcional). Cada función ordena las que existen, de la
 * más concreta a la más general, en un único sitio: si el orden cambia, cambia
 * para todas las pantallas a la vez.
 *
 * Se comprueba «hay texto» (`||`) y no solo `null` (`??`): un servidor anterior
 * a la migración V16 no envía estos campos (llegan `undefined`), y una cadena
 * vacía tampoco es una imagen.
 */
import type { Episode, Movie, Series } from './types';

/**
 * Quita las URLs vacías o ausentes y las repetidas, conservando el orden.
 * Repetidas: una serie cuya imagen de fondo es la misma que su portada no debe
 * probarse dos veces.
 */
function distinctImages(urls: readonly (string | null | undefined)[]): string[] {
  return [...new Set(urls.filter((url): url is string => Boolean(url)))];
}

/**
 * Candidatas para el `poster` del reproductor de una película, en orden de
 * preferencia: la imagen de fondo (horizontal, como el vídeo) y la portada de
 * siempre.
 *
 * Es una LISTA y no una sola URL porque el atributo `poster` no avisa cuando
 * la imagen falla: con solo la imagen de fondo, una URL rota dejaría el vídeo
 * en negro hasta el primer fotograma (incidencia QA-V16-1). El reproductor usa
 * la primera y pasa a la siguiente si no carga (`useFirstLoadableImage`).
 */
export function moviePlayerPosterCandidates(movie: Pick<Movie, 'imageUrl' | 'backdropUrl'>): string[] {
  return distinctImages([movie.backdropUrl, movie.imageUrl]);
}

/**
 * Candidatas para el `poster` del reproductor de un episodio: su imagen
 * propia, la imagen de fondo de la serie y, como último recurso, la portada de
 * la serie. Lista por lo mismo que {@link moviePlayerPosterCandidates}: una
 * imagen del episodio rota no debe tapar el fondo de la serie.
 */
export function episodePlayerPosterCandidates(
  episode: Pick<Episode, 'imageUrl'>,
  series: Pick<Series, 'imageUrl' | 'backdropUrl'>,
): string[] {
  return distinctImages([episode.imageUrl, series.backdropUrl, series.imageUrl]);
}

/**
 * Miniatura (16:9) de un episodio en la lista de su temporada: su imagen
 * propia o, si no la tiene, la imagen de fondo de la serie. **Nunca la
 * portada**: es vertical y en un hueco 16:9 solo se vería una franja ampliada
 * del centro. Sin ninguna de las dos, `null`, y la lista pinta el respaldo de
 * `MoviePoster` (un recuadro de color).
 */
export function episodeThumbnailUrl(
  episode: Pick<Episode, 'imageUrl'>,
  seriesBackdropUrl: string | null | undefined,
): string | null {
  return episode.imageUrl || seriesBackdropUrl || null;
}

/**
 * Indica si la temporada lleva miniaturas: solo si ALGÚN episodio tiene imagen
 * propia. Entonces todos las llevan (los que no, con {@link episodeThumbnailUrl}),
 * para que las filas queden alineadas; si ninguno la tiene, la lista sigue
 * siendo solo texto, como antes de existir la imagen del episodio (diez copias
 * de la misma imagen de la serie no informarían de nada).
 */
export function seasonHasThumbnails(episodes: readonly Pick<Episode, 'imageUrl'>[]): boolean {
  return episodes.some((episode) => Boolean(episode.imageUrl));
}
