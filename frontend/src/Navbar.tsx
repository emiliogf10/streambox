import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { LogOut, UserCircle } from 'lucide-react';
import { SearchBar } from './SearchBar';

export function Navbar() {
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const token = localStorage.getItem('token');
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('token');
    navigate('/login');
  };

  const linkClass = (path: string) =>
    `text-sm transition-colors ${location.pathname === path ? 'text-white font-semibold' : 'text-white/60 hover:text-white'}`;

  return (
    <header
      style={{ position: 'fixed', top: 0, left: 0, right: 0, zIndex: 50, height: '56px',
               display: 'flex', alignItems: 'center', padding: '0 24px',
               backgroundColor: '#0e1117', borderBottom: '1px solid rgba(255,255,255,0.05)' }}
    >
      {/* Logo + Nav */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '28px', flex: 1 }}>
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: '8px', textDecoration: 'none' }}>
          <div style={{ width: '10px', height: '10px', borderRadius: '50%', backgroundColor: '#f59e0b' }} />
          <span style={{ color: 'white', fontWeight: 700, fontSize: '14px', letterSpacing: '-0.02em' }}>
            streambox
          </span>
        </Link>

        {token && (
          <nav style={{ display: 'flex', alignItems: 'center', gap: '20px' }}>
            <Link to="/" className={linkClass('/')}>Inicio</Link>
            <span className="text-sm text-white/60" style={{ cursor: 'default' }}>Películas</span>
            <span className="text-sm text-white/60" style={{ cursor: 'default' }}>Series</span>
            <Link to="/favorites" className={linkClass('/favorites')}>Mi lista</Link>
          </nav>
        )}
      </div>

      {/* SearchBar + Avatar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
        {token && <SearchBar />}

        {token ? (
          <div style={{ position: 'relative' }} ref={menuRef}>
            <button
              onClick={() => setMenuOpen((v) => !v)}
              aria-label="Menú de usuario"
              aria-expanded={menuOpen}
              style={{ width: '32px', height: '32px', borderRadius: '50%',
                       backgroundColor: '#2a3148', border: 'none', cursor: 'pointer',
                       display: 'flex', alignItems: 'center', justifyContent: 'center' }}
            >
              <UserCircle style={{ width: '20px', height: '20px', color: '#9ca3af' }} />
            </button>

            {menuOpen && (
              <div
                style={{ position: 'absolute', right: 0, top: '40px', minWidth: '150px',
                         backgroundColor: '#161b27', border: '1px solid rgba(255,255,255,0.1)',
                         borderRadius: '12px', boxShadow: '0 20px 40px rgba(0,0,0,0.5)',
                         padding: '4px 0', zIndex: 60 }}
              >
                <button
                  onClick={handleLogout}
                  style={{ width: '100%', display: 'flex', alignItems: 'center', gap: '8px',
                           padding: '10px 16px', fontSize: '14px', color: '#d1d5db',
                           background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left' }}
                  onMouseEnter={(e) => (e.currentTarget.style.color = 'white')}
                  onMouseLeave={(e) => (e.currentTarget.style.color = '#d1d5db')}
                >
                  <LogOut style={{ width: '16px', height: '16px' }} />
                  Cerrar sesión
                </button>
              </div>
            )}
          </div>
        ) : (
          <Link
            to="/login"
            style={{ fontSize: '14px', fontWeight: 600, color: 'black',
                     backgroundColor: '#e8a020', padding: '6px 16px',
                     borderRadius: '8px', textDecoration: 'none' }}
          >
            Iniciar sesión
          </Link>
        )}
      </div>
    </header>
  );
}
