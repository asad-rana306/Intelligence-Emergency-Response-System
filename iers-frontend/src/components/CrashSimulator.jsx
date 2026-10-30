import React, { useState } from 'react';
import * as api from '../api';
import { Card, Input, Row } from './AuthPanel';

const SCENARIOS = [
  { name: '🟢 Speed Bump (False Alarm)', gForce: 2.1, rolloverAngle: 1.5, speed: 25, gpsLat: 31.5204, gpsLng: 74.3587, sensorData: { accelerometerX: -0.3, accelerometerY: 0.1, accelerometerZ: 2.1, gyroscopeRoll: 1.0, gyroscopePitch: -2.0 } },
  { name: '🟢 Phone Drop (False Alarm)', gForce: 7.0, rolloverAngle: 0.5, speed: 0, gpsLat: 31.5204, gpsLng: 74.3587, sensorData: { accelerometerX: 1.5, accelerometerY: -1.2, accelerometerZ: 7.0, gyroscopeRoll: 0.5, gyroscopePitch: 0.3 } },
  { name: '🟡 Fender Bender (Low)', gForce: 3.5, rolloverAngle: 2.0, speed: 20, gpsLat: 31.5150, gpsLng: 74.3500, sensorData: { accelerometerX: -3.0, accelerometerY: 0.5, accelerometerZ: 1.2, gyroscopeRoll: 2.0, gyroscopePitch: -1.5 } },
  { name: '🟠 Urban T-Bone (Moderate)', gForce: 7.0, rolloverAngle: 15.0, speed: 50, gpsLat: 31.5100, gpsLng: 74.3400, sensorData: { accelerometerX: -1.0, accelerometerY: -6.5, accelerometerZ: 1.5, gyroscopeRoll: 12.0, gyroscopePitch: -3.0 } },
  { name: '🔴 Highway Crash (High)', gForce: 10.0, rolloverAngle: 8.0, speed: 110, gpsLat: 31.4800, gpsLng: 74.3200, sensorData: { accelerometerX: -9.5, accelerometerY: 2.0, accelerometerZ: 1.5, gyroscopeRoll: 5.0, gyroscopePitch: -8.0 } },
  { name: '🔴 Rollover (Critical)', gForce: 12.0, rolloverAngle: 120.0, speed: 130, gpsLat: 31.4500, gpsLng: 74.3000, sensorData: { accelerometerX: -5.0, accelerometerY: 7.0, accelerometerZ: -4.0, gyroscopeRoll: 95.0, gyroscopePitch: -20.0 } },
  { name: '🔴 Head-On (Critical)', gForce: 18.0, rolloverAngle: 5.0, speed: 100, gpsLat: 31.4200, gpsLng: 74.2800, sensorData: { accelerometerX: -17.0, accelerometerY: 1.0, accelerometerZ: 2.0, gyroscopeRoll: 3.0, gyroscopePitch: -10.0 } },
];

export default function CrashSimulator({ token, addLog }) {
  const [deviceId, setDeviceId] = useState('DEV-001');
  const [crashEventId, setCrashEventId] = useState('');
  const [lastResult, setLastResult] = useState(null);
  const [selectedScenario, setSelectedScenario] = useState(null);
  const [customForm, setCustomForm] = useState({
    gForce: '8.5', rolloverAngle: '45', speed: '120', gpsLat: '31.5204', gpsLng: '74.3587'
  });

  const triggerCrash = async (payload) => {
    try {
      const fullPayload = { ...payload, timestamp: Date.now() };
      addLog(`🚨 Sending crash: gForce=${payload.gForce}g, speed=${payload.speed}km/h`, 'info');
      const res = await api.simulateCrash(deviceId, fullPayload);
      setLastResult(res);
      if (res.crashEventId) setCrashEventId(res.crashEventId);
      addLog(`Crash response: ${res.status} — ${res.message}`, res.status === 'DUPLICATE' ? 'info' : 'success');
    } catch (err) {
      addLog(`Crash send failed: ${JSON.stringify(err)}`, 'error');
    }
  };

  const handleCancel = async () => {
    if (!crashEventId) return addLog('No crash event to cancel', 'error');
    try {
      const res = await api.cancelCrash(crashEventId, token);
      setLastResult(res);
      addLog(`Cancel: ${res.status} — ${res.message}`, 'success');
    } catch (err) { addLog(`Cancel failed: ${err.message}`, 'error'); }
  };

  const handleLateCancel = async () => {
    if (!crashEventId) return addLog('No crash event to late-cancel', 'error');
    try {
      const res = await api.lateCancelCrash(crashEventId, token);
      setLastResult(res);
      addLog(`Late cancel: ${res.status} — ${res.message}`, 'success');
    } catch (err) { addLog(`Late cancel failed: ${err.message}`, 'error'); }
  };

  const testML = async (payload) => {
    try {
      const res = await api.testMlPredict({
        gForce: payload.gForce, rolloverAngle: payload.rolloverAngle,
        speed: payload.speed, sensorData: payload.sensorData
      });
      addLog(`ML result: priority=${res.priorityScore} (${res.severity}), severe=${res.severe}`, 'success');
    } catch (err) { addLog(`ML test failed: ${err.message}`, 'error'); }
  };

  return (
    <div className="space-y-4">
      {/* ── Device Config ── */}
      <Card title="⚙️ Device Configuration">
        <Input label="Device ID (must match paired device)" value={deviceId} onChange={setDeviceId} />
      </Card>

      {/* ── Preset Scenarios ── */}
      <Card title="💥 Crash Scenarios">
        <p className="text-xs text-gray-500 mb-3">Select a scenario to simulate a crash from the car's embedded device:</p>
        <div className="space-y-2">
          {SCENARIOS.map((s, i) => (
            <div key={i} className="flex items-center gap-2">
              <button onClick={() => { setSelectedScenario(s); triggerCrash(s); }}
                className="flex-1 text-left px-3 py-2 bg-gray-800 rounded hover:bg-gray-700 text-sm transition-colors">
                {s.name}
                <span className="text-xs text-gray-500 ml-2">
                  {s.gForce}g · {s.speed}km/h · {s.rolloverAngle}°
                </span>
              </button>
              <button onClick={() => testML(s)} className="text-xs px-2 py-1 bg-purple-900 rounded hover:bg-purple-800">
                🧠 ML
              </button>
            </div>
          ))}
        </div>
      </Card>

      {/* ── Custom Payload ── */}
      <Card title="🛠️ Custom Crash Payload">
        <div className="grid grid-cols-5 gap-2">
          {Object.entries(customForm).map(([k, v]) => (
            <Input key={k} label={k} value={v} onChange={val => setCustomForm(f => ({ ...f, [k]: val }))} />
          ))}
        </div>
        <button onClick={() => triggerCrash({
          gForce: parseFloat(customForm.gForce),
          rolloverAngle: parseFloat(customForm.rolloverAngle),
          speed: parseFloat(customForm.speed),
          gpsLat: parseFloat(customForm.gpsLat),
          gpsLng: parseFloat(customForm.gpsLng),
          sensorData: {}
        })} className="btn-red mt-3">🚨 Send Custom Crash</button>
      </Card>

      {/* ── Cancel Controls ── */}
      <Card title="🛑 Cancel Controls">
        <Input label="Crash Event ID" value={crashEventId} onChange={setCrashEventId}
          placeholder="Auto-filled from last crash, or paste manually" />
        <div className="flex gap-2 mt-3">
          <button onClick={handleCancel} className="btn-green flex-1">
            ✅ Cancel (within 10s window)
          </button>
          <button onClick={handleLateCancel} className="btn-red flex-1">
            ⚠️ Late Cancel (after 10s)
          </button>
        </div>
      </Card>

      {/* ── Last Result ── */}
      {lastResult && (
        <Card title="📋 Last Response">
          <pre className="text-xs bg-gray-800 p-3 rounded overflow-x-auto">
            {JSON.stringify(lastResult, null, 2)}
          </pre>
        </Card>
      )}
    </div>
  );
}
