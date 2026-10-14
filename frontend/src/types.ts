export interface User {
  id: number;
  username: string;
}

export type MessageStatus = 'SENT' | 'DELIVERED' | 'READ';

export interface ChatMessage {
  id: number;
  senderId: number;
  recipientId: number;
  content: string;
  status: MessageStatus;
  sentAt: string;
  clientMsgId?: string;
  pending?: boolean;
}

export interface WsEnvelope {
  type: 'SEND' | 'READ' | 'MESSAGE' | 'STATUS_UPDATE' | 'BACKLOG_DONE' | 'ERROR';
  id?: number;
  senderId?: number;
  recipientId?: number;
  content?: string;
  status?: MessageStatus;
  sentAt?: string;
  clientMsgId?: string;
  error?: string;
}
