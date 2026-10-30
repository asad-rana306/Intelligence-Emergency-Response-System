import React, { useState } from 'react';
import * as api from '../api';
import { Card, Input, Row } from './AuthPanel';

export default function ResponderPanel({ token, user, addLog }) {
  const [dutyStatus, setDutyStatus] = useState('OFF_DUTY');
  const [lat, setLat] = useState('31.5204');
  const [lng, setLng] = useState('74.3587');
  const [profile, setProfile] = useState(null);

  if (!token) return <Card title="⚠️ Login Required"><p className="text-sm text-gray-400">Please login as a RESPONDER.</p></Card>;

  const toggleDuty = async (status) => {
    try {
      const res = await api.updateDutyStatus(status, token);
      setDutyStatus(status);
      setProfile(res);
      addLog(`Duty status → ${status}`, 'success');
    } catch (err) { addLog(`Status error: ${err.message}`, 'error'); }
  };

  const updateLoc = async () => {
    try {
      const res = await api.updateLocation(parseFloat(lat), parseFloat(lng), token);
      setProfile(res);
      addLog(`Location updated: [${lat}, ${lng}]`, 'success');
    } catch (err) { addLog(`Location error: ${err.message}`, 'error'); }
  };

  const useCurrentLocation = () => {
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          setLat(pos.coords.latitude.toFixed(6));
          setLng(pos.coords.longitude.toFixed(6));
          addLog('GPS location acquired', 'info');
        },
        () => addLog('Geolocation denied', 'error')
      );
    }
  };

  return (
    <div className="space-y-4">
      {/* ── Duty Status ── */}
      <Card title="🚑 Duty Status">
        <div className="flex gap-2">
          {['ON_DUTY', 'OFF_DUTY', 'ON_MISSION'].map(s => (
            <button key={s} onClick={() => toggleDuty(s)}
              className={`px-4 py-2 rounded text-sm font-medium transition-colors ${
                dutyStatus === s
                  ? s === 'ON_DUTY' ? 'bg-green-700 text-white'
                    : s === 'ON_MISSION' ? 'bg-yellow-700 text-white'
                    : 'bg-gray-600 text-white'
                  : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
              }`}>
              {s === 'ON_DUTY' ? '🟢' : s === 'ON_MISSION' ? '🟡' : '⚫'} {s.replace('_', ' ')}
            </button>
          ))}
        </div>
        {profile && (
          <div className="mt-3 text-sm space-y-1">
            <Row label="Vehicle" value={profile.vehicleId || 'N/A'} />
            <Row label="Position" value={`[${profile.currentLat}, ${profile.currentLng}]`} />
          </div>
        )}
      </Card>

      {/* ── Location ── */}
      <Card title="📍 Update Location">
        <div className="flex gap-2 items-end">
          <Input label="Latitude" value={lat} onChange={setLat} />
          <Input label="Longitude" value={lng} onChange={setLng} />
          <button onClick={updateLoc} className="btn-primary h-10">Update</button>
          <button onClick={useCurrentLocation} className="btn-gray h-10 text-xs">📍 Use GPS</button>
        </div>
        <div className="mt-3 flex gap-2">
          <span className="text-xs text-gray-500">Quick presets:</span>
          {[
            { name: 'Lahore', lat: '31.5204', lng: '74.3587' },
            { name: 'Karachi', lat: '24.8607', lng: '67.0011' },
            { name: 'Islamabad', lat: '33.6844', lng: '73.0479' },
            { name: 'SF', lat: '37.7749', lng: '-122.4194' },
          ].map(p => (
            <button key={p.name} onClick={() => { setLat(p.lat); setLng(p.lng); }}
              className="text-xs px-2 py-1 bg-gray-800 rounded hover:bg-gray-700">{p.name}</button>
          ))}
        </div>
      </Card>

      {/* ── Dispatch Quick Actions ── */}
      <DispatchQuickActions token={token} addLog={addLog} />
    </div>
  );
}

function DispatchQuickActions({ token, addLog }) {
  const [incidentId, setIncidentId] = useState('');

  const action = async (fn, name) => {
    if (!incidentId.trim()) return addLog('Enter an incident ID first', 'error');
    try {
      const res = await fn(incidentId, token);
      addLog(`${name}: ${res.status} — ${res.message}`, 'success');
    } catch (err) { addLog(`${name} failed: ${err.message}`, 'error'); }
  };

  return (
    <Card title="📡 Dispatch Actions">
      <Input label="Incident ID" value={incidentId} onChange={setIncidentId} placeholder="paste incident UUID" />
      <div className="grid grid-cols-2 gap-2 mt-3">
        <button onClick={() => action(api.acceptDispatch, 'ACCEPT')} className="btn-green">✅ Accept Dispatch</button>
        <button onClick={() => action(api.rejectDispatch, 'REJECT')} className="btn-red">❌ Reject Dispatch</button>
        <button onClick={() => action((id, t) => api.updateIncidentStatus(id, 'ARRIVED', null, t), 'ARRIVED')} className="btn-gray">📍 Arrived</button>
        <button onClick={() => action((id, t) => api.updateIncidentStatus(id, 'EN_ROUTE_HOSPITAL', 'HOSP-1', t), 'EN_ROUTE')} className="btn-gray">🏥 En Route Hospital</button>
        <button onClick={() => action((id, t) => api.updateIncidentStatus(id, 'RESOLVED', null, t), 'RESOLVED')} className="btn-primary col-span-2">✔️ Resolved — Close Incident</button>
      </div>
    </Card>
  );
}
