import { useId } from 'react';
import type { ReactNode } from 'react';
import { ChevronRight, EyeOff } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useLatestSeries } from '../hooks/useSeriesCatalog';
import { ADMIN_SERIES_PATH } from '../pages/admin/adminPaths';
import { Button } from './Button';
import { RowSkeleton } from './CatalogSkeleton';
import { SeriesRow } from './SeriesRow';

/** Título de la fila (y nombre de su región). */
const ROW_TITLE = 'Series';

/** Propiedades de {@link LatestSeriesRow}. */
interface LatestSeriesRowProps {
  /**
   * Qué pintar si no hay ninguna serie visible, en lugar del comportamiento
   * normal (nada para un usuario, el aviso para un administrador). La portada
   * lo usa cuando tampoco hay películas: entonces es la fila la que sabe si el
   * catálogo está vacío de verdad (ver `HomePage`).
   */
  whenEmpty?: ReactNode;
}

/**
 * Fila «Series» de la portada: las series más recientes, cada una enlazada a su
 * página, con un enlace «Ver todas» a `/series` junto al título.
 *
 * Carga por su cuenta (`useLatestSeries`), en paralelo al catálogo de
 * películas, así que tiene sus propios estados:
 * - **Cargando**: esqueleto de fila, para que al llegar no empuje hacia abajo
 *   las filas de géneros (salto de diseño).
 * - **Vacía**, según el rol:
 *   - a un **usuario** no se le pinta nada. Una fila que dice "no hay series"
 *     en la portada sería ruido; la página `/series` ya explica el estado vacío.
 *   - a un **administrador** se le deja, en el hueco de la fila, un aviso de una
 *     línea: «Todavía no hay series visibles…» + «Gestionar series». Las series
 *     sin episodios no las ve nadie, tampoco él aquí, y sin el aviso la fila
 *     desaparecería sin explicación (le pasó al autor con 15 series sin
 *     episodios). Es una línea, no un estado vacío grande: no le tapa la
 *     portada, y desaparece sola en cuanto alguna serie tenga episodios.
 *     Mientras se carga el usuario se le trata como usuario (falla cerrado).
 * - **Error**: la fila con su título, el motivo y «Reintentar». Es contenido
 *   secundario: su fallo no tapa el resto de la portada, pero tampoco se calla.
 *
 * «Ver todas» lleva el texto completo para lectores de pantalla («Ver todas las
 * series»): fuera de contexto, "Ver todas" no dice de qué.
 */
export function LatestSeriesRow({ whenEmpty }: LatestSeriesRowProps = {}) {
  const { series, status, errorMessage, reload } = useLatestSeries();
  const { isAdmin } = useAuth();
  const headingId = useId();

  if (status === 'loading') return <RowSkeleton label="Cargando series..." />;

  if (status === 'error') {
    return (
      <section aria-labelledby={headingId} className="mb-9 px-4 sm:mb-10 sm:px-6">
        <h2 id={headingId} className="mb-3 text-lg font-bold tracking-tight text-white">
          {ROW_TITLE}
        </h2>
        <div
          role="alert"
          className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border border-red-500/30 bg-red-950/30 px-4 py-3"
        >
          <p className="text-sm text-muted">
            <span className="font-semibold text-danger">No se pudieron cargar las series.</span> {errorMessage}
          </p>
          <Button variant="outline" onClick={reload}>
            Reintentar
          </Button>
        </div>
      </section>
    );
  }

  if (series.length === 0) {
    if (whenEmpty !== undefined) return whenEmpty;
    if (!isAdmin) return null;
    return (
      <section aria-labelledby={headingId} className="mb-9 px-4 sm:mb-10 sm:px-6">
        <h2 id={headingId} className="mb-3 text-lg font-bold tracking-tight text-white">
          {ROW_TITLE}
        </h2>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-xl border border-dashed border-white/15 px-4 py-2">
          <p className="flex min-h-11 items-center gap-2 text-sm text-muted">
            <EyeOff aria-hidden="true" className="size-4 shrink-0" />
            <span>
              Todavía no hay series visibles, así que los usuarios no ven esta fila. Una serie aparece en cuanto tiene
              al menos un episodio.
            </span>
          </p>
          <Link
            to={ADMIN_SERIES_PATH}
            className="focus-ring inline-flex min-h-11 items-center rounded-md text-sm font-semibold text-accent hover:underline"
          >
            Gestionar series
          </Link>
        </div>
      </section>
    );
  }

  return (
    <SeriesRow
      title={ROW_TITLE}
      series={series}
      action={
        <Link
          to="/series"
          className="focus-ring inline-flex min-h-11 items-center gap-0.5 rounded-md px-1 text-sm font-medium text-muted transition-colors hover:text-white"
        >
          Ver todas
          <span className="sr-only"> las series</span>
          <ChevronRight aria-hidden="true" className="size-4" />
        </Link>
      }
    />
  );
}
