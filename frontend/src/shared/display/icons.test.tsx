import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { NavIcon } from './icons';

describe('NavIcon', () => {
  it('renders a menu icon for Student Data Export', () => {
    const { container } = render(<NavIcon panelKey="studentexport" />);

    expect(container.querySelector('svg')).not.toBeNull();
  });
});
