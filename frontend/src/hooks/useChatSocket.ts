import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatMessage, User, WsEnvelope } from '../types';

const RECONNECT_DELAY_MS = 2000;
const MAX_RECONNECT_ATTEMPTS = 5;

export function useChatSocket(currentUser: User | null, selectedUserId: number | null = null) {
  const [connected, setConnected] = useState(false);
  const [conversations, setConversations] = useState<Map<number, ChatMessage[]>>(new Map());
  // unreadCounts: otherUserId -> messages received while that thread wasn't the selected one.
  // Session-only - not persisted, resets on reload.
  const [unreadCounts, setUnreadCounts] = useState<Map<number, number>>(new Map());
  const wsRef = useRef<WebSocket | null>(null);
  const attemptsRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const intentionalCloseRef = useRef(false);
  const selectedUserIdRef = useRef(selectedUserId);
  useEffect(() => {
    selectedUserIdRef.current = selectedUserId;
    if (selectedUserId != null) {
      setUnreadCounts(prev => {
        if (!prev.get(selectedUserId)) return prev;
        const next = new Map(prev);
        next.set(selectedUserId, 0);
        return next;
      });
    }
  }, [selectedUserId]);

  const otherPartyOf = useCallback((m: ChatMessage) => {
    if (!currentUser) return m.recipientId;
    return m.senderId === currentUser.id ? m.recipientId : m.senderId;
  }, [currentUser]);

  const upsertMessage = useCallback((otherUserId: number, updater: (list: ChatMessage[]) => ChatMessage[]) => {
    setConversations(prev => {
      const next = new Map(prev);
      next.set(otherUserId, updater(next.get(otherUserId) ?? []));
      return next;
    });
  }, []);

  const applyStatusUpdate = useCallback((id: number, status: ChatMessage['status']) => {
    setConversations(prev => {
      const next = new Map(prev);
      for (const [key, list] of next) {
        const idx = list.findIndex(m => m.id === id);
        if (idx !== -1) {
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
          id: env.id!, senderId: env.senderId!, recipientId: env.recipientId!,
          content: env.content!, status: env.status!, sentAt: env.sentAt!
        };
        const otherUserId = otherPartyOf(incoming);
        // Exclude our own sent messages - their server echo shouldn't bump the badge.
        if (incoming.senderId !== currentUser?.id && otherUserId !== selectedUserIdRef.current) {
          setUnreadCounts(prev => {
            const next = new Map(prev);
            next.set(otherUserId, (next.get(otherUserId) ?? 0) + 1);
            return next;
          });
        }
        upsertMessage(otherUserId, list => {
          // Reconcile our own optimistic send: match by clientMsgId, swap the temp entry
          // for the server-confirmed one instead of appending a duplicate.
          if (env.clientMsgId) {
            const idx = list.findIndex(m => m.clientMsgId === env.clientMsgId);
            if (idx !== -1) {
              const updated = [...list];
              updated[idx] = incoming;
              return updated;
            }
          }
          if (list.some(m => m.id === incoming.id)) return list; // already have it
          return [...list, incoming];
        });
        break;
      }
      case 'STATUS_UPDATE':
        applyStatusUpdate(env.id!, env.status!);
        break;
      case 'BACKLOG_DONE':
        break;
      case 'ERROR':
        console.error('Server rejected message:', env.error);
        break;
    }
  }, [otherPartyOf, upsertMessage, applyStatusUpdate]);

  const connect = useCallback(() => {
    if (!currentUser) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${protocol}://${window.location.host}/ws/chat`);
    wsRef.current = ws;

    ws.onopen = () => {
      attemptsRef.current = 0;
      setConnected(true);
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

  const loadThreadHistory = useCallback((otherUserId: number, history: ChatMessage[]) => {
    setConversations(prev => {
      const next = new Map(prev);
      next.set(otherUserId, history);
      return next;
    });
  }, []);

  return { connected, conversations, unreadCounts, sendMessage, loadThreadHistory };
}
