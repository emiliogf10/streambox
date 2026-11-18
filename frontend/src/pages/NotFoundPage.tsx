import { Link } from 'react-router-dom';
import { SearchX } from 'lucide-react';
import { buttonClasses } from '../components/buttonStyles';
import { EmptyState } from '../components/EmptyState';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * Página «no encontrada» para cualquier ruta que no existe (`path="*"`).
 *
 * **Por qué una página y no una redirección** (revisión de 2026-11, E-12).
 * Antes, una dirección desconocida llevaba al inicio sin decir nada: quien
 * seguía un enlace roto o mal escrito no sabía qué había pasado y podía creer
 * que la página existía y era la portada. Ahora se dice, con el mismo
 * {@link EmptyState} de «Película no encontrada» o «Serie no encontrada».
 *
 * **Texto verdadero para cada rol.** Vive dentro de `AppShell` (con la barra,
 * tras la sesión), así que la ve un USER o un ADMIN, y lo que dice vale para
 * los dos: la dirección no corresponde a ninguna página. La salida, «Ir al
 * inicio», lleva a la portada, que nunca es otra página vacía sin salida (si el
 * catálogo está vacío, la portada ya explica por qué y qué hacer). Sin sesión,
 * `RequireAuth` lleva al login, como en cualquier otra ruta privada: así una
 * dirección inventada no revela nada de la aplicación a quien no ha entrado.
 *
 * El título es el `h1` de la página (`titleAs="h1"`): al llegar, `RouteChangeFocus`
 * lleva el foco al contenido y el lector de pantalla anuncia «Página no encontrada».
 */
export function NotFoundPage() {
  useDocumentTitle('Página no encontrada');
  return (
    <div className="px-4 py-6 sm:px-6">
      <EmptyState
        titleAs="h1"
        icon={<SearchX className="size-8" />}
        title="Página no encontrada"
        description="Esta dirección no corresponde a ninguna página de StreamBox. Puede que el enlace esté mal escrito o que la página ya no exista."
        action={
          <Link to="/" className={buttonClasses('light')}>
            Ir al inicio
          </Link>
        }
      />
    </div>
  );
}
