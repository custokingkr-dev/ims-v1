import { useEffect, useMemo, useState } from 'react';
import { Plus, Search, Trophy } from 'lucide-react';
import type { ProductOption } from '../types';
import { availableTrophySizes, filterTrophies, trophyImage, trophyProducts, trophyVariantCode, type TrophyProduct } from './catalogue';
import './trophies.css';

const money = (value: number) => value.toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
interface Props { options: ProductOption[]; disabled?: boolean; onAdd: (variant: string, quantity: number) => boolean }

function TrophyCard({ product, sizes, disabled, onAdd }: Props & { product: TrophyProduct; sizes: string[] }) {
  const [chosenSize, setSize] = useState(sizes[0]);
  const size = sizes.includes(chosenSize) ? chosenSize : sizes[0];
  const [quantity, setQuantity] = useState('1');
  const [imageFailed, setImageFailed] = useState(false);
  const [added, setAdded] = useState(false);
  const image = trophyImage(product.model);
  const count = Number(quantity);
  const validCount = Number.isInteger(count) && count >= 1 && count <= 2147483647;
  return <article className="ck-trophy-card" aria-label={`Trophy ${product.model}`}>
    <div className="ck-trophy-image">
      {image && !imageFailed ? <img src={image} alt={`Trophy model ${product.model}`} loading="lazy" decoding="async" onError={() => setImageFailed(true)} />
        : <div className="ck-trophy-placeholder"><Trophy size={44} strokeWidth={1.3} aria-hidden="true" /><span>Image unavailable</span></div>}
      <span className="ck-trophy-kind">{product.type === 'Economic Trophy' ? 'Economic' : 'Trophy'}</span>
    </div>
    <div className="ck-trophy-card-body">
      <div className="ck-trophy-model"><h3>{product.model}</h3><span>{product.startingPrice > 0 ? `From ${money(product.startingPrice)}` : 'Quote required'}</span></div>
      <label className="field"><span>Size</span><select aria-label={`Size for ${product.model}`} value={size} disabled={disabled}
        onChange={(event) => { setSize(event.target.value); setAdded(false); }}>
        {sizes.map((key) => <option key={key} value={key}>Size {key} · {product.sizes[key] > 0 ? money(product.sizes[key]) : 'Quote required'}</option>)}
      </select></label>
      <div className="ck-trophy-price"><strong>{product.sizes[size] > 0 ? money(product.sizes[size]) : 'Quote required'}</strong><span>Catalogue unit price</span></div>
      <div className="ck-trophy-card-actions"><label className="field"><span>Quantity</span>
        <input aria-label={`Quantity for ${product.model}`} type="number" min="1" max="2147483647" step="1" value={quantity} disabled={disabled}
          onChange={(event) => { setQuantity(event.target.value); setAdded(false); }} />
      </label><button type="button" className="ck-btn ck-btn-g" disabled={disabled || !validCount} aria-label={`Add ${product.model} size ${size} to order`}
        onClick={() => { setAdded(onAdd(trophyVariantCode(product.model, size), count)); }}><Plus size={15} aria-hidden="true" />{added ? 'Add again' : 'Add to order'}</button></div>
      {!validCount && <span className="ck-trophy-error">Enter a positive whole quantity.</span>}
      {added && <span className="ck-trophy-added" role="status">Added {count} × {product.model}, size {size} to your order.</span>}
    </div>
  </article>;
}

export function TrophyCatalogue({ options, disabled, onAdd }: Props) {
  const [filters, setFilters] = useState({ search: '', type: '', min: '', max: '', sort: 'ASC' });
  const [visible, setVisible] = useState(24);
  const products = useMemo(() => trophyProducts.filter((product) => availableTrophySizes(product, options).length > 0), [options]);
  const filtered = useMemo(() => filterTrophies(products, filters), [products, filters]);
  useEffect(() => setVisible(24), [filters]);
  const reset = () => setFilters({ search: '', type: '', min: '', max: '', sort: 'ASC' });
  const invalidRange = filters.min !== '' && filters.max !== '' && Number(filters.min) > Number(filters.max);
  return <section className="ck-trophy-catalogue" aria-label="Trophy catalogue">
    <div className="ck-trophy-intro"><div><span className="ck-trophy-eyebrow">Made for moments that matter</span><h3>Choose your next award</h3>
      <p>Explore models, choose a size and add quantities to one order.</p></div><Trophy size={42} strokeWidth={1.3} aria-hidden="true" /></div>
    <div className="ck-trophy-filters">
      <label className="field ck-trophy-search"><span>Search model</span><span><Search size={16} aria-hidden="true" /><input aria-label="Search trophy model" placeholder="e.g. WM001 or A-3" value={filters.search} onChange={(e) => setFilters({ ...filters, search: e.target.value })} /></span></label>
      <label className="field"><span>Type</span><select aria-label="Trophy type" value={filters.type} onChange={(e) => setFilters({ ...filters, type: e.target.value })}><option value="">All trophies</option><option>Trophy</option><option>Economic Trophy</option></select></label>
      <label className="field"><span>Min starting price (₹)</span><input aria-label="Minimum starting price" type="number" min="0" value={filters.min} onChange={(e) => setFilters({ ...filters, min: e.target.value })} /></label>
      <label className="field"><span>Max starting price (₹)</span><input aria-label="Maximum starting price" type="number" min="0" value={filters.max} onChange={(e) => setFilters({ ...filters, max: e.target.value })} /></label>
      <label className="field"><span>Sort by</span><select aria-label="Sort trophies" value={filters.sort} onChange={(e) => setFilters({ ...filters, sort: e.target.value })}><option value="ASC">Starting price: low to high</option><option value="DESC">Starting price: high to low</option><option value="MODEL">Model number</option></select></label>
    </div>
    <div className="ck-trophy-results"><span role="status">{filtered.length} {filtered.length === 1 ? 'model' : 'models'}{filtered.length > visible ? ` · showing ${visible}` : ''}</span><button type="button" className="ck-btn ck-btn-ghost" onClick={reset}>Reset filters</button></div>
    {invalidRange && <p role="alert">Minimum starting price must not exceed the maximum.</p>}
    {!filtered.length && <div className="ck-trophy-empty"><Trophy size={32} aria-hidden="true" /><h3>No matching trophies</h3><p>Try another model, type or price range.</p></div>}
    <div className="ck-trophy-grid">{filtered.slice(0, visible).map((product) => <TrophyCard key={product.model} product={product} sizes={availableTrophySizes(product, options)} options={options} disabled={disabled} onAdd={onAdd} />)}</div>
    {filtered.length > visible && <button type="button" className="ck-btn ck-btn-ghost ck-trophy-more" onClick={() => setVisible((count) => count + 24)}>Show more trophies</button>}
    <p className="ck-product-muted">Prices are catalogue estimates. Final pricing, tax and availability are confirmed in the Custoking quote. Size letters are catalogue codes; physical dimensions are not supplied.</p>
  </section>;
}
