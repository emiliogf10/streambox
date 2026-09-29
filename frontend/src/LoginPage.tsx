import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { API_URL } from './api';

export function LoginPage() {
  const [email, setEmail]       = useState('');
  const [password, setPassword] = useState('');
  const [error, setError]       = useState('');
  const [loading, setLoading]   = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    if (localStorage.getItem('token')) navigate('/', { replace: true });
  }, [navigate]);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await fetch(`${API_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      if (!res.ok) {
        throw new Error(
          res.status === 401 ? 'Credenciales incorrectas.' :
          res.status === 403 ? 'Acceso denegado.' :
          'Error al iniciar sesión. Inténtalo de nuevo.'
        );
      }
      const data = await res.json();
      localStorage.setItem('token', data.token);
      navigate('/');
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', backgroundColor: '#0e1117', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '16px', color: 'white' }}>
      
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '40px' }}>
        <div style={{ width: '10px', height: '10px', borderRadius: '50%', backgroundColor: '#f59e0b' }} />
        <span style={{ fontWeight: 700, fontSize: '18px', letterSpacing: '-0.02em' }}>streambox</span>
      </div>

      <div style={{ width: '100%', maxWidth: '400px', backgroundColor: '#161b27', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '16px', padding: '32px', boxShadow: '0 25px 50px -12px rgba(0,0,0,0.5)' }}>
        <h1 style={{ fontSize: '24px', fontWeight: 700, margin: '0 0 4px' }}>Bienvenido de nuevo</h1>
        <p style={{ color: '#8892a4', fontSize: '14px', margin: '0 0 28px' }}>Accede a tu cuenta para continuar.</p>

        {error && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', backgroundColor: 'rgba(127,29,29,0.4)', border: '1px solid rgba(239,68,68,0.4)', borderRadius: '8px', color: '#fca5a5', fontSize: '14px' }}>
            {error}
          </div>
        )}

        <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div>
            <label htmlFor="sb-email" style={{ display: 'block', fontSize: '14px', fontWeight: 500, color: '#d1d5db', marginBottom: '6px' }}>Correo electrónico</label>
            <input
              id="sb-email" type="email" autoComplete="email" placeholder="tu@correo.com" required
              value={email} onChange={(e) => setEmail(e.target.value)}
              style={{ width: '100%', padding: '10px 16px', borderRadius: '8px', backgroundColor: '#0e1117', border: '1px solid rgba(255,255,255,0.15)', color: 'white', fontSize: '14px', outline: 'none' }}
            />
          </div>

          <div>
            <label htmlFor="sb-password" style={{ display: 'block', fontSize: '14px', fontWeight: 500, color: '#d1d5db', marginBottom: '6px' }}>Contraseña</label>
            <input
              id="sb-password" type="password" autoComplete="current-password" placeholder="••••••••" required
              value={password} onChange={(e) => setPassword(e.target.value)}
              style={{ width: '100%', padding: '10px 16px', borderRadius: '8px', backgroundColor: '#0e1117', border: '1px solid rgba(255,255,255,0.15)', color: 'white', fontSize: '14px', outline: 'none' }}
            />
          </div>

          <button
            type="submit" disabled={loading}
            style={{ marginTop: '8px', width: '100%', backgroundColor: '#e8a020', color: 'black', fontWeight: 700, padding: '10px', borderRadius: '8px', border: 'none', cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.6 : 1 }}
          >
            {loading ? 'Entrando...' : 'Iniciar sesión'}
          </button>
        </form>
      </div>
    </div>
  );
}
