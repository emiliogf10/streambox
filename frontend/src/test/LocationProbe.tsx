import { useLocation } from 'react-router-dom';

/** Escribe en pantalla la ruta actual: permite comprobar redirecciones sin mirar el router por dentro. */
export function LocationProbe() {
  const location = useLocation();
  return <p>{`ruta:${location.pathname}`}</p>;
}
