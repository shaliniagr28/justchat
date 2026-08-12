import { useEffect, useState } from 'react';
import { api } from './api/api';
import { useChatSocket } from './hooks/useChatSocket';
import type { User } from './types';
import AuthForm from './components/AuthForm';
import Header from './components/Header';
import UserList from './components/UserList';
import ChatWindow from './components/ChatWindow';

export default function App() {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [checkingSession, setCheckingSession] = useState(true);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);

  const { connected, conversations, unreadCounts, sendMessage, loadThreadHistory } = useChatSocket(currentUser, selectedUser?.id ?? null);

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

  if (checkingSession) return null;
  if (!currentUser) return <AuthForm onAuthed={setCurrentUser} />;

  const messages = selectedUser ? (conversations.get(selectedUser.id) ?? []) : [];

  const handleLogout = () => {
    api.logout().catch(console.error).finally(() => {
      setCurrentUser(null);
      setSelectedUser(null);
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', fontFamily: 'sans-serif' }}>
      <Header currentUser={currentUser} onLogout={handleLogout} />
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <UserList selectedUserId={selectedUser?.id ?? null} onSelect={setSelectedUser} unreadCounts={unreadCounts} />
        {selectedUser ? (
          <ChatWindow
            currentUser={currentUser}
            otherUser={selectedUser}
            messages={messages}
            onSend={(content) => sendMessage(selectedUser.id, content)}
          />
        ) : (
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#888' }}>
            Select a user to start chatting {connected ? '' : '(connecting…)'}
          </div>
        )}
      </div>
    </div>
  );
}
