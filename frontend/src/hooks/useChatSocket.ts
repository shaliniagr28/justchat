import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatMessage, User, WsEnvelope } from '../types';

const RECONNECT_DELAY_MS = 2000;
const MAX_RECONNECT_ATTEMPTS = 5;

// Status only ever moves forward; a late or out-of-order STATUS_UPDATE must not undo READ.
const STATUS_RANK: Record<ChatMessage['status'], number> = { SENT: 0, DELIVERED: 1, READ: 2 };

// "Active" = this tab is visible and the browser window has focus. Messages only count as
// read while this is true, so a background tab or minimized window leaves them DELIVERED.
function isWindowActive() {
  return document.visibilityState === 'visible' && document.hasFocus();
}

// Our own send comes back as a MESSAGE echo carrying clientMsgId: swap the optimistic entry
// for the server row. If that row is already present (a history fetch landed first), just
// drop the optimistic entry rather than end up with two copies.
function reconcileIncoming(list: ChatMessage[], incoming: ChatMessage, clientMsgId?: string): ChatMessage[] {
  const alreadyHave = list.some(m => m.id === incoming.id);
  if (clientMsgId) {
    const idx = list.findIndex(m => m.clientMsgId === clientMsgId);
    if (idx !== -1) {
      return alreadyHave ? list.filter((_, i) => i !== idx) : list.map((m, i) => (i === idx ? incoming : m));
    }
  }
  return alreadyHave ? list : [...list, incoming];
}

function bump(counts: Map<number, number>, key: number) {
  const next = new Map(counts);
  next.set(key, (next.get(key) ?? 0) + 1);
  return next;
}

function cleared(counts: Map<number, number>, key: number) {
  if (!counts.get(key)) return counts;
  const next = new Map(counts);
  next.set(key, 0);
  return next;
}

export function useChatSocket(
  currentUser: User | null,
  selectedUserId: number | null = null,
  selectedGroupId: number | null = null
) {
  const [connected, setConnected] = useState(false);
  const [conversations, setConversations] = useState<Map<number, ChatMessage[]>>(new Map());
  // unreadCounts: otherUserId -> messages received while that thread wasn't the selected one.
  // Session-only - not persisted, resets on reload.
  const [unreadCounts, setUnreadCounts] = useState<Map<number, number>>(new Map());
  const [windowActive, setWindowActive] = useState(isWindowActive);
  // Group messages live apart from 1:1 conversations, keyed by groupId, so they never touch
  // 1:1 unread counts or read receipts. Kept even for a group not yet in the list (a new
  // member's "added" line can arrive before GROUP_ADDED triggers the list refresh).
  const [groupConversations, setGroupConversations] = useState<Map<number, ChatMessage[]>>(new Map());
  const [groupUnreadCounts, setGroupUnreadCounts] = useState<Map<number, number>>(new Map());
  // Bumped on GROUP_ADDED so the app refetches its group list.
  const [groupsVersion, setGroupsVersion] = useState(0);
  // Bumped on every successful (re)connect. There's no socket replay for groups, so the app
  // refetches the open group's history when this changes to fill any gap from a drop.
  const [connectionEpoch, setConnectionEpoch] = useState(0);
  const wsRef = useRef<WebSocket | null>(null);
  const attemptsRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const intentionalCloseRef = useRef(false);
  const selectedUserIdRef = useRef(selectedUserId);
  useEffect(() => {
    selectedUserIdRef.current = selectedUserId;
    if (selectedUserId != null) setUnreadCounts(prev => cleared(prev, selectedUserId));
  }, [selectedUserId]);
  const selectedGroupIdRef = useRef(selectedGroupId);
  useEffect(() => {
    selectedGroupIdRef.current = selectedGroupId;
    if (selectedGroupId != null) setGroupUnreadCounts(prev => cleared(prev, selectedGroupId));
  }, [selectedGroupId]);

  const otherPartyOf = useCallback((m: ChatMessage) => {
    if (!currentUser) return m.recipientId!;
    return m.senderId === currentUser.id ? m.recipientId! : m.senderId;
  }, [currentUser]);

  const upsertMessage = useCallback((otherUserId: number, updater: (list: ChatMessage[]) => ChatMessage[]) => {
    setConversations(prev => {
      const next = new Map(prev);
      next.set(otherUserId, updater(next.get(otherUserId) ?? []));
      return next;
    });
  }, []);

  const upsertGroupMessage = useCallback((groupId: number, updater: (list: ChatMessage[]) => ChatMessage[]) => {
    setGroupConversations(prev => {
      const next = new Map(prev);
      next.set(groupId, updater(next.get(groupId) ?? []));
      return next;
    });
  }, []);

  const applyStatusUpdate = useCallback((id: number, status: ChatMessage['status']) => {
    setConversations(prev => {
      const next = new Map(prev);
      for (const [key, list] of next) {
        const idx = list.findIndex(m => m.id === id);
        if (idx !== -1) {
          if (STATUS_RANK[status] <= STATUS_RANK[list[idx].status]) return prev;
          const updated = [...list];
          updated[idx] = { ...updated[idx], status };
          next.set(key, updated);
          break;
        }
      }
      return next;
    });
  }, []);

  const handleEnvelope = useCallback((raw: string) => {
    const env: WsEnvelope = JSON.parse(raw);
    switch (env.type) {
      case 'MESSAGE': {
        const incoming: ChatMessage = {
          id: env.id!, senderId: env.senderId!, recipientId: env.recipientId ?? null,
          groupId: env.groupId ?? null, system: env.system ?? false,
          content: env.content!, status: env.status!, sentAt: env.sentAt!
        };
        const fromSomeoneElse = incoming.senderId !== currentUser?.id;
        if (incoming.groupId != null) {
          const groupId = incoming.groupId;
          if (fromSomeoneElse && groupId !== selectedGroupIdRef.current) {
            setGroupUnreadCounts(prev => bump(prev, groupId));
          }
          upsertGroupMessage(groupId, list => reconcileIncoming(list, incoming, env.clientMsgId));
          break;
        }
        const otherUserId = otherPartyOf(incoming);
        // Exclude our own sent messages - their server echo shouldn't bump the badge.
        if (fromSomeoneElse && otherUserId !== selectedUserIdRef.current) {
          setUnreadCounts(prev => bump(prev, otherUserId));
        }
        upsertMessage(otherUserId, list => reconcileIncoming(list, incoming, env.clientMsgId));
        break;
      }
      case 'STATUS_UPDATE':
        applyStatusUpdate(env.id!, env.status!);
        break;
      case 'BACKLOG_DONE':
        break;
      case 'GROUP_ADDED':
        setGroupsVersion(v => v + 1);
        break;
      case 'ERROR':
        console.error('Server rejected message:', env.error);
        break;
    }
  }, [currentUser?.id, otherPartyOf, upsertMessage, upsertGroupMessage, applyStatusUpdate]);

  const connect = useCallback(() => {
    if (!currentUser) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${protocol}://${window.location.host}/ws/chat`);
    wsRef.current = ws;

    ws.onopen = () => {
      attemptsRef.current = 0;
      setConnected(true);
      setConnectionEpoch(e => e + 1);
    };
    ws.onmessage = (event) => handleEnvelope(event.data);
    ws.onclose = () => {
      setConnected(false);
      if (intentionalCloseRef.current) return;
      if (attemptsRef.current >= MAX_RECONNECT_ATTEMPTS) {
        console.error('Giving up reconnecting after', MAX_RECONNECT_ATTEMPTS, 'attempts');
        return;
      }
      attemptsRef.current += 1;
      reconnectTimerRef.current = setTimeout(connect, RECONNECT_DELAY_MS);
    };
    ws.onerror = () => ws.close();
  }, [currentUser, handleEnvelope]);

  useEffect(() => {
    if (!currentUser) return;
    intentionalCloseRef.current = false;
    connect();
    return () => {
      intentionalCloseRef.current = true;
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
      wsRef.current?.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentUser?.id]);

  useEffect(() => {
    const update = () => setWindowActive(isWindowActive());
    window.addEventListener('focus', update);
    window.addEventListener('blur', update);
    document.addEventListener('visibilitychange', update);
    return () => {
      window.removeEventListener('focus', update);
      window.removeEventListener('blur', update);
      document.removeEventListener('visibilitychange', update);
    };
  }, []);

  // Read receipts: whenever the open conversation has messages from the other person that
  // aren't READ yet, and the window is active, tell the server we've read up to the newest
  // one we're showing. Marking them READ locally too stops this from re-firing.
  useEffect(() => {
    if (!currentUser || selectedUserId == null || !windowActive || !connected) return;
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const unread = (conversations.get(selectedUserId) ?? [])
      .filter(m => m.senderId === selectedUserId && m.status !== 'READ');
    if (unread.length === 0) return;
    const upToId = Math.max(...unread.map(m => m.id));
    ws.send(JSON.stringify({ type: 'READ', senderId: selectedUserId, id: upToId }));
    upsertMessage(selectedUserId, list => list.map(m =>
      m.senderId === selectedUserId && m.id <= upToId && m.status !== 'READ' ? { ...m, status: 'READ' } : m));
  }, [currentUser, selectedUserId, windowActive, connected, conversations, upsertMessage]);

  const sendMessage = useCallback((recipientId: number, content: string) => {
    if (!currentUser || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    const clientMsgId = crypto.randomUUID();
    const optimistic: ChatMessage = {
      id: -Date.now(), senderId: currentUser.id, recipientId, content,
      status: 'SENT', sentAt: new Date().toISOString(), clientMsgId, pending: true
    };
    upsertMessage(recipientId, list => [...list, optimistic]);
    wsRef.current.send(JSON.stringify({ type: 'SEND', recipientId, content, clientMsgId }));
  }, [currentUser, upsertMessage]);

  const sendGroupMessage = useCallback((groupId: number, content: string) => {
    if (!currentUser || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    const clientMsgId = crypto.randomUUID();
    const optimistic: ChatMessage = {
      id: -Date.now(), senderId: currentUser.id, recipientId: null, groupId, content,
      status: 'SENT', sentAt: new Date().toISOString(), clientMsgId, pending: true
    };
    upsertGroupMessage(groupId, list => [...list, optimistic]);
    wsRef.current.send(JSON.stringify({ type: 'SEND', groupId, content, clientMsgId }));
  }, [currentUser, upsertGroupMessage]);

  // Merge, don't replace: a live message or our own pending send can land while the history
  // request is in flight, and replacing would drop it.
  const mergeGroupHistory = useCallback((groupId: number, history: ChatMessage[]) => {
    upsertGroupMessage(groupId, existing => {
      const historyIds = new Set(history.map(m => m.id));
      const extras = existing.filter(m => !historyIds.has(m.id));
      const settled = [...history, ...extras.filter(m => !m.pending)].sort((a, b) => a.id - b.id);
      return [...settled, ...extras.filter(m => m.pending)];
    });
  }, [upsertGroupMessage]);

  const loadThreadHistory = useCallback((otherUserId: number, history: ChatMessage[]) => {
    setConversations(prev => {
      const next = new Map(prev);
      next.set(otherUserId, history);
      return next;
    });
  }, []);

  return {
    connected, conversations, unreadCounts, sendMessage, loadThreadHistory,
    groupConversations, groupUnreadCounts, sendGroupMessage, mergeGroupHistory, groupsVersion, connectionEpoch
  };
}
