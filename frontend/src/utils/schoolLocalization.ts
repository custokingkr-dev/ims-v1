export type SchoolLocalization = {
  countryCode: string;
  locale: string;
  currencyCode: string;
  phoneRegion: string;
  timeZone: string;
};

export const DEFAULT_SCHOOL_LOCALIZATION: SchoolLocalization = {
  countryCode: 'IN', locale: 'en-IN', currencyCode: 'INR', phoneRegion: 'IN', timeZone: 'Asia/Kolkata',
};

export const SCHOOL_COUNTRY_PRESETS: Array<SchoolLocalization & { name: string }> = [
  { name: 'India', countryCode: 'IN', locale: 'en-IN', currencyCode: 'INR', phoneRegion: 'IN', timeZone: 'Asia/Kolkata' },
  { name: 'United Arab Emirates', countryCode: 'AE', locale: 'en-AE', currencyCode: 'AED', phoneRegion: 'AE', timeZone: 'Asia/Dubai' },
  { name: 'Singapore', countryCode: 'SG', locale: 'en-SG', currencyCode: 'SGD', phoneRegion: 'SG', timeZone: 'Asia/Singapore' },
  { name: 'United Kingdom', countryCode: 'GB', locale: 'en-GB', currencyCode: 'GBP', phoneRegion: 'GB', timeZone: 'Europe/London' },
  { name: 'United States', countryCode: 'US', locale: 'en-US', currencyCode: 'USD', phoneRegion: 'US', timeZone: 'America/New_York' },
  { name: 'Canada', countryCode: 'CA', locale: 'en-CA', currencyCode: 'CAD', phoneRegion: 'CA', timeZone: 'America/Toronto' },
  { name: 'Australia', countryCode: 'AU', locale: 'en-AU', currencyCode: 'AUD', phoneRegion: 'AU', timeZone: 'Australia/Sydney' },
  { name: 'New Zealand', countryCode: 'NZ', locale: 'en-NZ', currencyCode: 'NZD', phoneRegion: 'NZ', timeZone: 'Pacific/Auckland' },
  { name: 'South Africa', countryCode: 'ZA', locale: 'en-ZA', currencyCode: 'ZAR', phoneRegion: 'ZA', timeZone: 'Africa/Johannesburg' },
];

export function localizationForCountry(countryCode: string): SchoolLocalization | null {
  const preset = SCHOOL_COUNTRY_PRESETS.find((item) => item.countryCode === countryCode.toUpperCase());
  if (!preset) return null;
  const { name: _name, ...settings } = preset;
  return settings;
}

export function formatSchoolCurrency(amount: number, settings?: Partial<SchoolLocalization>): string {
  return new Intl.NumberFormat(settings?.locale || DEFAULT_SCHOOL_LOCALIZATION.locale, {
    style: 'currency', currency: settings?.currencyCode || DEFAULT_SCHOOL_LOCALIZATION.currencyCode,
    maximumFractionDigits: 2,
  }).format(amount);
}

export function formatSchoolDateTime(value: string | number | Date, settings?: Partial<SchoolLocalization>): string {
  return new Intl.DateTimeFormat(settings?.locale || DEFAULT_SCHOOL_LOCALIZATION.locale, {
    dateStyle: 'medium', timeStyle: 'short', timeZone: settings?.timeZone || DEFAULT_SCHOOL_LOCALIZATION.timeZone,
  }).format(new Date(value));
}
