import { formatEpisodeCount, formatSeasonCount, formatSeriesYears, isOnAir } from '../lib/series';
import type { Series } from '../lib/types';
import { MetaTags } from './MetaTags';

/**
 * Datos de una serie: años de emisión, «En emisión» si sigue abierta, número de
 * temporadas y de episodios, y géneros. Mismo aspecto que los de una película
 * ({@link MetaTags}).
 *
 * «En emisión» va escrito y no se deja solo a la raya final de `2021–`: la raya
 * sola se entiende a medias a la vista y un lector de pantalla la lee como "2021 guion".
 * `badge` añade la etiqueta de acento del banner («Novedad») como primer dato.
 */
export function SeriesMetaTags({ series, badge }: { series: Series; badge?: string }) {
  const facts = [
    formatSeriesYears(series.releaseYear, series.endYear),
    ...(isOnAir(series) ? ['En emisión'] : []),
    formatSeasonCount(series.seasonCount),
    formatEpisodeCount(series.episodeCount),
  ];
  return <MetaTags facts={facts} genres={series.genres} label="Datos de la serie" badge={badge} />;
}
