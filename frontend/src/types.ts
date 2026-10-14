export interface User {
  id: number;
  username: string;
}

export type MessageStatus = 'SENT' | 'DELIVERED' | 'READ';

export interface Group {
  id: number;
  groupName: string;
  createdBy: number | null;
}

export interface GroupDetail extends Group {
  members: User[];
}

export interface ChatMessage {
  id: number;
  senderId: number;
  // null for group messages, which carry groupId instead
  recipientId: number | null;
  groupId?: number | null;
  // a group event line ("alice added bob"), not a chat bubble
  system?: boolean;
  content: string;
  status: MessageStatus;
  sentAt: string;
  clientMsgId?: string;
  pending?: boolean;
}

export interface WsEnvelope {
  type: 'SEND' | 'READ' | 'MESSAGE' | 'STATUS_UPDATE' | 'BACKLOG_DONE' | 'GROUP_ADDED' | 'ERROR';
  id?: number;
  senderId?: number;
  recipientId?: number | null;
  groupId?: number | null;
  system?: boolean;
  content?: string;
  status?: MessageStatus;
  sentAt?: string;
  clientMsgId?: string;
  error?: string;
}
