import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ListX, Plus } from 'lucide-react';
import { Button } from '../components/Button';
import { buttonClasses } from '../components/buttonStyles';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { MovieCard } from '../components/MovieCard';
import { MovieDetailsModal } from '../components/MovieDetailsModal';
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

/**
 * Página "Mi lista": las películas favoritas del usuario.
 *
 * Los datos y las acciones vienen de la lista compartida (`useFavorites`), que
 * ya hace la petición y avisa con toasts de éxito o error. Aquí se resuelven:
 * - Los tres estados: cargando, error (con "Reintentar") y vacío. El `<h1>`
 *   "Mi lista" está siempre presente, también durante la carga o el error.
 * - La confirmación de "Vaciar lista" con {@link ConfirmDialog}: es una acción
 *   destructiva y no se puede deshacer.
 * - Una rejilla de dos columnas en móvil que se ensancha con la pantalla.
 */
export function MyListPage() {
  useDocumentTitle('Mi lista');
  const { movies, status, errorMessage, clear, reload } = useFavorites();
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [clearing, setClearing] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);
  // Se activa al vaciar la lista y se consume cuando el diálogo ya está cerrado (ver el efecto).
  const focusHeadingAfterClose = useRef(false);

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
  } else if (movies.length === 0) {
    content = (
      <EmptyState
        visual={<EmptyListArt />}
        title="Tu lista está vacía"
        description="Pulsa «Mi lista» en cualquier película del catálogo y la encontrarás aquí."
        action={
          <Link to="/" className={buttonClasses('light')}>
            Explorar catálogo
          </Link>
        }
      />
    );
  } else {
    content = (
      <ul role="list" className="grid grid-cols-2 gap-4 sm:grid-cols-[repeat(auto-fill,minmax(10.5rem,1fr))]">
        {movies.map((movie) => (
          <li key={movie.id}>
            <MovieCard movie={movie} onClick={setSelectedMovie} className="w-full" />
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="min-h-screen px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        {/* tabIndex -1: recibe el foco por código tras vaciar la lista; no es una parada de tabulador. */}
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-bold tracking-tight outline-hidden sm:text-3xl">
          Mi lista
        </h1>
        {status === 'ready' && movies.length > 0 && (
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
        description={
          movies.length === 1
            ? 'Se quitará la película de Mi lista. Esta acción no se puede deshacer.'
            : `Se quitarán las ${movies.length} películas de Mi lista. Esta acción no se puede deshacer.`
        }
        confirmLabel="Sí, vaciar lista"
        busy={clearing}
        onConfirm={() => void handleConfirmClear()}
        onCancel={() => setConfirmOpen(false)}
      />

      {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
    </div>
  );
}
