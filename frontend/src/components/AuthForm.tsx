import { useState } from 'react';
import { api } from '../api/api';
import type { User } from '../types';

export default function AuthForm({ onAuthed }: { onAuthed: (user: User) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const user = mode === 'login' ? await api.login(username, password) : await api.register(username, password);
      onAuthed(user);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 320, margin: '80px auto', fontFamily: 'sans-serif' }}>
      <h2>{mode === 'login' ? 'Log in' : 'Create account'}</h2>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <input placeholder="Username" value={username} onChange={e => setUsername(e.target.value)} required />
        <input placeholder="Password" type="password" value={password} onChange={e => setPassword(e.target.value)} required />
        {error && <div style={{ color: 'crimson', fontSize: 13 }}>{error}</div>}
        <button type="submit" disabled={busy}>{mode === 'login' ? 'Log in' : 'Register'}</button>
      </form>
      <button style={{ marginTop: 12, background: 'none', border: 'none', color: '#3366cc', cursor: 'pointer' }}
              onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
        {mode === 'login' ? "Need an account? Register" : 'Already have an account? Log in'}
      </button>
    </div>
  );
}
