import { describe, expect, it } from 'vitest';
import { formatSchoolCurrency, formatSchoolDateTime, localizationForCountry } from './schoolLocalization';

describe('school localization', () => {
  it('derives a coherent locale bundle from country selection', () => {
    expect(localizationForCountry('GB')).toEqual({
      countryCode: 'GB', locale: 'en-GB', currencyCode: 'GBP', phoneRegion: 'GB', timeZone: 'Europe/London',
    });
  });

  it('formats money with the school currency instead of a hardcoded rupee symbol', () => {
    expect(formatSchoolCurrency(1250.5, { locale: 'en-GB', currencyCode: 'GBP' })).toContain('£');
  });

  it('formats the same instant in the school timezone', () => {
    const instant = '2026-01-01T00:30:00Z';
    expect(formatSchoolDateTime(instant, { locale: 'en-GB', timeZone: 'Europe/London' })).toContain('1 Jan 2026');
    expect(formatSchoolDateTime(instant, { locale: 'en-US', timeZone: 'America/New_York' })).toContain('Dec 31, 2025');
  });
});
