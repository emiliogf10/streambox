import { useMemo } from 'react';
import { episodePlayerPath, getDirectVideoUrl, resumePositionOf } from '../lib/playback';
import { episodeCode } from '../lib/series';
import { episodeThumbnailUrl, seasonHasThumbnails } from '../lib/titleImages';
import type { EpisodeProgress, Season } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { MoviePoster } from './MoviePoster';
import { WatchButton } from './WatchButton';
import { WatchProgressBar } from './WatchProgressBar';

/** Propiedades de {@link EpisodeList}. */
interface EpisodeListProps {
  season: Season;
  /** Serie de los episodios: forma la dirección del reproductor de cada uno. */
  seriesId: number;
  /**
   * Imagen de fondo de la serie (`backdropUrl`): la miniatura de los episodios
   * sin imagen propia cuando la temporada lleva miniaturas.
   */
  seriesBackdropUrl?: string | null;
  /**
   * Progreso del usuario en los episodios empezados de la serie
   * (`GET /progress/series/{id}`); `undefined` mientras llega o si falló: la
   * lista se pinta entonces sin barras (son un extra, no deben taparla).
   */
  progress?: readonly EpisodeProgress[];
}

/**
 * Episodios de una temporada: número y título («3. El regreso»), duración,
 * sinopsis si la tiene y el enlace «Ver» **solo si el episodio tiene vídeo**
 * (es opcional; sin él la fila es solo texto: ningún botón promete ver algo
 * que no existe, y `WatchButton` no deja hueco porque no pinta nada).
 *
 * **Progreso.** Un episodio empezado lleva debajo de su sinopsis la barra de lo
 * visto y lo que queda («Quedan 12 min», {@link WatchProgressBar}); uno
 * terminado, la barra llena y «Visto». Uno que ya se vio y se está volviendo a
 * ver (`watched` sin `finished`) no aparece como «a medias» sin más: la barra
 * en la posición actual y «Visto · Quedan 12 min». El botón de uno a medias
 * dice «Reanudar» (el reproductor empieza donde se quedó); el de los demás,
 * «Ver». El botón lo decide la posición (`finished`), nunca `watched`.
 * Solo hay progreso de los episodios que se ven en StreamBox (archivo de vídeo
 * directo): los demás se abren en otra pestaña y la aplicación no sabe cuánto
 * se vio de ellos.
 *
 * **Miniaturas.** Cada episodio puede tener imagen propia (`imageUrl`, 16:9).
 * La regla es por TEMPORADA, para que la lista quede alineada:
 * - **Si ningún episodio de la temporada tiene imagen**, la lista es solo
 *   texto, como antes de existir la imagen del episodio: el título manda y la
 *   duración lo acompaña. No se rellena con la imagen de la serie: diez copias
 *   seguidas de la misma imagen no informarían de nada y empujarían el texto.
 * - **Si alguno la tiene, todos llevan miniatura**: los que no tienen, la imagen
 *   de fondo de la serie o, sin ella, el respaldo de {@link MoviePoster} (un
 *   recuadro de color con un icono; nunca la portada vertical, que en 16:9 sería
 *   una franja ampliada). Así los títulos empiezan todos a la misma altura y no
 *   hay filas con hueco y filas sin él (`seasonHasThumbnails` y
 *   `episodeThumbnailUrl`, en `lib/titleImages.ts`).
 * - La miniatura tiene su tamaño reservado (`aspect-video` en una columna de
 *   ancho fijo: nada salta al cargar), se descarga solo al acercarse a la
 *   pantalla (carga diferida de `MoviePoster`) y es decorativa (`alt` vacío):
 *   el título está escrito al lado. En móvil va a la izquierda del título, con
 *   la sinopsis y el botón debajo a todo el ancho; desde `sm`, miniatura, texto
 *   y botón en una fila.
 *
 * **Accesibilidad.**
 * - Lista ordenada (`<ol>`): el orden importa y el lector de pantalla dice cuántos hay.
 * - Cada título es un `<h3>` (bajo el `<h2>` «Episodios»): se puede saltar de
 *   episodio en episodio con la navegación por encabezados.
 * - «Ver» es el mismo {@link WatchButton} de las películas (URL validada con
 *   `getSafeVideoUrl`; reproductor integrado o pestaña nueva con `noopener`),
 *   con un nombre accesible único: «Ver T1:E3 El regreso» o «Reanudar T1:E3 El
 *   regreso». Diez enlaces llamados solo «Ver» serían indistinguibles en la
 *   lista de enlaces de un lector de pantalla.
 * - Va en variante `outline` (secundaria): en una lista larga, diez botones
 *   blancos competirían entre sí y con la acción principal de la cabecera.
 */
export function EpisodeList({ season, seriesId, seriesBackdropUrl, progress }: EpisodeListProps) {
  const progressById = useMemo(() => new Map((progress ?? []).map((item) => [item.episodeId, item])), [progress]);
  const withThumbnails = seasonHasThumbnails(season.episodes);

  return (
    <ol role="list" aria-label={`Episodios de la temporada ${season.seasonNumber}`} className="border-t border-line">
      {season.episodes.map((episode) => {
        const code = episodeCode(episode.seasonNumber, episode.episodeNumber);
        // Un progreso de un episodio que ya no se ve aquí (le han cambiado el vídeo) no se enseña: no se puede reanudar.
        const episodeProgress = getDirectVideoUrl(episode.videoUrl) ? progressById.get(episode.id) : undefined;
        const label = episodeProgress && resumePositionOf(episodeProgress) > 0 ? 'Reanudar' : 'Ver';
        const heading = (
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h3 className="text-base font-semibold text-pretty break-words text-white">
              {episode.episodeNumber}. {episode.title}
            </h3>
            <span className="text-sm text-muted tabular-nums">{formatDuration(episode.duration)}</span>
          </div>
        );
        const description = (className: string) =>
          episode.description && (
            <p className={`max-w-[65ch] text-sm leading-relaxed text-pretty text-gray-300 ${className}`}>
              {episode.description}
            </p>
          );
        const progressBar = (className: string) =>
          episodeProgress && (
            <WatchProgressBar
              positionSeconds={episodeProgress.positionSeconds}
              durationSeconds={episodeProgress.durationSeconds}
              finished={episodeProgress.finished}
              watched={episodeProgress.watched}
              className={`w-48 max-w-full ${className}`}
            />
          );
        const watchButton = (className: string) => (
          <WatchButton
            videoUrl={episode.videoUrl}
            title={episode.title}
            label={label}
            accessibleName={`${label} ${code} ${episode.title}`}
            variant="outline"
            className={`shrink-0 self-start ${className}`}
            playerPath={episodePlayerPath(seriesId, episode.id)}
          />
        );

        if (!withThumbnails) {
          return (
            <li
              key={episode.id}
              className="flex flex-col gap-3 border-b border-line py-5 sm:flex-row sm:items-start sm:justify-between sm:gap-6"
            >
              <div className="min-w-0">
                {heading}
                {description('mt-1.5')}
                {progressBar('mt-3')}
              </div>
              {watchButton('')}
            </li>
          );
        }

        // Con miniaturas (ver «Miniaturas»): en móvil, miniatura y título en una fila y el resto debajo, a todo el
        // ancho (`contents` hace de cada bloque una celda de la rejilla); desde `sm`, miniatura | texto | botón.
        return (
          <li
            key={episode.id}
            className="grid grid-cols-[7.5rem_minmax(0,1fr)] items-center gap-x-4 gap-y-3 border-b border-line py-5 sm:grid-cols-[10rem_minmax(0,1fr)_auto] sm:items-start sm:gap-x-6 lg:grid-cols-[12rem_minmax(0,1fr)_auto]"
          >
            <MoviePoster
              title={episode.title}
              src={episodeThumbnailUrl(episode, seriesBackdropUrl)}
              wide
              compact
              className="aspect-video w-full rounded-md ring-1 ring-white/10"
            />
            <div className="contents sm:block sm:min-w-0">
              {heading}
              {description('col-span-2 sm:mt-1.5')}
              {progressBar('col-span-2 sm:mt-3')}
            </div>
            {watchButton('col-span-2 justify-self-start sm:col-span-1')}
          </li>
        );
      })}
    </ol>
  );
}
