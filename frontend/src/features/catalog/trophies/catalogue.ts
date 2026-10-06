import source from './catalogue.json';
import imageFiles from './images.json';
import type { ProductOption } from '../types';

export interface TrophyProduct {
  model: string; type: string; startingPrice: number; sizes: Record<string, number>;
}
export const trophyProducts: TrophyProduct[] = source.map((product) => ({ ...product,
  sizes: Object.fromEntries(Object.entries(product.sizes).map(([size, price]) => {
    if (typeof price !== 'number') throw new Error(`Invalid catalogue price for ${product.model}.`);
    return [size, price];
  })),
}));
export const trophyVariantCode = (model: string, size: string) => `T_${model.toUpperCase().replace(/-/g, '_')}_${size}`;
export const trophyImage = (model: string) => {
  const file = (imageFiles as Record<string, string>)[model];
  return file ? `${import.meta.env.BASE_URL}catalog/trophies/images/${encodeURIComponent(file)}` : null;
};
const prices = new Map(trophyProducts.flatMap((product) => Object.entries(product.sizes)
  .map(([size, price]) => [trophyVariantCode(product.model, size), price] as const)));
export const trophyReferencePrice = (code: string): number | null => {
  const price = prices.get(code);
  // Zero in the supplied catalogue is an unspecified price, not a promise of a free trophy.
  return price && price > 0 ? price : null;
};
export const availableTrophySizes = (product: TrophyProduct, options: ProductOption[]) => {
  const available = new Set(options.filter((option) => option.active && option.specStatus === 'CONFIRMED').map((option) => option.code));
  return Object.keys(product.sizes).filter((size) => available.has(trophyVariantCode(product.model, size)));
};

export interface TrophyFilters { search: string; type: string; min: string; max: string; sort: string }
export function filterTrophies(products: TrophyProduct[], filters: TrophyFilters): TrophyProduct[] {
  const search = filters.search.trim().toLowerCase();
  return products.filter((product) => product.model.toLowerCase().includes(search)
    && (!filters.type || product.type === filters.type)
    && (filters.min === '' || product.startingPrice >= Number(filters.min))
    && (filters.max === '' || product.startingPrice <= Number(filters.max)))
    .sort((a, b) => filters.sort === 'MODEL' ? a.model.localeCompare(b.model, undefined, { numeric: true })
      : (filters.sort === 'DESC' ? b.startingPrice - a.startingPrice : a.startingPrice - b.startingPrice)
        || a.model.localeCompare(b.model, undefined, { numeric: true }));
}
