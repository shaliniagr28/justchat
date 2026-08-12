import type { User } from '../types';

export default function Header({ currentUser, onLogout }: { currentUser: User; onLogout: () => void }) {
  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '10px 16px',
      borderBottom: '1px solid #ddd',
      background: '#fafafa'
    }}>
      <span style={{ fontWeight: 600 }}>JustChat</span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ color: '#555' }}>{currentUser.username}</span>
        <button onClick={onLogout} style={{ cursor: 'pointer' }}>Log out</button>
      </div>
    </div>
  );
}
