import React, { useState } from 'react';
import * as api from '../api';

export default function AuthPanel({ onLogin, onLogout, user, token, addLog }) {
  const [mode, setMode] = useState('login');
  const [form, setForm] = useState({
    email: '', password: '', fullName: '', phone: '', role: 'DRIVER', vehicleId: ''
  });
  const [loading, setLoading] = useState(false);

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    try {
      const res = mode === 'login'
        ? await api.login({ email: form.email, password: form.password })
        : await api.register(form);
      onLogin(res);
      addLog(`${mode === 'login' ? 'Login' : 'Registration'} successful`, 'success');
    } catch (err) {
      addLog(`Auth failed: ${err.message || JSON.stringify(err)}`, 'error');
    }
    setLoading(false);
  };

  if (user) {
    return (
      <div className="space-y-4">
        <Card title="Authenticated">
          <div className="space-y-2 text-sm">
            <Row label="User ID" value={user.userId} />
            <Row label="Email" value={user.email} />
            <Row label="Role" value={user.role} />
            <Row label="Token" value={token.substring(0, 40) + '...'} />
          </div>
          <button onClick={async () => {
            try { await api.logout(token); } catch {}
            onLogout();
          }} className="mt-4 btn-red">Logout</button>
        </Card>
      </div>
    );
  }

  return (
    <div className="max-w-md">
      <div className="flex gap-2 mb-4">
        <button onClick={() => setMode('login')} className={mode === 'login' ? 'btn-primary' : 'btn-gray'}>Login</button>
        <button onClick={() => setMode('register')} className={mode === 'register' ? 'btn-primary' : 'btn-gray'}>Register</button>
      </div>

      <Card title={mode === 'login' ? 'Login' : 'Register New Account'}>
        <form onSubmit={handleSubmit} className="space-y-3">
          <Input label="Email" type="email" value={form.email} onChange={v => set('email', v)} />
          <Input label="Password" type="password" value={form.password} onChange={v => set('password', v)} />

          {mode === 'register' && <>
            <Input label="Full Name" value={form.fullName} onChange={v => set('fullName', v)} />
            <Input label="Phone" value={form.phone} onChange={v => set('phone', v)} placeholder="+14155551234" />
            <div>
              <label className="label">Role</label>
              <select value={form.role} onChange={e => set('role', e.target.value)} className="input">
                <option value="DRIVER">🚗 Driver</option>
                <option value="RESPONDER">🚑 Responder</option>
                <option value="ADMIN">👤 Admin</option>
              </select>
            </div>
            {form.role === 'RESPONDER' && (
              <Input label="Vehicle ID" value={form.vehicleId} onChange={v => set('vehicleId', v)} placeholder="AMB-001" />
            )}
          </>}

          <button type="submit" disabled={loading} className="btn-primary w-full">
            {loading ? 'Processing...' : mode === 'login' ? 'Login' : 'Register'}
          </button>
        </form>
      </Card>
    </div>
  );
}

// ── Shared tiny components ──
export function Card({ title, children }) {
  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mb-4">
      {title && <h3 className="text-sm font-semibold text-gray-300 mb-3">{title}</h3>}
      {children}
    </div>
  );
}

export function Input({ label, value, onChange, type = 'text', placeholder = '' }) {
  return (
    <div>
      <label className="label">{label}</label>
      <input type={type} value={value} onChange={e => onChange(e.target.value)}
        placeholder={placeholder} className="input" />
    </div>
  );
}

export function Row({ label, value }) {
  return (
    <div className="flex justify-between">
      <span className="text-gray-500">{label}</span>
      <span className="font-mono text-xs">{value}</span>
    </div>
  );
}
