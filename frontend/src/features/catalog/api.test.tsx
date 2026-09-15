import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { getFormOrder, parseProductCategories, parseProductFormDefinition, useProductCategories, useProductFormDefinition } from './api';
import { notebookDefinition } from './catalogTestFixtures';
vi.mock('../../services/api', () => ({ default: { get: vi.fn() } }));
beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);
it('rejects malformed categories and nested definitions at the API boundary', () => {
  expect(() => parseProductCategories({})).toThrow(/Catalog data could not be read/);
  expect(() => parseProductCategories([null])).toThrow(/Catalog data could not be read/);
  expect(() => parseProductFormDefinition({})).toThrow(/Notebook options could not be read/);
  expect(() => parseProductFormDefinition({ ...notebookDefinition, groups: [{ ...notebookDefinition.groups[0], options: {} }] })).toThrow(/Notebook options could not be read/);
  expect(() => parseProductFormDefinition({ ...notebookDefinition, rules: [{ ...notebookDefinition.rules[0], params: null }] })).toThrow(/Notebook options could not be read/);
  expect(() => parseProductFormDefinition(notebookDefinition)).not.toThrow();
});
it('keeps malformed catalog data out of React state and recovers after retry', async () => {
  vi.mocked(api.get).mockResolvedValueOnce({ data: {} }).mockResolvedValueOnce({ data: [notebookDefinition.category] });
  const { result } = renderHook(() => useProductCategories());
  await waitFor(() => expect(result.current.error).toMatch(/Catalog data could not be read/));
  expect(result.current.categories).toBeNull();
  act(() => result.current.retry());
  await waitFor(() => expect(result.current.categories).toEqual([notebookDefinition.category]));
  expect(result.current.error).toBe('');
});
it('shows a recoverable error for a sparse form response', async () => {
  vi.mocked(api.get).mockResolvedValueOnce({ data: {} }).mockResolvedValueOnce({ data: notebookDefinition });
  const { result } = renderHook(() => useProductFormDefinition('NOTEBOOKS'));
  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(result.current.definition).toBeNull();
  expect(result.current.error).toMatch(/Notebook options could not be read/);
  act(() => result.current.retry());
  await waitFor(() => expect(result.current.definition).toEqual(notebookDefinition));
});
it('rejects an invalid order detail before the saved-order view can render it', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: {} });
  await expect(getFormOrder('ORD-42')).rejects.toThrow('Order details could not be read. Please refresh the order.');
});
