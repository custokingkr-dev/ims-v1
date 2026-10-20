import crypto from 'node:crypto';

const id = (kind, key) => `${kind}-${crypto.createHash('sha256').update(key).digest('hex')}`;
export function memorySecurityState() {
  const entries = new Map();
  return {
    async claim(kind, key, expires) {
      for (const [name, until] of entries) if (until <= Date.now()) entries.delete(name);
      const name = id(kind, key);
      if (entries.has(name)) return false;
      if (entries.size >= 10000) throw new Error('Security state capacity exceeded');
      entries.set(name, expires); return true;
    },
    async contains(kind, key) { return (entries.get(id(kind, key)) || 0) > Date.now(); },
  };
}

export function firestoreSecurityState({ project, database, accessToken, fetchImpl = fetch }) {
  if (!/^[a-z][a-z0-9-]{4,62}$/.test(project || '') || !/^[a-z][a-z0-9-]{3,62}$/.test(database || '') || database === '(default)')
    throw new Error('Dedicated dashboard state database configuration is invalid');
  const root = `https://firestore.googleapis.com/v1/projects/${project}/databases/${database}/documents/dashboardSecurityState`;
  let active = 0;
  async function request(url, method, body) {
    if (active >= 8) throw new Error('Security state is busy');
    active++;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetchImpl(url, { method, signal: controller.signal,
        headers: { authorization: `Bearer ${await accessToken()}`, 'content-type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {}) });
      const reader = response.body?.getReader();
      let size = 0;
      if (reader) {
        try { while (true) { const part = await reader.read(); if (part.done) break; size += part.value.byteLength;
          if (size > 65536) { controller.abort(); throw new Error('Security state response exceeds size limit'); } } }
        finally { await reader.cancel().catch(() => {}); }
      }
      return response.status;
    } finally { clearTimeout(timer); active--; }
  }
  return {
    async claim(kind, key, expires) {
      // createDocument is atomic: conflict means another replica already consumed/revoked it.
      const status = await request(`${root}?documentId=${id(kind, key)}`, 'POST', {
        fields: { expiresAt: { timestampValue: new Date(expires).toISOString() } },
      });
      if (status === 409) return false;
      if (status !== 200) throw new Error('Durable security state unavailable');
      return true;
    },
    async contains(kind, key) {
      const status = await request(`${root}/${id(kind, key)}`, 'GET');
      if (status === 404) return false;
      if (status !== 200) throw new Error('Durable security state unavailable');
      return true; // TTL cleanup may lag; expired cookies are already rejected independently.
    },
  };
}
