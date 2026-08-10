import { describe, expect, it } from 'vitest';
import en from '../../../public/i18n/en.json';
import pt from '../../../public/i18n/pt.json';

/** Flattens nested translation objects into dotted keys. */
function keysOf(obj: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return value && typeof value === 'object'
      ? keysOf(value as Record<string, unknown>, path)
      : [path];
  });
}

/**
 * A key present in one locale but not the other renders as the raw key for users of the missing
 * language — a visible defect that nothing else in the build catches.
 */
describe('translations', () => {
  const enKeys = keysOf(en as Record<string, unknown>).sort();
  const ptKeys = keysOf(pt as Record<string, unknown>).sort();

  it('has the same keys in both locales', () => {
    const missingInPt = enKeys.filter((k) => !ptKeys.includes(k));
    const missingInEn = ptKeys.filter((k) => !enKeys.includes(k));
    expect({ missingInPt, missingInEn }).toEqual({ missingInPt: [], missingInEn: [] });
  });

  it('has no empty values', () => {
    const empty = [
      ...Object.entries(flatten(en as Record<string, unknown>)),
      ...Object.entries(flatten(pt as Record<string, unknown>)),
    ]
      .filter(([, v]) => typeof v === 'string' && v.trim() === '')
      .map(([k]) => k);
    expect(empty).toEqual([]);
  });
});

function flatten(obj: Record<string, unknown>, prefix = ''): Record<string, unknown> {
  return Object.entries(obj).reduce<Record<string, unknown>>((acc, [key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object') Object.assign(acc, flatten(value as Record<string, unknown>, path));
    else acc[path] = value;
    return acc;
  }, {});
}
