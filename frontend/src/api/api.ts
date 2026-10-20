import type { User, ChatMessage, Group, GroupDetail } from '../types';

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Request failed: ${res.status}`);
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') return undefined as T;
  return res.json();
}

export const api = {
  register: (username: string, password: string) =>
    request<User>('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) }),

  login: (username: string, password: string) =>
    request<User>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),

  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),

  me: () => request<User>('/api/auth/me'),

  listUsers: (q?: string) => request<User[]>(`/api/users${q ? `?q=${encodeURIComponent(q)}` : ''}`),

  thread: (otherUserId: number) => request<ChatMessage[]>(`/api/messages/thread/${otherUserId}`),

  listGroups: () => request<Group[]>('/api/groups'),

  createGroup: (groupName: string) =>
    request<GroupDetail>('/api/groups', { method: 'POST', body: JSON.stringify({ groupName }) }),

  groupDetail: (groupId: number) => request<GroupDetail>(`/api/groups/${groupId}`),

  addGroupMember: (groupId: number, username: string) =>
    request<GroupDetail>(`/api/groups/${groupId}/members`, { method: 'POST', body: JSON.stringify({ username }) }),

  groupMessages: (groupId: number) => request<ChatMessage[]>(`/api/groups/${groupId}/messages`)
};
