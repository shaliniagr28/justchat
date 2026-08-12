export interface User {
  id: number;
  username: string;
}

export type MessageStatus = 'SENT' | 'DELIVERED';

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
  type: 'SEND' | 'MESSAGE' | 'STATUS_UPDATE' | 'BACKLOG_DONE' | 'ERROR';
  id?: number;
  senderId?: number;
  recipientId?: number;
  content?: string;
  status?: MessageStatus;
  sentAt?: string;
  clientMsgId?: string;
  error?: string;
}
