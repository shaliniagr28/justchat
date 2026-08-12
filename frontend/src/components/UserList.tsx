import { useEffect, useState } from 'react';
import { api } from '../api/api';
import type { User } from '../types';

export default function UserList({ selectedUserId, onSelect, unreadCounts }: {
  selectedUserId: number | null;
  onSelect: (user: User) => void;
  unreadCounts: Map<number, number>;
}) {
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState<User[]>([]);

  useEffect(() => {
    // Simple debounce - avoids firing a request per keystroke on the search box.
    const handle = setTimeout(() => {
      api.listUsers(query).then(setUsers).catch(console.error);
    }, 500);
    return () => clearTimeout(handle);
  }, [query]);

  return (
    <div style={{ width: 220, borderRight: '1px solid #ddd', height: '100vh', overflowY: 'auto' }}>
      <input
        placeholder="Search users..."
        value={query}
        onChange={e => setQuery(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box', padding: 8, border: 'none', borderBottom: '1px solid #ddd' }}
      />
      {users.map(u => {
        const unread = unreadCounts.get(u.id) ?? 0;
        return (
          <div
            key={u.id}
            onClick={() => onSelect(u)}
            style={{
              padding: '10px 12px', cursor: 'pointer',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              background: u.id === selectedUserId ? '#eef3ff' : 'transparent'
            }}
          >
            <span>{u.username}</span>
            {unread > 0 && (
              <span style={{
                background: '#e53935', color: '#fff', borderRadius: 10,
                padding: '1px 7px', fontSize: 12, minWidth: 8, textAlign: 'center'
              }}>
                {unread}
              </span>
            )}
          </div>
        );
      })}
      {users.length === 0 && <div style={{ padding: 12, color: '#888', fontSize: 13 }}>No users found</div>}
    </div>
  );
}
