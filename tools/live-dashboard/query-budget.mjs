export function queryParameters(url) {
  const windowMinutes = Number(url.searchParams.get('window') || 180);
  const audience = url.searchParams.get('audience') || 'all';
  if (![15, 60, 180, 720, 1440, 10080].includes(windowMinutes) || !['all', 'owner', 'ops'].includes(audience))
    throw new Error('Unsupported dashboard query');
  return { windowMinutes, audience };
}

export function concurrencyBudget(maximum = 6, maximumQueue = 128) {
  let active = 0; const queue = [];
  return async function run(task, signal) {
    if (signal?.aborted) throw new Error('Dashboard query deadline exceeded');
    if (active >= maximum) {
      if (queue.length >= maximumQueue) throw new Error('Dashboard query capacity exceeded');
      await new Promise((resolve, reject) => {
        const entry = { resolve, reject };
        const abort = () => { const index = queue.indexOf(entry); if (index >= 0) queue.splice(index, 1); reject(new Error('Dashboard query cancelled')); };
        entry.resolve = () => { signal?.removeEventListener('abort', abort); resolve(); };
        signal?.addEventListener('abort', abort, { once: true }); queue.push(entry);
      });
    } else active++;
    try { if (signal?.aborted) throw new Error('Dashboard query cancelled'); return await task(); }
    finally { const next = queue.shift(); if (next) next.resolve(); else active--; }
  };
}

export function snapshotCache() {
  const cache = new Map(); let active = 0;
  return async (windowMinutes, audience, build) => {
    const key = `${windowMinutes}:${audience}`;
    const entry = cache.get(key);
    if (entry && entry.expires > Date.now()) return entry.promise;
    if (active >= 2) throw new Error('Dashboard query capacity exceeded');
    active++;
    const promise = Promise.resolve().then(build).finally(() => active--);
    cache.set(key, { expires: Date.now() + 30000, promise });
    try { return await promise; } catch (error) { cache.delete(key); throw error; }
  };
}

export function wholeResponseDeadline(request, milliseconds) {
  const timer = setTimeout(() => request.destroy(new Error('Dashboard upstream deadline exceeded')), milliseconds);
  request.once('close', () => clearTimeout(timer));
  return timer;
}
