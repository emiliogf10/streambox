import { useAuth } from '../context/AuthContext';

/**
 * Escribe en pantalla el `userStatus` de la sesión («estado:ready»). Permite
 * esperar a que `/users/me` haya terminado cuando el resultado no cambia nada
 * visible (un usuario normal ve lo mismo que mientras carga), en lugar de
 * comprobar demasiado pronto y dar por bueno un estado que aún no ha llegado.
 */
export function UserStatusProbe() {
  const { userStatus } = useAuth();
  return <p>{`estado:${userStatus}`}</p>;
}
