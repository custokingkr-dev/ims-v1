import { useEffect, useState } from 'react';
import api from '../services/api';

export function useApiData<T>(endpoint: string, params?: Record<string, unknown>) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    try {
      setLoading(true);
      setError('');
      const res = await api.get<T>(endpoint, params ? { params } : undefined);
      setData(res.data ?? null);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to load data.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [endpoint]);

  return { data, loading, error, reload: load, setData };
}
