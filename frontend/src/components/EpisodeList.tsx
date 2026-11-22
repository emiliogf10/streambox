import { useMemo } from 'react';
import { episodePlayerPath, getDirectVideoUrl, resumePositionOf } from '../lib/playback';
import { episodeCode } from '../lib/series';
import type { EpisodeProgress, Season } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { WatchButton } from './WatchButton';
import { WatchProgressBar } from './WatchProgressBar';

/** Propiedades de {@link EpisodeList}. */
interface EpisodeListProps {
  season: Season;
  /** Serie de los episodios: forma la dirección del reproductor de cada uno. */
  seriesId: number;
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
 * terminado, la barra llena y «Visto». El botón de uno a medias dice
 * «Reanudar» (el reproductor empieza donde se quedó); el de los demás, «Ver».
 * Solo hay progreso de los episodios que se ven en StreamBox (archivo de vídeo
 * directo): los demás se abren en otra pestaña y la aplicación no sabe cuánto
 * se vio de ellos.
 *
 * **Sin miniatura por episodio.** La API no tiene imagen propia de episodio
 * (está previsto añadirla más adelante). Repetir el póster de la serie en cada
 * fila no daría ninguna información nueva: diez carteles iguales seguidos solo
 * añadirían ruido y empujarían el texto. Mientras no haya imagen propia, la
 * lista es tipográfica: el título del episodio manda y la duración lo acompaña.
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
export function EpisodeList({ season, seriesId, progress }: EpisodeListProps) {
  const progressById = useMemo(() => new Map((progress ?? []).map((item) => [item.episodeId, item])), [progress]);

  return (
    <ol role="list" aria-label={`Episodios de la temporada ${season.seasonNumber}`} className="border-t border-line">
      {season.episodes.map((episode) => {
        const code = episodeCode(episode.seasonNumber, episode.episodeNumber);
        // Un progreso de un episodio que ya no se ve aquí (le han cambiado el vídeo) no se enseña: no se puede reanudar.
        const watched = getDirectVideoUrl(episode.videoUrl) ? progressById.get(episode.id) : undefined;
        const label = watched && resumePositionOf(watched) > 0 ? 'Reanudar' : 'Ver';
        return (
          <li
            key={episode.id}
            className="flex flex-col gap-3 border-b border-line py-5 sm:flex-row sm:items-start sm:justify-between sm:gap-6"
          >
            <div className="min-w-0">
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <h3 className="text-base font-semibold text-pretty break-words text-white">
                  {episode.episodeNumber}. {episode.title}
                </h3>
                <span className="text-sm text-muted tabular-nums">{formatDuration(episode.duration)}</span>
              </div>
              {episode.description && (
                <p className="mt-1.5 max-w-[65ch] text-sm leading-relaxed text-pretty text-gray-300">
                  {episode.description}
                </p>
              )}
              {watched && (
                <WatchProgressBar
                  positionSeconds={watched.positionSeconds}
                  durationSeconds={watched.durationSeconds}
                  finished={watched.finished}
                  className="mt-3 w-48 max-w-full"
                />
              )}
            </div>
            <WatchButton
              videoUrl={episode.videoUrl}
              title={episode.title}
              label={label}
              accessibleName={`${label} ${code} ${episode.title}`}
              variant="outline"
              className="shrink-0 self-start"
              playerPath={episodePlayerPath(seriesId, episode.id)}
            />
          </li>
        );
      })}
    </ol>
  );
}
