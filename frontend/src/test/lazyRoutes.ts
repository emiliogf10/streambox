/**
 * Ayuda para los tests que montan las rutas reales (`AppRoutes`) de una
 * pantalla que se carga bajo demanda (`React.lazy` en `App.tsx`: registro,
 * recuperar y restablecer la contraseña, confirmar el correo y el panel).
 *
 * La primera vez que se pinta una de esas pantallas, React enseña su espera
 * («Cargando...») mientras llega el módulo, y en Vitest eso tarda lo que tarda
 * en transformarse el archivo. Un `getBy...` justo después de `render` miraría
 * la espera, no la pantalla.
 */
import { screen, waitFor } from '@testing-library/react';
import { expect } from 'vitest';

/**
 * Espera a que no quede ninguna espera de carga bajo demanda en pantalla
 * («Cargando...», «Cargando el panel...»). Si la pantalla ya estaba cargada,
 * vuelve al momento.
 */
export async function waitForLazyRoutes(): Promise<void> {
  await waitFor(() => expect(screen.queryByText(/^Cargando( el panel)?\.\.\.$/)).not.toBeInTheDocument());
}
