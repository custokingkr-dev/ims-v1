import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api, { identityAuthClient, setAccessToken } from './api';

// Use the real Axios pipeline with a local adapter: this exercises merged defaults,
// generated calls, and both interceptors without network traffic or a 60-second wait.
const originalAdapter = api.defaults.adapter;
const login = { email: 'login@example.test', password: 'test-password' };

describe('login transport deadline', () => {
  beforeEach(() => { setAccessToken(null); });
  afterEach(() => { api.defaults.adapter = originalAdapter; setAccessToken(null); });

  it('gives only the canonical login POST 60 seconds and leaves ordinary requests at 30 seconds', async () => {
    const adapter = vi.fn<AxiosAdapter>().mockImplementation(async config => ({
      data: { accessToken: 'test-token' }, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config,
    }));
    api.defaults.adapter = adapter;

    await api.get('/schools');
    await identityAuthClient.login(login);
    await identityAuthClient.refresh();
    await api.get('/auth/login');
    await api.post('/auth/login-audit');
    await identityAuthClient.requestPasswordReset({ email: login.email });

    expect(api.defaults.timeout).toBe(30000);
    expect(adapter.mock.calls.map(([config]) => [config.method, config.url, config.timeout])).toEqual([
      ['get', '/schools', 30000],
      ['post', '/auth/login', 60000],
      ['post', '/auth/refresh', 30000],
      ['get', '/auth/login', 30000],
      ['post', '/auth/login-audit', 30000],
      ['post', '/auth/password-reset/request', 30000],
    ]);
    expect(adapter.mock.calls[1][0].withCredentials).toBe(true);
    expect(JSON.parse(adapter.mock.calls[1][0].data)).toEqual(login);
  });

  it.each(['timeout', 'unauthorized', 'unavailable'] as const)('does not retry or refresh login after %s', async failure => {
    let rejection: AxiosError;
    const adapter = vi.fn<AxiosAdapter>().mockImplementation(async (config: InternalAxiosRequestConfig) => {
      rejection = new AxiosError(
        failure === 'timeout' ? 'timeout of 60000ms exceeded' : 'Login rejected',
        failure === 'timeout' ? 'ECONNABORTED' : 'ERR_BAD_RESPONSE', config, undefined,
        failure === 'timeout' ? undefined : { data: {}, status: failure === 'unauthorized' ? 401 : 503, statusText: '', headers: new AxiosHeaders(), config },
      );
      throw rejection;
    });
    api.defaults.adapter = adapter;

    const caught = await identityAuthClient.login(login).catch(error => error);

    expect(caught).toBe(rejection!);
    expect(adapter).toHaveBeenCalledTimes(1);
    expect(adapter.mock.calls[0][0]).toEqual(expect.objectContaining({ url: '/auth/login', method: 'post', timeout: 60000 }));
    expect(rejection!.config?.url).toBe('/auth/login');
  });
});
