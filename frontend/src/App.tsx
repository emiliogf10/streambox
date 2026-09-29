import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './LoginPage';
import { HomePage } from './HomePage';
import { MyListPage } from './MyListPage';
import { AppShell } from './AppShell';

/** Ruta protegida: redirige a /login si no hay token */
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('token');
  return token ? <>{children}</> : <Navigate to="/login" replace />;
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <AppShell>
                <HomePage />
              </AppShell>
            </ProtectedRoute>
          }
        />
        <Route
          path="/favorites"
          element={
            <ProtectedRoute>
              <AppShell>
                <MyListPage />
              </AppShell>
            </ProtectedRoute>
          }
        />
        {/* Alias /my-list → /favorites por compatibilidad con enunciado */}
        <Route path="/my-list" element={<Navigate to="/favorites" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
