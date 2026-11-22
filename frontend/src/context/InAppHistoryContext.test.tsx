/**
 * Tests de `InAppHistoryProvider`: la cuenta de pantallas de StreamBox que hay
 * detrás de cada entrada del historial, de la que depende que «Volver» del
 * reproductor pueda hacer `navigate(-1)` sin sacar al usuario de la aplicación.
 *
 * Se prueba con un `MemoryRouter` y un botón que pregunta por la entrada actual,
 * navegando como lo hace la aplicación: enlaces (`PUSH`), sustituciones
 * (`replace`, `<Navigate replace>`) y «Atrás» (`POP`).
 */
import { StrictMode, useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { InAppHistoryProvider, useHasAppEntryBehind } from './InAppHistoryContext';

/** Pregunta (al pulsar, como el reproductor) si hay pantalla de StreamBox detrás de la entrada actual. */
function Probe() {
  const { key, pathname } = useLocation();
  const navigate = useNavigate();
  const hasAppEntryBehind = useHasAppEntryBehind();
  const [answer, setAnswer] = useState('sin preguntar');
  return (
    <div>
      <p>en:{pathname}</p>
      <button type="button" onClick={() => setAnswer(hasAppEntryBehind(key) ? 'hay pantalla detrás' : 'es la primera')}>
        Preguntar
      </button>
      <button type="button" onClick={() => void navigate('/b', { replace: true })}>
        Sustituir por b
      </button>
      <button type="button" onClick={() => void navigate(-1)}>
        Atrás
      </button>
      <Link to="/b">Ir a b</Link>
      <Link to="/c">Ir a c</Link>
      <Link to="/redirige">Ir a redirige</Link>
      <p>{answer}</p>
    </div>
  );
}

function renderAt(route: string, { strict = false, withProvider = true } = {}) {
  const routes = (
    <Routes>
      <Route path="/redirige" element={<Navigate to="/c" replace />} />
      <Route path="*" element={<Probe />} />
    </Routes>
  );
  const tree = (
    <MemoryRouter initialEntries={[route]}>
      {withProvider ? <InAppHistoryProvider>{routes}</InAppHistoryProvider> : routes}
    </MemoryRouter>
  );
  render(strict ? <StrictMode>{tree}</StrictMode> : tree);
  return userEvent.setup();
}

async function ask(user: ReturnType<typeof userEvent.setup>): Promise<string> {
  await user.click(screen.getByRole('button', { name: 'Preguntar' }));
  return screen.getByText(/hay pantalla detrás|es la primera/).textContent ?? '';
}

describe('InAppHistoryProvider', () => {
  it('la primera entrada de la visita (enlace pegado, recarga) no tiene nada detrás', async () => {
    const user = renderAt('/a');
    expect(await ask(user)).toBe('es la primera');
  });

  it('tras un enlace de la aplicación (`PUSH`), sí', async () => {
    const user = renderAt('/a');
    await user.click(screen.getByRole('link', { name: 'Ir a b' }));
    await screen.findByText('en:/b');
    expect(await ask(user)).toBe('hay pantalla detrás');
  });

  it('sustituir la primera entrada (`replace`, como «Ver el siguiente») le da otra clave, pero sigue siendo la primera', async () => {
    const user = renderAt('/a');
    await user.click(screen.getByRole('button', { name: 'Sustituir por b' }));
    await screen.findByText('en:/b');
    expect(await ask(user)).toBe('es la primera');
  });

  it('sustituir una entrada que sí tenía pantalla detrás conserva esa pantalla', async () => {
    const user = renderAt('/a');
    await user.click(screen.getByRole('link', { name: 'Ir a c' }));
    await screen.findByText('en:/c');
    await user.click(screen.getByRole('button', { name: 'Sustituir por b' }));
    await screen.findByText('en:/b');
    expect(await ask(user)).toBe('hay pantalla detrás');
  });

  it('pasar por un `<Navigate replace>` (como la bienvenida) no cuenta como pantalla', async () => {
    const user = renderAt('/redirige');
    await screen.findByText('en:/c');
    expect(await ask(user)).toBe('es la primera');
  });

  it('al volver «Atrás» (`POP`) a la primera entrada, recuerda que es la primera', async () => {
    const user = renderAt('/a');
    await user.click(screen.getByRole('link', { name: 'Ir a b' }));
    await screen.findByText('en:/b');
    await user.click(screen.getByRole('button', { name: 'Atrás' }));
    await screen.findByText('en:/a');
    expect(await ask(user)).toBe('es la primera');
  });

  it('en modo estricto (efectos dos veces) no cuenta una pantalla de más', async () => {
    const user = renderAt('/a', { strict: true });
    await user.click(screen.getByRole('button', { name: 'Sustituir por b' }));
    await screen.findByText('en:/b');
    expect(await ask(user)).toBe('es la primera');
  });

  it('sin el proveedor responde siempre que es la primera (la respuesta segura)', async () => {
    const user = renderAt('/a', { withProvider: false });
    await user.click(screen.getByRole('link', { name: 'Ir a b' }));
    await screen.findByText('en:/b');
    expect(await ask(user)).toBe('es la primera');
  });
});
