import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { OrderAssetField } from './OrderAssetField';
import type { OrderAsset } from './types';

vi.mock('../../services/api', () => ({ default: { get: vi.fn() } }));
const created = new Map<string, Blob>();
const revoke = vi.fn();
const asset: OrderAsset = { id: 1, assetKind: 'DESIGN', contentType: 'image/png', sizeBytes: 32,
  originalFilename: 'artwork.png', contentUrl: '/unused', uploadedAt: '2026-09-26T00:00:00Z' };
beforeEach(() => {
  vi.clearAllMocks(); created.clear();
  let nextId = 0;
  vi.stubGlobal('URL', class extends URL {
    static createObjectURL(blob: Blob) {
      const url = `blob:asset-${++nextId}`;
      created.set(url, blob); return url;
    }
    static revokeObjectURL = revoke;
  });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function readBytes(blob: Blob): Promise<number[]> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve([...new Uint8Array(reader.result as ArrayBuffer)]);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
}

it.each(['text/html', 'image/svg+xml', 'application/xhtml+xml', 'application/pdf', ''])('downloads %s as unchanged bytes without an inline preview', async (type) => {
  const file = new File(['<svg onload="alert(1)">', new Uint8Array([0, 255, 128])], 'untrusted.dat', { type });
  render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required" file={file} />);
  const link = screen.getByRole('link', { name: 'Download untrusted.dat' });
  const download = created.get(link.getAttribute('href')!)!;
  expect(download).not.toBe(file);
  expect(download.type).toBe('application/octet-stream');
  expect(await readBytes(download)).toEqual(await readBytes(file));
  expect(link).toHaveAttribute('download', 'untrusted.dat');
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(created.size).toBe(1);
});

it.each(['image/png', 'image/jpeg', 'image/webp'])('separates a %s preview from its byte download and releases both URLs', async (type) => {
  const file = new File([new Uint8Array([0, 255, 128, 1])], 'photo.dat', { type });
  const view = render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required" file={file} />);
  const previewUrl = screen.getByRole('img').getAttribute('src')!;
  const downloadUrl = screen.getByRole('link').getAttribute('href')!;
  expect(previewUrl).not.toBe(downloadUrl);
  expect(created.get(previewUrl)).not.toBe(file);
  expect(created.get(previewUrl)!.type).toBe(type);
  expect(created.get(downloadUrl)!.type).toBe('application/octet-stream');
  expect(await readBytes(created.get(downloadUrl)!)).toEqual(await readBytes(file));
  view.unmount();
  expect(revoke.mock.calls.map(([url]) => url).sort()).toEqual([previewUrl, downloadUrl].sort());
});

it.each(['text/html', 'image/svg+xml'])('ignores stored image metadata when the API returns %s', async (type) => {
  const data = new Blob(['<svg onload="alert(1)">'], { type });
  vi.mocked(api.get).mockResolvedValueOnce({ data });
  render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required" orderId="order/1" asset={asset} />);
  const link = await screen.findByRole('link', { name: 'Download artwork.png' });
  expect(api.get).toHaveBeenCalledWith('/supply/orders/order%2F1/assets/1/content', { responseType: 'blob' });
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  const download = created.get(link.getAttribute('href')!)!;
  expect(download.type).toBe('application/octet-stream');
  expect(await readBytes(download)).toEqual(await readBytes(data));
});

it('revokes replaced URLs and ignores an API response after the asset changes', async () => {
  let resolveFirst!: (response: { data: Blob }) => void;
  vi.mocked(api.get).mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve; }));
  const view = render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required" orderId="order-1" asset={asset} />);
  const file = new File(['png'], 'replacement.png', { type: 'image/png' });
  view.rerender(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required" file={file} />);
  const oldUrls = [...created.keys()];
  await act(async () => { resolveFirst({ data: new Blob(['old'], { type: 'image/png' }) }); });
  expect(created.size).toBe(2);
  expect(screen.getByRole('link')).toHaveAttribute('download', 'replacement.png');
  view.rerender(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required" file={new File(['pdf'], 'next.pdf', { type: 'application/pdf' })} />);
  await waitFor(() => expect(screen.queryByRole('img')).not.toBeInTheDocument());
  expect(revoke.mock.calls.map(([url]) => url).sort()).toEqual(oldUrls.sort());
  view.unmount();
  expect(revoke).toHaveBeenCalledTimes(3);
});

it('permits PDF artwork but rejects PDF as a pre-delivery photo', () => {
  const onFile = vi.fn();
  const file = new File(['%PDF-1.7'], 'artwork.pdf', { type: 'application/pdf' });
  const view = render(<OrderAssetField assetKind="PRE_DELIVERY_PHOTO" label="Pre-delivery photo" requirement="Required before delivery" onFile={onFile} />);
  const input = view.container.querySelector('input[type="file"]')!;
  expect(input).not.toHaveAttribute('accept', expect.stringContaining('application/pdf'));
  fireEvent.change(input, { target: { files: [file] } });
  expect(onFile).not.toHaveBeenCalled();
  expect(screen.getByRole('alert')).toHaveTextContent('JPEG, PNG or WebP.');
  view.unmount();
  const design = render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required to place order" onFile={onFile} />);
  fireEvent.change(design.container.querySelector('input[type="file"]')!, { target: { files: [file] } });
  expect(onFile).toHaveBeenCalledWith(file);
});
