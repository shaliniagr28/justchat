import { useEffect, useRef, useState } from 'react';
import { api } from '../api/api';
import type { ChatMessage, GroupDetail, User } from '../types';

// Stable per sender, so the same person keeps the same name colour across the chat.
const SENDER_COLORS = ['#3366cc', '#a0522d', '#2e7d32', '#8e24aa', '#c62828', '#00838f'];

export default function GroupChatWindow({ currentUser, detail, messages, onSend, onDetailChange }: {
  currentUser: User;
  detail: GroupDetail;
  messages: ChatMessage[];
  onSend: (content: string) => void;
  onDetailChange: (detail: GroupDetail) => void;
}) {
  const [draft, setDraft] = useState('');
  const [newMember, setNewMember] = useState('');
  const [addError, setAddError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const isCreator = currentUser.id === detail.createdBy;
  const usernames = new Map(detail.members.map(m => [m.id, m.username]));

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  useEffect(() => {
    setNewMember('');
    setAddError(null);
  }, [detail.id]);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!draft.trim()) return;
    onSend(draft.trim());
    setDraft('');
  };

  const addMember = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newMember.trim() || adding) return;
    setAdding(true);
    api.addGroupMember(detail.id, newMember.trim())
      .then(updated => {
        setNewMember('');
        setAddError(null);
        onDetailChange(updated);
      })
      .catch(err => setAddError(err.message))
      .finally(() => setAdding(false));
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={{ padding: 12, borderBottom: '1px solid #ddd' }}>
        <div>
          <span style={{ fontWeight: 600 }}># {detail.groupName}</span>
          <span style={{ color: '#888', fontSize: 13 }}> · {detail.members.map(m => m.username).join(', ')}</span>
        </div>
        {isCreator && (
          <form onSubmit={addMember} style={{ display: 'flex', gap: 6, marginTop: 8 }}>
            <input
              placeholder="Add member by username"
              value={newMember}
              onChange={e => { setNewMember(e.target.value); setAddError(null); }}
              style={{ flex: 1, padding: 6, maxWidth: 280 }}
            />
            <button type="submit" disabled={adding || !newMember.trim()}>Add</button>
          </form>
        )}
        {addError && <div style={{ color: '#c62828', fontSize: 12, marginTop: 4 }}>{addError}</div>}
      </div>
      <div style={{ flex: 1, overflowY: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {messages.map(m => {
          if (m.system) {
            return (
              <div key={m.id} style={{ alignSelf: 'center', color: '#888', fontSize: 11 }}>{m.content}</div>
            );
          }
          const isMine = m.senderId === currentUser.id;
          return (
            <div
              key={m.clientMsgId ?? m.id}
              style={{
                alignSelf: isMine ? 'flex-end' : 'flex-start',
                background: isMine ? '#3366cc' : '#eee',
                color: isMine ? 'white' : 'black',
                borderRadius: 12, padding: '8px 12px', maxWidth: '60%',
                opacity: m.pending ? 0.7 : 1
              }}
            >
              {!isMine && (
                <div style={{
                  fontSize: 12, fontWeight: 600, marginBottom: 2,
                  color: SENDER_COLORS[m.senderId % SENDER_COLORS.length]
                }}>
                  {usernames.get(m.senderId) ?? `user ${m.senderId}`}
                </div>
              )}
              <div>{m.content}</div>
            </div>
          );
        })}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={submit} style={{ display: 'flex', borderTop: '1px solid #ddd' }}>
        <input
          value={draft}
          onChange={e => setDraft(e.target.value)}
          placeholder="Type a message"
          style={{ flex: 1, padding: 12, border: 'none' }}
        />
        <button type="submit" style={{ padding: '0 20px' }}>Send</button>
      </form>
    </div>
  );
}
