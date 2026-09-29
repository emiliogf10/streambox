import { Navbar } from './Navbar';

interface Props {
  children: React.ReactNode;
}

export function AppShell({ children }: Props) {
  return (
    <div style={{ minHeight: '100vh', backgroundColor: '#0e1117', color: 'white' }}>
      <Navbar />
      <main style={{ paddingTop: '56px' }}>{children}</main>
    </div>
  );
}
