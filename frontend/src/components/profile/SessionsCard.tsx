import { useState } from 'react';
import { MonitorSmartphone } from 'lucide-react';
import { Button } from '../Button';
import { ConfirmDialog } from '../ConfirmDialog';
import { useAuth } from '../../context/AuthContext';
import { ProfileCard } from './ProfileCard';

/**
 * Tarjeta «Sesiones» del perfil: «Cerrar sesión en todos los dispositivos»
 * (`POST /api/auth/logout-all`, vía `logoutAll` de `AuthContext`).
 *
 * Cierra TODAS las sesiones de la cuenta, también la de este dispositivo
 * (decisión del autor), así que pide confirmación (`ConfirmDialog`, con el foco
 * inicial en «Cancelar») y la explicación dice exactamente qué pasará:
 * - **204**: la sesión se cierra como con «Cerrar sesión» (las demás pestañas se
 *   enteran y la ruta protegida lleva al login, donde se ve el aviso de éxito).
 * - **Fallo** (500, red...): el servidor no ha borrado las cookies, así que la
 *   sesión SIGUE abierta; el diálogo sigue abierto con el motivo y el mismo botón
 *   sirve para reintentar.
 * - Mientras espera, el botón dice «Cerrando sesiones...» y no se puede cancelar
 *   (la petición ya está en el servidor; cancelar solo en pantalla mentiría).
 */
export function SessionsCard() {
  const { logoutAll } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const open = () => {
    setError('');
    setConfirming(true);
  };

  const confirm = async () => {
    setBusy(true);
    setError('');
    const result = await logoutAll();
    setBusy(false);
    // `null`: no se hizo nada porque la sesión ya se cerró por otro motivo (la ruta lleva al login).
    if (result && !result.closed) setError(result.message);
    else if (!result) setConfirming(false);
  };

  return (
    <ProfileCard title="Sesiones">
      <p className="text-sm leading-relaxed text-muted">
        ¿Has entrado desde un equipo que no es tuyo o has perdido un dispositivo? Cierra la sesión en todos a la vez,
        también en este.
      </p>
      <Button variant="outline" className="mt-4" onClick={open}>
        <MonitorSmartphone aria-hidden="true" className="size-4 shrink-0" />
        Cerrar sesión en todos los dispositivos
      </Button>
      <ConfirmDialog
        open={confirming}
        title="¿Cerrar sesión en todos los dispositivos?"
        description="Se cerrará tu sesión aquí y en cualquier otro navegador o dispositivo donde hayas entrado, y tendrás que volver a iniciar sesión. En los demás dispositivos puede tardar hasta 15 minutos en cerrarse."
        confirmLabel="Cerrar todas las sesiones"
        busyLabel="Cerrando sesiones..."
        busy={busy}
        error={error}
        onConfirm={() => void confirm()}
        onCancel={() => setConfirming(false)}
      />
    </ProfileCard>
  );
}
