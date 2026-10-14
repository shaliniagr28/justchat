import { useEffect, useRef, useState } from 'react';
import { api } from './api/api';
import { useChatSocket } from './hooks/useChatSocket';
import type { Group, GroupDetail, User } from './types';
import AuthForm from './components/AuthForm';
import Header from './components/Header';
import UserList from './components/UserList';
import ChatWindow from './components/ChatWindow';
import Sidebar, { type SidebarTab } from './components/Sidebar';
import GroupList from './components/GroupList';
import GroupChatWindow from './components/GroupChatWindow';
import { acceptDetail } from './lib/groupSelection';

type Selection = { kind: 'user'; user: User } | { kind: 'group'; group: Group } | null;

const total = (counts: Map<number, number>) => [...counts.values()].reduce((a, b) => a + b, 0);

export default function App() {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [checkingSession, setCheckingSession] = useState(true);
  const [selection, setSelection] = useState<Selection>(null);
  const [tab, setTab] = useState<SidebarTab>('people');
  const [groups, setGroups] = useState<Group[]>([]);
  const [groupDetail, setGroupDetail] = useState<GroupDetail | null>(null);

  const selectedUser = selection?.kind === 'user' ? selection.user : null;
  const selectedGroup = selection?.kind === 'group' ? selection.group : null;

  const {
    connected, conversations, unreadCounts, sendMessage, loadThreadHistory,
    groupConversations, groupUnreadCounts, sendGroupMessage, mergeGroupHistory, groupsVersion, connectionEpoch
  } = useChatSocket(currentUser, selectedUser?.id ?? null, selectedGroup?.id ?? null);

  // On load, check for an existing session cookie rather than always showing the login
  // form - lets a page refresh resume the session instead of forcing a re-login every time.
  useEffect(() => {
    api.me().then(setCurrentUser).catch(() => {}).finally(() => setCheckingSession(false));
  }, []);

  useEffect(() => {
    if (selectedUser) {
      api.thread(selectedUser.id).then(history => loadThreadHistory(selectedUser.id, history)).catch(console.error);
    }
  }, [selectedUser, loadThreadHistory]);

  // Group list: on login, when we're added to a group (GROUP_ADDED), and after a reconnect.
  useEffect(() => {
    if (!currentUser) return;
    api.listGroups().then(setGroups).catch(console.error);
  }, [currentUser, groupsVersion, connectionEpoch]);

  // Opening a group (or reconnecting while one is open) loads its members and full history.
  // History is merged, not replaced, so live messages that arrive meanwhile survive.
  const selectedGroupId = selectedGroup?.id ?? null;
  const selectedGroupIdRef = useRef(selectedGroupId);
  selectedGroupIdRef.current = selectedGroupId;
  const showDetail = (d: GroupDetail) => setGroupDetail(cur => acceptDetail(cur, d, selectedGroupIdRef.current));
  useEffect(() => {
    if (selectedGroupId == null) {
      setGroupDetail(null);
      return;
    }
    setGroupDetail(prev => (prev?.id === selectedGroupId ? prev : null));
    api.groupDetail(selectedGroupId).then(showDetail).catch(console.error);
    api.groupMessages(selectedGroupId).then(h => mergeGroupHistory(selectedGroupId, h)).catch(console.error);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedGroupId, connectionEpoch, mergeGroupHistory]);

  // A new "X added Y" line in the open group means the member list changed - refresh it.
  const groupMessages = selectedGroupId != null ? (groupConversations.get(selectedGroupId) ?? []) : [];
  const systemCount = groupMessages.filter(m => m.system).length;
  const lastSystemCount = useRef<{ groupId: number | null; count: number }>({ groupId: null, count: 0 });
  useEffect(() => {
    const last = lastSystemCount.current;
    lastSystemCount.current = { groupId: selectedGroupId, count: systemCount };
    if (selectedGroupId == null || last.groupId !== selectedGroupId || systemCount <= last.count) return;
    api.groupDetail(selectedGroupId).then(showDetail).catch(console.error);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedGroupId, systemCount]);

  if (checkingSession) return null;
  if (!currentUser) return <AuthForm onAuthed={setCurrentUser} />;

  const messages = selectedUser ? (conversations.get(selectedUser.id) ?? []) : [];

  const handleLogout = () => {
    api.logout().catch(console.error).finally(() => {
      setCurrentUser(null);
      setSelection(null);
      setGroups([]);
    });
  };

  const handleGroupCreated = (created: GroupDetail) => {
    setGroups(prev => [...prev, created]);
    selectedGroupIdRef.current = created.id;
    setGroupDetail(created);
    setSelection({ kind: 'group', group: created });
  };

  let main;
  if (selectedUser) {
    main = (
      <ChatWindow
        currentUser={currentUser}
        otherUser={selectedUser}
        messages={messages}
        onSend={(content) => sendMessage(selectedUser.id, content)}
      />
    );
  } else if (selectedGroup && groupDetail?.id === selectedGroup.id) {
    main = (
      <GroupChatWindow
        currentUser={currentUser}
        detail={groupDetail}
        messages={groupMessages}
        onSend={(content) => sendGroupMessage(selectedGroup.id, content)}
        onDetailChange={showDetail}
      />
    );
  } else {
    main = (
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#888' }}>
        {selectedGroup ? 'Loading group…' : `Select a user or group to start chatting ${connected ? '' : '(connecting…)'}`}
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', fontFamily: 'sans-serif' }}>
      <Header currentUser={currentUser} onLogout={handleLogout} />
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <Sidebar tab={tab} onTab={setTab} peopleUnread={total(unreadCounts)} groupsUnread={total(groupUnreadCounts)}>
          {tab === 'people' ? (
            <UserList
              selectedUserId={selectedUser?.id ?? null}
              onSelect={user => setSelection({ kind: 'user', user })}
              unreadCounts={unreadCounts}
            />
          ) : (
            <GroupList
              groups={groups}
              selectedGroupId={selectedGroupId}
              unreadCounts={groupUnreadCounts}
              onSelect={group => setSelection({ kind: 'group', group })}
              onCreated={handleGroupCreated}
            />
          )}
        </Sidebar>
        {main}
      </div>
    </div>
  );
}
