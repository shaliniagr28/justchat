import { useState } from 'react';
import { api } from '../api/api';
import type { Group, GroupDetail } from '../types';

export default function GroupList({ groups, selectedGroupId, unreadCounts, onSelect, onCreated }: {
  groups: Group[];
  selectedGroupId: number | null;
  unreadCounts: Map<number, number>;
  onSelect: (group: Group) => void;
  onCreated: (group: GroupDetail) => void;
}) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const cancel = () => {
    setCreating(false);
    setName('');
    setError(null);
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || busy) return;
    setBusy(true);
    api.createGroup(name.trim())
      .then(group => {
        cancel();
        onCreated(group);
      })
      .catch(err => setError(err.message))
      .finally(() => setBusy(false));
  };

  return (
    <div>
      {creating ? (
        <form onSubmit={submit} style={{ padding: 8, borderBottom: '1px solid #eee' }}>
          <input
            autoFocus
            placeholder="Group name"
            value={name}
            onChange={e => setName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Escape') cancel(); }}
            style={{ width: '100%', boxSizing: 'border-box', padding: 6 }}
          />
          {error && <div style={{ color: '#c62828', fontSize: 12, marginTop: 4 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
            <button type="submit" disabled={busy || !name.trim()}>Create</button>
            <button type="button" onClick={cancel}>Cancel</button>
          </div>
        </form>
      ) : (
        <div
          onClick={() => setCreating(true)}
          style={{ padding: '10px 12px', cursor: 'pointer', color: '#3366cc' }}
        >
          + New group
        </div>
      )}
      {groups.map(g => {
        const unread = unreadCounts.get(g.id) ?? 0;
        return (
          <div
            key={g.id}
            onClick={() => onSelect(g)}
            style={{
              padding: '10px 12px', cursor: 'pointer',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              background: g.id === selectedGroupId ? '#eef3ff' : 'transparent'
            }}
          >
            <span># {g.groupName}</span>
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
      {groups.length === 0 && !creating && (
        <div style={{ padding: 12, color: '#888', fontSize: 13 }}>No groups yet</div>
      )}
    </div>
  );
}
