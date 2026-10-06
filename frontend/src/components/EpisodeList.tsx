import { episodeCode } from '../lib/series';
import type { Season } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { WatchButton } from './WatchButton';

/**
 * Episodios de una temporada: número y título («3. El regreso»), duración,
 * sinopsis si la tiene y el enlace «Ver».
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
 *   `getSafeVideoUrl`, pestaña nueva, `noopener`), con un nombre accesible
 *   único: «Ver T1:E3 El regreso». Diez enlaces llamados solo «Ver» serían
 *   indistinguibles en la lista de enlaces de un lector de pantalla.
 * - Va en variante `outline` (secundaria): en una lista larga, diez botones
 *   blancos competirían entre sí y con la acción principal de la cabecera.
 */
export function EpisodeList({ season }: { season: Season }) {
  return (
    <ol role="list" aria-label={`Episodios de la temporada ${season.seasonNumber}`} className="border-t border-line">
      {season.episodes.map((episode) => {
        const code = episodeCode(episode.seasonNumber, episode.episodeNumber);
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
            </div>
            <WatchButton
              videoUrl={episode.videoUrl}
              title={episode.title}
              label="Ver"
              accessibleName={`Ver ${code} ${episode.title}`}
              unavailableSubject="este episodio"
              variant="outline"
              className="shrink-0 self-start"
            />
          </li>
        );
      })}
    </ol>
  );
}
