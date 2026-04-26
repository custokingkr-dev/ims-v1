import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import type { AuthUser } from '../types/auth';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000
});

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = localStorage.getItem('custoking_auth');
  if (auth) {
    const parsed = JSON.parse(auth) as AuthUser;
    config.headers.Authorization = `Bearer ${parsed.accessToken}`;
  }
  return config;
});

let refreshing: Promise<AuthUser | null> | null = null;

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<{ message?: string }>) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined;
    const status = error.response?.status;
    const message = error.response?.data?.message || '';
    const looksLikeExpiredSession = status === 401 || ((status === 400 || status === 403) && ['Invalid access token', 'Missing bearer token'].includes(message));
    if (looksLikeExpiredSession && original && !original._retry) {
      original._retry = true;
      const stored = localStorage.getItem('custoking_auth');
      if (!stored) {
        return Promise.reject(error);
      }
      const auth = JSON.parse(stored) as AuthUser;
      if (!auth.refreshToken) {
        localStorage.removeItem('custoking_auth');
        return Promise.reject(error);
      }
      refreshing ??= api.post<AuthUser>('/auth/refresh', { refreshToken: auth.refreshToken })
        .then((res) => {
          const merged: AuthUser = { ...auth, ...res.data, refreshToken: res.data.refreshToken || auth.refreshToken };
          localStorage.setItem('custoking_auth', JSON.stringify(merged));
          return merged;
        })
        .catch((refreshError) => {
          localStorage.removeItem('custoking_auth');
          throw refreshError;
        })
        .finally(() => {
          refreshing = null;
        });
      const refreshed = await refreshing;
      if (refreshed) {
        original.headers.Authorization = `Bearer ${refreshed.accessToken}`;
      }
      return api(original);
    }
    return Promise.reject(error);
  }
);

export default api;
