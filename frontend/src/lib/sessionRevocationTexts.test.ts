/**
 * Textos que cuentan qué pasa con las demás sesiones al cerrarlas todas («Cerrar sesión en todos los dispositivos»,
 * cambiar la contraseña desde el perfil y restablecerla con el enlace del correo).
 *
 * **Qué protege.** Desde `users.token_version` (V12) el servidor anula al momento los JWT de acceso de la cuenta, así
 * que los demás dispositivos quedan fuera en su siguiente petición. Los avisos antiguos decían «puede tardar hasta
 * 15 minutos», que ahora es falso. Los tests de componentes importan estas constantes y pasarían igual con el texto
 * viejo: aquí se fija el texto literal (petición del autor: los textos importan tanto como el código) y, por si se
 * reescriben, que ninguno vuelva a prometer una espera.
 */
import { describe, expect, it } from 'vitest';
import { LOGOUT_ALL_DONE } from './logout';
import { RESET_SESSIONS_CLOSED } from './passwordReset';
import { PASSWORD_CHANGED } from './profile';

const MESSAGES = { LOGOUT_ALL_DONE, PASSWORD_CHANGED, RESET_SESSIONS_CLOSED };

describe('avisos del cierre de todas las sesiones', () => {
  it('dicen exactamente lo que hace el servidor', () => {
    expect(LOGOUT_ALL_DONE).toBe('Se ha cerrado la sesión en todos tus dispositivos.');
    expect(PASSWORD_CHANGED).toBe(
      'Contraseña cambiada. Se han cerrado tus sesiones en otros navegadores y dispositivos; en este sigues dentro.',
    );
    expect(RESET_SESSIONS_CLOSED).toBe('Por seguridad, se han cerrado todas las sesiones abiertas con tu cuenta.');
  });

  it.each(Object.entries(MESSAGES))('%s no promete ninguna espera en los demás dispositivos', (_name, message) => {
    expect(message).not.toMatch(/minuto|tardar|hasta que caduque/i);
  });
});
