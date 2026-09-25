import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { OrderAssetField } from './OrderAssetField';
afterEach(cleanup);
it('never builds a previewable URL from an untrusted content type', () => {
  // A blob URL inherits the blob's type. If a stored file or an API response ever claimed
  // text/html or image/svg+xml, opening that URL would run script on the app's own origin, so the
  // type is taken from an allow-list rather than from the data.
  const created: Blob[] = [];
  const original = URL.createObjectURL;
  URL.revokeObjectURL = (() => {}) as typeof URL.revokeObjectURL;
  URL.createObjectURL = ((blob: Blob) => { created.push(blob); return 'blob:stub'; }) as typeof URL.createObjectURL;
  try {
    const hostile = new File(['<script>alert(1)</script>'], 'note.html', { type: 'text/html' });
    render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required to place order" file={hostile} />);
    expect(created).toHaveLength(1);
    expect(created[0].type).toBe('application/octet-stream');
  } finally {
    URL.createObjectURL = original;
    // revokeObjectURL stays stubbed: jsdom has none, and the component revokes on unmount.
  }
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
