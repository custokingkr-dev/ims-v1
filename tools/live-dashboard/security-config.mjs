export function securityConfiguration(env = process.env) {
  const deployed = Boolean(env.K_SERVICE) || ['production', 'prod', 'dev', 'staging'].includes(env.NODE_ENV)
    || ['prod', 'dev', 'staging'].includes(env.DASHBOARD_ENV);
  const authRequired = env.DASHBOARD_AUTH !== 'off';
  if (deployed && !authRequired) throw new Error('Deployed dashboard authentication cannot be disabled');
  const publicUrl = env.DASHBOARD_PUBLIC_URL || '';
  if (deployed || publicUrl) {
    let origin;
    try { origin = new URL(publicUrl); } catch { throw new Error('Dashboard requires a pinned public HTTPS origin'); }
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/')
      throw new Error('Dashboard requires a pinned public HTTPS origin');
  }
  if (deployed && Buffer.byteLength(env.SESSION_SECRET || '', 'utf8') < 32)
    throw new Error('Deployed dashboard requires a stable managed session secret of at least 32 bytes');
  if (deployed && (!env.DASHBOARD_STATE_DATABASE || !env.DASHBOARD_PROJECT))
    throw new Error('Deployed dashboard requires a dedicated durable security-state database');
  return { deployed, authRequired, publicUrl: publicUrl.replace(/\/+$/, '') };
}
