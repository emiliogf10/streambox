import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ListX, Plus } from 'lucide-react';
import { Button } from '../components/Button';
import { buttonClasses } from '../components/buttonStyles';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { ExploreLink } from '../components/ExploreLink';
import { LoadingState } from '../components/LoadingState';
import { MovieCard } from '../components/MovieCard';
import { POSTER_GRID_CLASS } from '../components/posterGridStyles';
import { MovieDetailsModal } from '../components/MovieDetailsModal';
import { SeriesCard } from '../components/SeriesCard';
import { useFavorites } from '../context/FavoritesContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import type { Movie } from '../lib/types';

/**
 * Ilustración del estado vacío: tres huecos de póster (2:3, borde discontinuo)
 * abiertos en abanico, el del centro con un "+" en el color de acento.
 *
 * Por qué y no un icono genérico en un círculo: enseña QUÉ va a aparecer aquí
 * (pósters) y con qué gesto (el "+" es el mismo icono del botón "Mi lista"), así
 * que la pantalla vacía también explica cómo llenarla. Son tres `div` con
 * clases de Tailwind: sin imágenes, sin peticiones y sin animación. Es
 * decorativa (`EmptyState` la oculta a los lectores de pantalla; el texto ya
 * lo dice todo).
 */
function EmptyListArt() {
  const slot = 'aspect-2/3 rounded-lg border-2 border-dashed';
  return (
    <div className="flex items-end justify-center">
      <div className={`${slot} w-14 translate-x-2 -rotate-8 border-white/15 bg-white/2`} />
      <div className={`${slot} relative z-10 flex w-20 items-center justify-center border-accent/60 bg-canvas text-accent`}>
        <Plus className="size-7" strokeWidth={2.25} />
      </div>
      <div className={`${slot} w-14 -translate-x-2 rotate-8 border-white/15 bg-white/2`} />
    </div>
  );
}

/** Propiedades de {@link ListSection}. */
interface ListSectionProps {
  title: string;
  /** Tarjetas (`<li>`) de la sección; vacío = no hay ningún título de este tipo. */
  children: ReactNode[];
  /** Frase para cuando la sección está vacía. */
  emptyText: string;
  /** Enlace para llenarla («Explorar series»...). */
  emptyAction: ReactNode;
}

/**
 * Una sección de "Mi lista" («Películas» o «Series») con su `<h2>`.
 *
 * Si está vacía (pero la otra no) no desaparece: dice que no hay nada y, si en
 * el catálogo hay algo de ese tipo, enlaza a donde encontrarlo (ver
 * {@link ExploreLink}). Así el usuario descubre que también puede guardar series
 * (o películas) y la página mantiene siempre la misma estructura.
 */
function ListSection({ title, children, emptyText, emptyAction }: ListSectionProps) {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="mb-10">
      <h2 id={headingId} className="mb-4 text-lg font-bold tracking-tight text-white">
        {title}
      </h2>
      {children.length > 0 ? (
        <ul role="list" className={POSTER_GRID_CLASS}>
          {children}
        </ul>
      ) : (
        // El texto ocupa la altura del enlace (44 px): si este aparece después (ver `ExploreLink`), el recuadro no crece.
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-xl border border-dashed border-white/15 px-4 py-2">
          <p className="flex min-h-11 items-center text-sm text-muted">{emptyText}</p>
          {emptyAction}
        </div>
      )}
    </section>
  );
}

/**
 * Texto del diálogo de "Vaciar lista" según lo que hay: "Se quitarán las 3
 * películas y la serie de Mi lista...". Nombra exactamente lo que se va a
 * borrar porque la acción no se puede deshacer.
 */
function describeClear(movieCount: number, seriesCount: number): string {
  const parts: string[] = [];
  if (movieCount > 0) parts.push(movieCount === 1 ? 'la película' : `las ${movieCount} películas`);
  if (seriesCount > 0) parts.push(seriesCount === 1 ? 'la serie' : `las ${seriesCount} series`);
  // Solo pasa mientras el diálogo se cierra tras vaciar (ya no hay nada): un texto con sentido, no "Se quitarán  de".
  if (parts.length === 0) return 'Se quitará todo el contenido de Mi lista. Esta acción no se puede deshacer.';
  const verb = movieCount + seriesCount === 1 ? 'Se quitará' : 'Se quitarán';
  return `${verb} ${parts.join(' y ')} de Mi lista. Esta acción no se puede deshacer.`;
}

/**
 * Página "Mi lista": las películas y las series favoritas del usuario, en dos
 * secciones con su encabezado («Películas» y «Series»).
 *
 * Los datos y las acciones vienen de la lista compartida (`useFavorites`), que
 * ya hace las peticiones y avisa con toasts de éxito o error. Aquí se resuelven:
 * - Los tres estados: cargando, error (con "Reintentar") y vacío (si no hay
 *   NINGÚN título de ningún tipo). El `<h1>` "Mi lista" está siempre presente.
 * - Pulsar una película abre su diálogo de detalles; pulsar una serie lleva a su página.
 * - La confirmación de "Vaciar lista" con {@link ConfirmDialog}: es destructiva,
 *   no se puede deshacer y vacía las dos secciones (el contexto explica qué pasa
 *   si una de las dos peticiones falla).
 */
export function MyListPage() {
  useDocumentTitle('Mi lista');
  const { movies, series, status, errorMessage, clear, reload } = useFavorites();
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [clearing, setClearing] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);
  // Se activa al vaciar la lista y se consume cuando el diálogo ya está cerrado (ver el efecto).
  const focusHeadingAfterClose = useRef(false);
  const totalCount = movies.length + series.length;

  const handleConfirmClear = async () => {
    setClearing(true);
    const done = await clear();
    setClearing(false);
    focusHeadingAfterClose.current = done;
    setConfirmOpen(false);
  };

  // El botón "Vaciar lista" desaparece al quedar la lista vacía; sin esto el foco
  // se perdería. Se lleva al título para no desorientar a quien usa teclado. Se
  // hace en un efecto y no en `handleConfirmClear` porque mientras el diálogo
  // modal está abierto el resto de la página es inerte y `focus()` no funcionaría.
  // Si el vaciado fue solo parcial, el botón sigue ahí y el diálogo le devuelve el foco.
  useEffect(() => {
    if (!confirmOpen && focusHeadingAfterClose.current) {
      focusHeadingAfterClose.current = false;
      headingRef.current?.focus();
    }
  }, [confirmOpen]);

  let content: ReactNode;
  if (status === 'loading') {
    content = <LoadingState label="Cargando tu lista..." />;
  } else if (status === 'error') {
    content = <ErrorState title="No se pudo cargar tu lista" message={errorMessage} onRetry={reload} />;
  } else if (totalCount === 0) {
    content = (
      <EmptyState
        visual={<EmptyListArt />}
        title="Tu lista está vacía"
        description="Pulsa «Mi lista» en cualquier película o serie del catálogo y la encontrarás aquí."
        action={
          <Link to="/" className={buttonClasses('light')}>
            Explorar catálogo
          </Link>
        }
      />
    );
  } else {
    content = (
      <>
        <ListSection
          title="Películas"
          emptyText="Todavía no has guardado ninguna película."
          emptyAction={
            <ExploreLink catalog="/movies" to="/peliculas">
              Explorar películas
            </ExploreLink>
          }
        >
          {movies.map((movie) => (
            <li key={movie.id}>
              <MovieCard movie={movie} onClick={setSelectedMovie} className="w-full" />
            </li>
          ))}
        </ListSection>
        <ListSection
          title="Series"
          emptyText="Todavía no has guardado ninguna serie."
          emptyAction={
            <ExploreLink catalog="/series" to="/series">
              Explorar series
            </ExploreLink>
          }
        >
          {series.map((item) => (
            <li key={item.id}>
              <SeriesCard series={item} className="w-full" />
            </li>
          ))}
        </ListSection>
      </>
    );
  }

  return (
    <div className="min-h-screen px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        {/* tabIndex -1: recibe el foco por código tras vaciar la lista; no es una parada de tabulador. */}
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-bold tracking-tight outline-hidden sm:text-3xl">
          Mi lista
        </h1>
        {status === 'ready' && totalCount > 0 && (
          <Button variant="outline" onClick={() => setConfirmOpen(true)} className="font-medium">
            <ListX aria-hidden="true" className="size-4" />
            Vaciar lista
          </Button>
        )}
      </div>

      {content}

      <ConfirmDialog
        open={confirmOpen}
        title="¿Vaciar tu lista?"
        description={describeClear(movies.length, series.length)}
        confirmLabel="Sí, vaciar lista"
        busy={clearing}
        onConfirm={() => void handleConfirmClear()}
        onCancel={() => setConfirmOpen(false)}
      />

      {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
    </div>
  );
}
