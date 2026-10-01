import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { useAuth } from '../context/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import type { LoginResponse } from '../lib/types';

/**
 * Pantalla de inicio de sesión (`POST /api/auth/login`).
 *
 * Errores tratados de forma distinta según `status`:
 * - 401: credenciales incorrectas (mensaje genérico: no revela si falla el correo o la contraseña).
 * - 429: demasiados intentos o cuenta bloqueada; el botón se bloquea con cuenta atrás (`Retry-After`).
 * - Red caída / 5xx / otros: el mensaje que ya prepara `apiFetch`.
 *
 * Al iniciar sesión solo se guarda el token con `login()`: `RedirectIfAuthenticated`
 * (en `App.tsx`) se encarga de llevar al usuario a la portada.
 */
export function LoginPage() {
  useDocumentTitle('Iniciar sesión');
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  // Al acabar la espera se retira el aviso de "demasiados intentos".
  const { remaining, start } = useCountdown(() => setError(''));

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    if (!email.trim() || !password) {
      setError('Introduce tu correo electrónico y tu contraseña.');
      return;
    }
    setLoading(true);
    try {
      const { token } = await apiFetch<LoginResponse>('/auth/login', {
        method: 'POST',
        body: { email: email.trim(), password },
        public: true, // un 401 aquí significa "credenciales incorrectas", no "sesión caducada"
      });
      login(token);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('Correo o contraseña incorrectos.');
      } else {
        if (err instanceof ApiError && err.status === 429 && err.retryAfterSeconds) {
          start(err.retryAfterSeconds);
        }
        setError(getErrorMessage(err));
      }
    } finally {
      setLoading(false);
    }
  };

  const blocked = remaining > 0;

  return (
    <AuthLayout
      title="Bienvenido de nuevo"
      subtitle="Accede a tu cuenta para continuar."
      footer={
        <>
          ¿No tienes cuenta?{' '}
          <Link to="/registro" className="focus-ring font-semibold text-accent underline-offset-2 hover:underline">
            Regístrate
          </Link>
        </>
      }
    >
      <FormAlert message={error} />

      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          id="login-email"
          label="Correo electrónico"
          type="email"
          autoComplete="email"
          placeholder="tu@correo.com"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <FormField
          id="login-password"
          label="Contraseña"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" disabled={loading || blocked} className="mt-2 w-full">
          {loading ? 'Entrando...' : blocked ? `Reintentar en ${remaining} s` : 'Iniciar sesión'}
        </Button>
      </form>
    </AuthLayout>
  );
}
