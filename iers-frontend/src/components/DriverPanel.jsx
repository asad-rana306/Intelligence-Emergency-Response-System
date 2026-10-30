import React, { useState, useEffect } from 'react';
import * as api from '../api';
import { Card, Input, Row } from './AuthPanel';

export default function DriverPanel({ token, user, addLog }) {
  if (!token) return <Card title="⚠️ Login Required"><p className="text-sm text-gray-400">Please login first.</p></Card>;

  return (
    <div className="space-y-4">
      <MedicalProfile token={token} addLog={addLog} />
      <EmergencyContacts token={token} addLog={addLog} />
      <DevicePairing token={token} user={user} addLog={addLog} />
    </div>
  );
}

function MedicalProfile({ token, addLog }) {
  const [profile, setProfile] = useState(null);
  const [form, setForm] = useState({
    bloodType: 'O+', allergies: '', medications: '', chronicConditions: '', emergencyNotes: ''
  });
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const load = async () => {
    try {
      const data = await api.getMyMedicalProfile(token);
      setProfile(data);
      setForm(data);
      addLog('Medical profile loaded', 'success');
    } catch { setProfile(null); }
  };

  useEffect(() => { load(); }, [token]);

  const save = async () => {
    try {
      if (profile) {
        await api.updateMedicalProfile(form, token);
        addLog('Medical profile updated', 'success');
      } else {
        await api.createMedicalProfile(form, token);
        addLog('Medical profile created', 'success');
      }
      load();
    } catch (err) { addLog(`Profile error: ${err.message}`, 'error'); }
  };

  return (
    <Card title="🩺 Medical Profile">
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="label">Blood Type</label>
          <select value={form.bloodType} onChange={e => set('bloodType', e.target.value)} className="input">
            {['A+','A-','B+','B-','AB+','AB-','O+','O-'].map(bt => <option key={bt}>{bt}</option>)}
          </select>
        </div>
        <Input label="Allergies" value={form.allergies} onChange={v => set('allergies', v)} placeholder="Penicillin, Shellfish" />
        <Input label="Medications" value={form.medications} onChange={v => set('medications', v)} placeholder="Metformin 500mg" />
        <Input label="Chronic Conditions" value={form.chronicConditions} onChange={v => set('chronicConditions', v)} />
      </div>
      <Input label="Emergency Notes" value={form.emergencyNotes} onChange={v => set('emergencyNotes', v)} placeholder="Carries insulin pen" />
      <button onClick={save} className="btn-primary mt-3">
        {profile ? 'Update Profile' : 'Create Profile'}
      </button>
      {profile && <span className="ml-3 text-xs text-green-400">✓ Profile saved</span>}
    </Card>
  );
}

function EmergencyContacts({ token, addLog }) {
  const [contacts, setContacts] = useState([]);
  const [form, setForm] = useState({ contactName: '', phone: '', relationship: '' });
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const load = async () => {
    try {
      const data = await api.getMyEmergencyContacts(token);
      setContacts(data);
    } catch {}
  };

  useEffect(() => { load(); }, [token]);

  const add = async () => {
    try {
      await api.addEmergencyContact(form, token);
      addLog(`Contact added: ${form.contactName}`, 'success');
      setForm({ contactName: '', phone: '', relationship: '' });
      load();
    } catch (err) { addLog(`Contact error: ${err.message}`, 'error'); }
  };

  const remove = async (id) => {
    try {
      await api.deleteEmergencyContact(id, token);
      addLog('Contact removed', 'success');
      load();
    } catch (err) { addLog(`Delete error: ${err.message}`, 'error'); }
  };

  return (
    <Card title="📞 Emergency Contacts">
      {contacts.map(c => (
        <div key={c.id} className="flex items-center justify-between bg-gray-800 rounded p-2 mb-2 text-sm">
          <span>{c.contactName} ({c.relationship}) — {c.phone}</span>
          <button onClick={() => remove(c.id)} className="text-red-400 text-xs hover:text-red-300">✕</button>
        </div>
      ))}
      <div className="grid grid-cols-3 gap-2 mt-3">
        <Input label="Name" value={form.contactName} onChange={v => set('contactName', v)} />
        <Input label="Phone" value={form.phone} onChange={v => set('phone', v)} placeholder="+1..." />
        <Input label="Relationship" value={form.relationship} onChange={v => set('relationship', v)} placeholder="Spouse" />
      </div>
      <button onClick={add} className="btn-gray mt-2">+ Add Contact</button>
    </Card>
  );
}

function DevicePairing({ token, user, addLog }) {
  const [deviceId, setDeviceId] = useState('DEV-001');
  const [status, setStatus] = useState(null);

  const pair = async () => {
    try {
      const res = await api.pairDevice({ deviceId, driverName: user?.email || 'Driver', driverPhone: '+14155551234' }, token);
      setStatus(res);
      addLog(`Device ${deviceId} paired`, 'success');
    } catch (err) { addLog(`Pairing error: ${err.message}`, 'error'); }
  };

  const check = async () => {
    try {
      const res = await api.getDeviceStatus(deviceId, token);
      setStatus(res);
      addLog(`Device status: ${res.paired ? 'paired' : 'unpaired'}`, 'info');
    } catch (err) { addLog(`Status error: ${err.message}`, 'error'); }
  };

  return (
    <Card title="📱 Device Pairing">
      <div className="flex gap-2 items-end">
        <Input label="Device ID" value={deviceId} onChange={setDeviceId} placeholder="DEV-001" />
        <button onClick={pair} className="btn-primary h-10">Pair</button>
        <button onClick={check} className="btn-gray h-10">Check</button>
      </div>
      {status && (
        <div className="mt-3 text-sm space-y-1">
          <Row label="Paired" value={status.paired ? '✅ Yes' : '❌ No'} />
          {status.userId && <Row label="User ID" value={status.userId} />}
        </div>
      )}
    </Card>
  );
}
