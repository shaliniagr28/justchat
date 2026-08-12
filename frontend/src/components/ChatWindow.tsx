import { useEffect, useRef, useState } from 'react';
import type { ChatMessage, User } from '../types';

export default function ChatWindow({
  currentUser, otherUser, messages, onSend
}: {
  currentUser: User;
  otherUser: User;
  messages: ChatMessage[];
  onSend: (content: string) => void;
}) {
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!draft.trim()) return;
    onSend(draft.trim());
    setDraft('');
  };

  const statusTick = (m: ChatMessage): { text: string; color?: string } | null => {
    if (m.senderId !== currentUser.id) return null;
    if (m.pending) return { text: '…' };
    if (m.status === 'DELIVERED') return { text: '✓✓' };
    return { text: '✓' };
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={{ padding: 12, borderBottom: '1px solid #ddd', fontWeight: 600 }}>{otherUser.username}</div>
      <div ref={containerRef} style={{ flex: 1, overflowY: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {messages.map(m => {
          const isMine = m.senderId === currentUser.id;
          const tick = statusTick(m);
          return (
            <div
              key={m.clientMsgId ?? m.id}
              data-msg-id={m.id}
              style={{
                alignSelf: isMine ? 'flex-end' : 'flex-start',
                background: isMine ? '#3366cc' : '#eee',
                color: isMine ? 'white' : 'black',
                borderRadius: 12, padding: '8px 12px', maxWidth: '60%'
              }}
            >
              <div>{m.content}</div>
              <div style={{ fontSize: 11, opacity: 0.7, textAlign: 'right', color: tick?.color }}>{tick?.text}</div>
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
