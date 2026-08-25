import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { createIdentityAuthClient } from '../generated/identityAuthApi';
import type { AuthUser } from '../types/auth';

// Access token lives only in this module-level variable — never written to
// localStorage or sessionStorage so XSS cannot steal it.  Lost on page refresh;
// AuthProvider calls refreshToken() on mount to restore it via the HttpOnly cookie.
let accessToken: string | null = null;
let authSessionVersion = 0;

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

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
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
 * On failure clears auth state; the caller is responsible for redirecting to /login.
 */
export async function refreshToken(): Promise<AuthUser | null> {
  refreshing ??= identityAuthClient.refresh()
    .then((restored) => {
      setAccessToken(restored.accessToken);
      return restored;
    })
    .catch(() => {
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
  async (error: AxiosError<{ message?: string }>) => {
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
