import React, { useState } from 'react';
import AuthPanel from './components/AuthPanel';
import DriverPanel from './components/DriverPanel';
import ResponderPanel from './components/ResponderPanel';
import CrashSimulator from './components/CrashSimulator';
import DispatchCenter from './components/DispatchCenter';
import LiveTracker from './components/LiveTracker';

const TABS = [
  { id: 'auth', label: '🔐 Auth' },
  { id: 'driver', label: '🚗 Driver' },
  { id: 'responder', label: '🚑 Responder' },
  { id: 'crash', label: '💥 Crash Sim' },
  { id: 'dispatch', label: '📡 Dispatch' },
  { id: 'tracker', label: '📍 Live GPS' },
];

export default function App() {
  const [tab, setTab] = useState('auth');
  const [user, setUser] = useState(null);   // { userId, email, role }
  const [token, setToken] = useState('');    // JWT access token
  const [refreshToken, setRefreshToken] = useState('');
  const [log, setLog] = useState([]);        // Activity log

  const addLog = (msg, type = 'info') => {
    setLog(prev => [{ msg, type, time: new Date().toLocaleTimeString() }, ...prev].slice(0, 50));
  };

  const handleLogin = (authResponse) => {
    setToken(authResponse.accessToken);
    setRefreshToken(authResponse.refreshToken);
    setUser({ userId: authResponse.userId, email: authResponse.email, role: authResponse.role });
    addLog(`Logged in as ${authResponse.email} (${authResponse.role})`, 'success');
  };

  const handleLogout = () => {
    setToken('');
    setRefreshToken('');
    setUser(null);
    setTab('auth');
    addLog('Logged out', 'info');
  };

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100">
      {/* ── Header ── */}
      <header className="bg-gray-900 border-b border-gray-800 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="text-2xl">🚨</span>
          <h1 className="text-lg font-bold text-red-400">IERS Prototype</h1>
          <span className="text-xs text-gray-500">Intelligent Emergency Response System</span>
        </div>
        {user && (
          <div className="flex items-center gap-3 text-sm">
            <span className="px-2 py-1 bg-gray-800 rounded text-xs">
              {user.role === 'DRIVER' ? '🚗' : user.role === 'RESPONDER' ? '🚑' : '👤'}{' '}
              {user.email}
            </span>
            <span className="px-2 py-0.5 rounded text-xs font-medium bg-blue-900 text-blue-300">
              {user.role}
            </span>
          </div>
        )}
      </header>

      {/* ── Tabs ── */}
      <nav className="bg-gray-900 border-b border-gray-800 px-4 flex gap-1 overflow-x-auto">
        {TABS.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`px-4 py-2.5 text-sm whitespace-nowrap border-b-2 transition-colors ${
              tab === t.id
                ? 'border-red-500 text-red-400'
                : 'border-transparent text-gray-500 hover:text-gray-300'
            }`}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {/* ── Content ── */}
      <div className="flex">
        <main className="flex-1 p-4 max-w-4xl">
          {tab === 'auth' && <AuthPanel onLogin={handleLogin} onLogout={handleLogout} user={user} token={token} addLog={addLog} />}
          {tab === 'driver' && <DriverPanel token={token} user={user} addLog={addLog} />}
          {tab === 'responder' && <ResponderPanel token={token} user={user} addLog={addLog} />}
          {tab === 'crash' && <CrashSimulator token={token} addLog={addLog} />}
          {tab === 'dispatch' && <DispatchCenter token={token} user={user} addLog={addLog} />}
          {tab === 'tracker' && <LiveTracker token={token} addLog={addLog} />}
        </main>

        {/* ── Activity Log ── */}
        <aside className="w-80 border-l border-gray-800 p-3 hidden lg:block max-h-[calc(100vh-6rem)] overflow-y-auto">
          <h3 className="text-xs font-bold text-gray-500 uppercase mb-2">Activity Log</h3>
          {log.map((entry, i) => (
            <div key={i} className={`text-xs mb-1.5 ${
              entry.type === 'success' ? 'text-green-400' :
              entry.type === 'error' ? 'text-red-400' : 'text-gray-400'
            }`}>
              <span className="text-gray-600">{entry.time}</span>{' '}{entry.msg}
            </div>
          ))}
          {log.length === 0 && <p className="text-xs text-gray-600">No activity yet</p>}
        </aside>
      </div>
    </div>
  );
}
