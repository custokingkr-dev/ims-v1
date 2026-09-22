import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { OrderAssetField } from './OrderAssetField';
afterEach(cleanup);
it('permits PDF artwork but rejects PDF as a pre-delivery photo', () => {
  const onFile = vi.fn();
  const file = new File(['%PDF-1.7'], 'artwork.pdf', { type: 'application/pdf' });
  const view = render(<OrderAssetField assetKind="PRE_DELIVERY_PHOTO" label="Pre-delivery photo" requirement="Required before delivery" onFile={onFile} />);
  const input = view.container.querySelector('input[type="file"]')!;
  expect(input).not.toHaveAttribute('accept', expect.stringContaining('application/pdf'));
  fireEvent.change(input, { target: { files: [file] } });
  expect(onFile).not.toHaveBeenCalled();
  expect(screen.getByRole('alert')).toHaveTextContent('Choose a JPEG, PNG or WebP photo.');
  view.unmount();
  const design = render(<OrderAssetField assetKind="DESIGN" label="Design artwork" requirement="Required to place order" onFile={onFile} />);
  fireEvent.change(design.container.querySelector('input[type="file"]')!, { target: { files: [file] } });
  expect(onFile).toHaveBeenCalledWith(file);
});
