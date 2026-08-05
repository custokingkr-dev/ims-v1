export const DEFAULT_SCHOOL_TIME_ZONE = 'Asia/Kolkata';

const fallbackTimeZones = [
  DEFAULT_SCHOOL_TIME_ZONE,
  'Asia/Dubai',
  'Asia/Dhaka',
  'Asia/Kathmandu',
  'Asia/Singapore',
  'Africa/Johannesburg',
  'Europe/London',
  'Europe/Paris',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'Australia/Sydney',
  'Pacific/Auckland',
  'UTC',
];

const supportedValuesOf = (Intl as typeof Intl & {
  supportedValuesOf?: (key: 'timeZone') => string[];
}).supportedValuesOf;

const supportedTimeZones = supportedValuesOf ? supportedValuesOf('timeZone') : fallbackTimeZones;

export const SCHOOL_TIME_ZONES = Array.from(
  new Set([...fallbackTimeZones, ...supportedTimeZones]),
).sort((left, right) => left.localeCompare(right));
