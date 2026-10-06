import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { createIdentityAuthClient } from '../generated/identityAuthApi';
import type { AuthUser } from '../types/auth';

// Access token lives only in this module-level variable — never written to
// localStorage or sessionStorage. This reduces persistence; XSS can still use
// an active session or read memory. Lost on page refresh;
// AuthProvider calls refreshToken() on mount to restore it via the HttpOnly cookie.
let accessToken: string | null = null;
let authSessionVersion = 0;
const SESSION_EPOCH_KEY = 'custoking_session_epoch';
const sessionEpoch = () => localStorage.getItem(SESSION_EPOCH_KEY);

export function invalidateAuthSession(): void {
  localStorage.removeItem('custoking_isLoggedIn');
  localStorage.setItem(SESSION_EPOCH_KEY, crypto.randomUUID());
  setAccessToken(null);
}

export function setAccessToken(token: string | null): void {
  if (accessToken !== token) authSessionVersion += 1;
  accessToken = token;
}

/** Read-only access for authenticated streaming downloads handled by the browser fetch API. */
export function getAccessToken(): string | null {
  return accessToken;
}

export function getAuthSessionVersion(): number {
  return authSessionVersion;
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000,
  // Sends the HttpOnly refresh-token cookie automatically on every request.
  withCredentials: true,
});

// Generated clients share this exact Axios transport so credentials, authorization,
// timeout, refresh suppression, and every future transport interceptor remain centralized.
export const identityAuthClient = createIdentityAuthClient(api);

export async function withAuthSessionLock<T>(operation: () => Promise<T>): Promise<T> {
  // The cookie is shared between tabs. Serialize rotation/logout across the
  // entire origin, not just within this module. No credentials leave memory.
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return await navigator.locks.request('custoking-auth-session', operation);
  }
  return operation();
}

export class SessionRestoreUnavailableError extends Error {
  constructor() { super('Your session could not be restored. Check your connection and retry.'); }
}

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // A cold identity-service start can exceed the ordinary 30-second deadline.
  // Extend only the canonical login POST; never retry credential submission.
  if (config.method?.toLowerCase() === 'post' && config.url === '/auth/login') {
    config.timeout = 60000;
  }
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

// Deduplication guard: multiple concurrent 401s share one refresh call.
let refreshing: Promise<AuthUser | null> | null = null;

/**
 * Calls POST /api/v1/auth/refresh — the browser sends the HttpOnly cookie automatically.
 * On success updates the in-memory access token and returns the full user object.
 * Only confirmed invalidity clears auth state. Temporary failures remain recoverable.
 */
export async function refreshToken(): Promise<AuthUser | null> {
  const admittedEpoch = sessionEpoch();
  refreshing ??= withAuthSessionLock(() => admittedEpoch === sessionEpoch() ? identityAuthClient.refresh() : Promise.resolve(null))
    .then((restored) => {
      if (!restored || admittedEpoch !== sessionEpoch()) return null;
      setAccessToken(restored.accessToken);
      return restored;
    })
    .catch((error: AxiosError) => {
      if (admittedEpoch !== sessionEpoch()) return null;
      if (![401, 403].includes(error.response?.status ?? 0)) throw new SessionRestoreUnavailableError();
      setAccessToken(null);
      localStorage.removeItem('custoking_isLoggedIn');
      return null;
    })
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<{ message?: string; code?: string }>) => {
    if (error.response?.status === 403 && error.response.data?.code === 'STEP_UP_REQUIRED') {
      window.dispatchEvent(new Event('custoking-step-up-required'));
      // Do not replay a mutation automatically after a verification ceremony.
      return Promise.reject(error);
    }
    const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined;
    const isAuthEndpoint = original?.url?.includes('/auth/');

    // Only intercept 401s on non-auth endpoints, and only once per request.
    if (error.response?.status === 401 && original && !original._retry && !isAuthEndpoint) {
      original._retry = true;
      const user = await refreshToken();
      if (user) {
        original.headers.Authorization = `Bearer ${user.accessToken}`;
        return api(original);
      }
      // Refresh failed — session is gone; send the user to login.
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default api;
