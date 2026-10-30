import React, { useState, useRef, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Card, Input, Row } from './AuthPanel';

export default function LiveTracker({ token, addLog }) {
  const [incidentId, setIncidentId] = useState('');
  const [connected, setConnected] = useState(false);
  const [gpsLog, setGpsLog] = useState([]);
  const [mockLat, setMockLat] = useState('31.5300');
  const [mockLng, setMockLng] = useState('74.3600');
  const [mockSpeed, setMockSpeed] = useState('60');
  const clientRef = useRef(null);
  const subRef = useRef(null);

  const connect = useCallback(() => {
    if (!incidentId.trim()) { addLog('Enter an incident ID', 'error'); return; }
    if (connected) { addLog('Already connected', 'info'); return; }

    const wsUrl = `http://localhost:8080/ws/incident`;
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      debug: (str) => console.log('[STOMP]', str),
      onConnect: () => {
        setConnected(true);
        addLog(`WebSocket connected — subscribing to incident ${incidentId}`, 'success');

        const sub = client.subscribe(`/topic/gps/${incidentId}`, (message) => {
          try {
            const data = JSON.parse(message.body);
            setGpsLog(prev => [
              { ...data, receivedAt: new Date().toLocaleTimeString() },
              ...prev
            ].slice(0, 100));
            addLog(`GPS: [${data.latitude?.toFixed(4)}, ${data.longitude?.toFixed(4)}] speed=${data.speed}`, 'info');
          } catch (e) {
            addLog(`GPS parse error: ${e.message}`, 'error');
          }
        });
        subRef.current = sub;
      },
      onStompError: (frame) => {
        addLog(`STOMP error: ${frame.headers?.message || 'Unknown'}`, 'error');
      },
      onDisconnect: () => {
        setConnected(false);
        addLog('WebSocket disconnected', 'info');
      }
    });

    client.activate();
    clientRef.current = client;
  }, [incidentId, connected, addLog]);

  const disconnect = () => {
    if (subRef.current) subRef.current.unsubscribe();
    if (clientRef.current) clientRef.current.deactivate();
    setConnected(false);
    addLog('WebSocket disconnected', 'info');
  };

  const sendMockGps = () => {
    if (!clientRef.current || !connected) {
      addLog('Connect first before sending GPS', 'error');
      return;
    }
    const payload = {
      incidentId,
      responderId: 'mock-responder',
      latitude: parseFloat(mockLat),
      longitude: parseFloat(mockLng),
      heading: 45.0,
      speed: parseFloat(mockSpeed),
      estimatedEta: `${Math.ceil(Math.random() * 10)} min`,
    };
    clientRef.current.publish({
      destination: `/app/gps/${incidentId}`,
      body: JSON.stringify(payload),
    });
    addLog(`Sent GPS: [${mockLat}, ${mockLng}]`, 'success');

    // Simulate movement
    setMockLat(prev => (parseFloat(prev) + 0.001).toFixed(6));
    setMockLng(prev => (parseFloat(prev) + 0.0005).toFixed(6));
  };

  const simulateMovement = () => {
    let count = 0;
    const interval = setInterval(() => {
      if (count >= 10 || !clientRef.current || !connected) {
        clearInterval(interval);
        addLog('Movement simulation complete', 'info');
        return;
      }
      sendMockGps();
      count++;
    }, 2000);
    addLog('Simulating 10 GPS updates (every 2s)...', 'info');
  };

  return (
    <div className="space-y-4">
      {/* ── Connection ── */}
      <Card title="📍 Live GPS Tracker">
        <div className="flex gap-2 items-end">
          <div className="flex-1">
            <Input label="Incident ID" value={incidentId} onChange={setIncidentId} placeholder="Paste incident UUID" />
          </div>
          {!connected ? (
            <button onClick={connect} className="btn-green h-10">🔌 Connect</button>
          ) : (
            <button onClick={disconnect} className="btn-red h-10">⏹ Disconnect</button>
          )}
        </div>
        <div className="mt-2 flex items-center gap-2">
          <div className={`w-2 h-2 rounded-full ${connected ? 'bg-green-500 animate-pulse' : 'bg-red-500'}`} />
          <span className="text-xs text-gray-400">{connected ? 'Connected — Listening for GPS updates' : 'Disconnected'}</span>
        </div>
      </Card>

      {/* ── Send Mock GPS ── */}
      <Card title="🚑 Send Mock GPS (simulate responder movement)">
        <div className="grid grid-cols-3 gap-2">
          <Input label="Latitude" value={mockLat} onChange={setMockLat} />
          <Input label="Longitude" value={mockLng} onChange={setMockLng} />
          <Input label="Speed (km/h)" value={mockSpeed} onChange={setMockSpeed} />
        </div>
        <div className="flex gap-2 mt-3">
          <button onClick={sendMockGps} disabled={!connected} className="btn-primary">📤 Send Single Update</button>
          <button onClick={simulateMovement} disabled={!connected} className="btn-gray">🔄 Simulate 10 Updates</button>
        </div>
      </Card>

      {/* ── GPS Feed ── */}
      {gpsLog.length > 0 && (
        <Card title={`📡 GPS Feed (${gpsLog.length} updates)`}>
          <div className="max-h-80 overflow-y-auto space-y-1">
            {gpsLog.map((g, i) => (
              <div key={i} className="flex items-center gap-3 text-xs bg-gray-800 rounded px-3 py-1.5">
                <span className="text-gray-500 w-20">{g.receivedAt}</span>
                <span className="text-blue-400 font-mono w-40">
                  [{g.latitude?.toFixed(6)}, {g.longitude?.toFixed(6)}]
                </span>
                <span className="text-gray-400">🚑 {g.speed} km/h</span>
                <span className="text-green-400">ETA: {g.estimatedEta}</span>
              </div>
            ))}
          </div>
          <button onClick={() => setGpsLog([])} className="text-xs text-gray-500 mt-2 hover:text-gray-400">Clear feed</button>
        </Card>
      )}
    </div>
  );
}
