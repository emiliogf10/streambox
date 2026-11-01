import type { ReactNode } from 'react';
import type { Series } from '../lib/types';
import { PosterRow } from './PosterRow';
import { SeriesCard } from './SeriesCard';

/** Propiedades de {@link SeriesRow}. */
interface Props {
  title: string;
  series: Series[];
  /** Enlace opcional junto al título (ver `PosterRow`), p. ej. «Ver todas». */
  action?: ReactNode;
  /** Frase opcional bajo el título que explica la fila (ver `PosterRow`). */
  description?: string;
}

/**
 * Fila de series: una {@link PosterRow} con una {@link SeriesCard} por serie.
 * Cada tarjeta es un enlace a la página de la serie (no abre un diálogo).
 */
export function SeriesRow({ title, series, action, description }: Props) {
  return (
    <PosterRow
      title={title}
      items={series}
      action={action}
      description={description}
      renderItem={(item) => <SeriesCard series={item} />}
    />
  );
}
