import React, { useState, useEffect } from 'react';
import * as api from '../api';
import { Card, Row } from './AuthPanel';

const STATUS_COLORS = {
  DISPATCHING: 'text-yellow-400', DISPATCHED: 'text-orange-400',
  ACCEPTED: 'text-blue-400', ARRIVED: 'text-cyan-400',
  EN_ROUTE_HOSPITAL: 'text-purple-400', RESOLVED: 'text-green-400',
  CANCELLED_BY_USER: 'text-gray-500',
};

export default function DispatchCenter({ token, user, addLog }) {
  const [incidents, setIncidents] = useState([]);
  const [selected, setSelected] = useState(null);
  const [lookupId, setLookupId] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(false);

  if (!token) return <Card title="⚠️ Login Required"><p className="text-sm text-gray-400">Please login first.</p></Card>;

  const loadActive = async () => {
    try {
      const data = await api.getActiveIncidents(token);
      setIncidents(data);
      addLog(`Loaded ${data.length} active incidents`, 'info');
    } catch (err) { addLog(`Load failed: ${err.message}`, 'error'); }
  };

  const lookup = async () => {
    if (!lookupId.trim()) return;
    try {
      const data = await api.getIncident(lookupId, token);
      setSelected(data);
      addLog(`Incident loaded: ${data.status}`, 'success');
    } catch (err) { addLog(`Lookup failed: ${err.message}`, 'error'); }
  };

  const assist = async (id) => {
    try {
      const res = await api.bystanderAssist(id, token);
      addLog(`Bystander assist: ${res.message}`, 'success');
    } catch (err) { addLog(`Assist failed: ${err.message}`, 'error'); }
  };

  useEffect(() => {
    if (autoRefresh) {
      const timer = setInterval(loadActive, 3000);
      return () => clearInterval(timer);
    }
  }, [autoRefresh]);

  return (
    <div className="space-y-4">
      {/* ── Controls ── */}
      <Card title="📡 Dispatch Center">
        <div className="flex gap-2 items-end">
          <button onClick={loadActive} className="btn-primary">🔄 Load Active Incidents</button>
          <button onClick={() => setAutoRefresh(!autoRefresh)}
            className={autoRefresh ? 'btn-green' : 'btn-gray'}>
            {autoRefresh ? '⏸ Auto-Refresh ON' : '▶ Auto-Refresh OFF'}
          </button>
        </div>
      </Card>

      {/* ── Active Incidents ── */}
      {incidents.length > 0 && (
        <Card title={`🚨 Active Incidents (${incidents.length})`}>
          {incidents.map(inc => (
            <div key={inc.id} className="bg-gray-800 rounded p-3 mb-2 cursor-pointer hover:bg-gray-750 transition-colors"
              onClick={() => { setSelected(inc); setLookupId(inc.id); }}>
              <div className="flex justify-between items-center">
                <div>
                  <span className={`font-semibold ${STATUS_COLORS[inc.status] || 'text-white'}`}>
                    {inc.status}
                  </span>
                  <span className="text-gray-400 ml-2 text-sm">
                    {inc.driverName || 'Unknown'} · Priority {inc.priorityScore}
                  </span>
                </div>
                <div className="flex gap-2">
                  <button onClick={(e) => { e.stopPropagation(); assist(inc.id); }}
                    className="text-xs px-2 py-1 bg-yellow-900 rounded hover:bg-yellow-800">
                    🤝 Bystander
                  </button>
                  <span className="text-xs text-gray-500 font-mono">{inc.id.substring(0, 8)}...</span>
                </div>
              </div>
              <div className="text-xs text-gray-500 mt-1">
                GPS: [{inc.gpsLat?.toFixed(4)}, {inc.gpsLng?.toFixed(4)}]
                {inc.bystanderAssisting && <span className="text-yellow-400 ml-2">🤝 Bystander on scene</span>}
              </div>
            </div>
          ))}
        </Card>
      )}

      {/* ── Lookup ── */}
      <Card title="🔍 Lookup Incident">
        <div className="flex gap-2 items-end">
          <div className="flex-1">
            <label className="label">Incident ID</label>
            <input value={lookupId} onChange={e => setLookupId(e.target.value)}
              className="input" placeholder="Paste UUID" />
          </div>
          <button onClick={lookup} className="btn-primary h-10">Lookup</button>
        </div>
      </Card>

      {/* ── Selected Incident Detail ── */}
      {selected && (
        <Card title="📋 Incident Detail">
          <div className="grid grid-cols-2 gap-y-2 text-sm">
            <Row label="ID" value={selected.id} />
            <Row label="Crash Event" value={selected.crashEventId} />
            <Row label="Driver" value={selected.driverName || 'Unknown'} />
            <Row label="Priority" value={`${selected.priorityScore}`} />
            <Row label="Status" value={selected.status} />
            <Row label="GPS" value={`[${selected.gpsLat?.toFixed(4)}, ${selected.gpsLng?.toFixed(4)}]`} />
            <Row label="Blood Type" value={selected.bloodType || 'N/A'} />
            <Row label="Allergies" value={selected.allergies || 'N/A'} />
            <Row label="Responder" value={selected.assignedResponderId || 'None'} />
            <Row label="Bystander" value={selected.bystanderAssisting ? 'Yes' : 'No'} />
            <Row label="Created" value={selected.createdAt || '—'} />
            <Row label="Accepted" value={selected.acceptedAt || '—'} />
          </div>
        </Card>
      )}
    </div>
  );
}
