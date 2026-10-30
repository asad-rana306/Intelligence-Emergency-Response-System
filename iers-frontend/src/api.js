// ═══════════════════════════════════════════════════
//  IERS API Client — all endpoints in one file
//  Vite proxy handles routing to localhost:8080
// ═══════════════════════════════════════════════════

const DEVICE_API_KEY = 'your-device-api-key'; // Must match .env DEVICE_API_KEY

async function request(method, path, body, token, isDevice = false) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (isDevice) {
    headers['X-Device-Api-Key'] = DEVICE_API_KEY;
    headers['X-Device-Id'] = body?._deviceId || 'DEV-001';
    delete body?._deviceId;
  }
  const res = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw { status: res.status, ...(data || { message: res.statusText }) };
  return data;
}

// ── Auth ──
export const register = (data) => request('POST', '/auth/register', data);
export const login = (data) => request('POST', '/auth/login', data);
export const logout = (token) => request('POST', '/auth/logout', null, token);

// ── User ──
export const getMe = (token) => request('GET', '/api/users/me', null, token);
export const updateMe = (data, token) => request('PUT', '/api/users/me', data, token);

// ── Medical Profile ──
export const createMedicalProfile = (data, token) => request('POST', '/api/medical-profiles', data, token);
export const getMyMedicalProfile = (token) => request('GET', '/api/medical-profiles/me', null, token);
export const updateMedicalProfile = (data, token) => request('PUT', '/api/medical-profiles/me', data, token);

// ── Emergency Contacts ──
export const addEmergencyContact = (data, token) => request('POST', '/api/emergency-contacts', data, token);
export const getMyEmergencyContacts = (token) => request('GET', '/api/emergency-contacts/me', null, token);
export const deleteEmergencyContact = (id, token) => request('DELETE', `/api/emergency-contacts/${id}`, null, token);

// ── Responder ──
export const updateDutyStatus = (status, token) => request('PUT', '/api/responders/status', { status }, token);
export const updateLocation = (lat, lng, token) => request('PUT', '/api/responders/location', { latitude: lat, longitude: lng }, token);

// ── Device ──
export const pairDevice = (data, token) => request('POST', '/api/devices/pair', data, token);
export const getDeviceStatus = (deviceId, token) => request('GET', `/api/devices/${deviceId}/status`, null, token);

// ── Crash Simulation (device API key auth, NOT JWT) ──
export const simulateCrash = (deviceId, payload) =>
  request('POST', '/api/telemetry/crash', { ...payload, _deviceId: deviceId }, null, true);
export const sendHeartbeat = (deviceId, payload) =>
  request('POST', '/api/telemetry/heartbeat', { ...payload, _deviceId: deviceId }, null, true);

// ── Crash Actions (JWT auth) ──
export const cancelCrash = (crashEventId, token) => request('POST', `/api/telemetry/crash/${crashEventId}/cancel`, null, token);
export const lateCancelCrash = (crashEventId, token) => request('POST', `/api/telemetry/crash/${crashEventId}/late-cancel`, null, token);

// ── Dispatch ──
export const acceptDispatch = (incidentId, token) => request('POST', `/api/dispatch/${incidentId}/accept`, null, token);
export const rejectDispatch = (incidentId, token) => request('POST', `/api/dispatch/${incidentId}/reject`, null, token);
export const updateIncidentStatus = (incidentId, status, hospitalId, token) =>
  request('POST', `/api/dispatch/${incidentId}/status`, { status, hospitalId }, token);

// ── Incidents ──
export const getIncident = (id, token) => request('GET', `/api/incidents/${id}`, null, token);
export const getActiveIncidents = (token) => request('GET', '/api/incidents/active', null, token);
export const bystanderAssist = (incidentId, token) => request('POST', `/api/incidents/${incidentId}/bystander-assist`, null, token);

// ── ML Service (direct, for testing) ──
export const testMlPredict = (data) => fetch('http://localhost:8000/predict', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)
}).then(r => r.json());
