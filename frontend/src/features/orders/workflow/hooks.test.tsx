import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useServerClock } from './hooks';

afterEach(cleanup);

describe('useServerClock', () => {
  it('draws with a new clock offset in the same render, never with the uncorrected device clock', () => {
    const drawn: Array<{ offset: number; now: number }> = [];
    function Clock({ offset }: { offset: number }) {
      const now = useServerClock(offset, 30_000);
      drawn.push({ offset, now });
      return null;
    }
    const HOURS_2 = 2 * 3_600_000;
    const { rerender } = render(<Clock offset={0} />);
    // The list or order arrives and says the server is two hours ahead of this device.
    rerender(<Clock offset={HOURS_2} />);
    for (const { offset, now } of drawn) expect(Math.abs(now - (Date.now() + offset))).toBeLessThan(5_000);
    expect(drawn.some((entry) => entry.offset === HOURS_2)).toBe(true);
  });
});
