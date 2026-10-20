import type { ReactNode } from 'react';

export type SidebarTab = 'people' | 'groups';

function Badge({ count }: { count: number }) {
  if (count <= 0) return null;
  return (
    <span style={{
      background: '#e53935', color: '#fff', borderRadius: 10,
      padding: '1px 6px', fontSize: 11, marginLeft: 6
    }}>
      {count}
    </span>
  );
}

export default function Sidebar({ tab, onTab, peopleUnread, groupsUnread, children }: {
  tab: SidebarTab;
  onTab: (tab: SidebarTab) => void;
  peopleUnread: number;
  groupsUnread: number;
  children: ReactNode;
}) {
  const tabStyle = (active: boolean): React.CSSProperties => ({
    flex: 1, padding: '9px 0', textAlign: 'center', cursor: 'pointer',
    border: 'none', background: 'transparent', fontSize: 14,
    borderBottom: active ? '2px solid #3366cc' : '2px solid transparent',
    fontWeight: active ? 600 : 400, color: active ? '#000' : '#777'
  });

  return (
    <div style={{ width: 220, borderRight: '1px solid #ddd', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ display: 'flex', borderBottom: '1px solid #ddd' }}>
        <button style={tabStyle(tab === 'people')} onClick={() => onTab('people')}>
          People<Badge count={peopleUnread} />
        </button>
        <button style={tabStyle(tab === 'groups')} onClick={() => onTab('groups')}>
          Groups<Badge count={groupsUnread} />
        </button>
      </div>
      <div style={{ flex: 1, overflowY: 'auto' }}>{children}</div>
    </div>
  );
}
