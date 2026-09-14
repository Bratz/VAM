import React, { useMemo, useState } from 'react';
import { ComposableMap, Geographies, Geography } from 'react-simple-maps';
import { geoEqualEarth, geoArea } from 'd3-geo';
import { feature } from 'topojson-client';
import worldAtlas from 'world-atlas/countries-50m.json';
import type { Topology, GeometryCollection } from 'topojson-specification';
import { BankShare } from '../multiBank/BankSplitBar';
import { formatCurrency } from '../../utils';

// react-simple-maps' Geographies expects real GeoJSON, not raw TopoJSON —
// convert once at module scope (world-atlas's file never changes at
// runtime) rather than re-converting every render.
const WORLD_COUNTRIES = feature(
  worldAtlas as unknown as Topology<{ countries: GeometryCollection }>,
  (worldAtlas as unknown as Topology<{ countries: GeometryCollection }>).objects.countries,
);

// ISO 3166-1 alpha-2 -> numeric, verified against this exact topojson file's
// `id` field (not just the ISO standard on paper) for every country this
// app actually operates in (BaNCS deployment footprint: India, Philippines,
// GCC, ANZ, SEA) plus the original seed-data set. Two real traps found
// during verification, worth keeping the comment on: this file's ids are
// the *zero-padded* ISO 3166-1 numeric string, and two of our target
// countries collide with an unpadded neighbor if you don't pad —
// '48' silently matches nothing (Bahrain is '048'), '36' matches a tiny
// Australian offshore territory instead of the mainland (Australia is
// '036'). If you add a country here, verify its id the same way — don't
// assume the numeric code you find on Wikipedia is formatted the same way
// world-atlas stores it.
//
// ponytail: covers only the countries above; extend (and re-verify) if the
// footprint grows further.
const ALPHA2_TO_NUMERIC: Record<string, string> = {
  // Original seed set
  AE: '784', US: '840', GB: '826', DE: '276', FR: '250', IT: '380', SA: '682', SG: '702',
  // India / Philippines
  IN: '356', PH: '608',
  // Rest of GCC (AE, SA already above)
  QA: '634', KW: '414', BH: '048', OM: '512',
  // ANZ
  AU: '036', NZ: '554',
  // Rest of SEA (SG already above)
  MY: '458', ID: '360', TH: '764', VN: '704',
};

/** BIC positions 5-6 are the ISO 3166-1 alpha-2 country code (SWIFT standard). */
function countryFromBic(bic: string): string | null {
  const cc = bic.slice(4, 6).toUpperCase();
  return /^[A-Z]{2}$/.test(cc) ? cc : null;
}

// world-atlas's MultiPolygon features bundle a country's overseas territories
// into the same geometry as the mainland — e.g. France's feature spans from
// French Guiana (-62°) to Réunion (+56°), crossing the equator. fitExtent()
// fits to the *geometric* bounds of whatever it's given, so including those
// rings made "zoom to fit" barely different from the full-world view: it was
// dutifully framing a box that stretched from South America to the Indian
// Ocean just because France was one of the matched countries. This keeps
// only the largest ring (by spherical area) per feature for bounds-fitting
// purposes only — actual rendering below still uses the untouched feature,
// so overseas territories still render and color correctly, they just don't
// skew what "zoom to fit" considers the extent to frame.
function largestRing<T extends { geometry: any }>(f: T): T {
  if (f.geometry?.type !== 'MultiPolygon') return f;
  let best = f.geometry.coordinates[0];
  let bestArea = 0;
  for (const coords of f.geometry.coordinates) {
    const area = geoArea({ type: 'Polygon', coordinates: coords });
    if (area > bestArea) { bestArea = area; best = coords; }
  }
  return { ...f, geometry: { type: 'Polygon', coordinates: best } };
}

// Internal SVG coordinate space for the map. Chosen wide/flat to match this
// widget's actual rendered shape (a full-width, ~220px-tall dashboard row)
// instead of react-simple-maps' 800x600 default, which is why the map used
// to render small-and-centered with large empty gutters either side —
// the library was letterboxing a ~4:3 viewBox inside a much wider box.
// `preserveAspectRatio="xMidYMid slice"` below is the safety net for
// whatever the *actual* container aspect turns out to be at a given
// breakpoint (sidebar collapse, mobile stacking, etc.) — it crops instead
// of letterboxing, which is the right trade for a decorative choropleth
// (losing a sliver of Arctic/Antarctic is fine; empty gutters aren't).
const MAP_WIDTH = 1000;
const MAP_HEIGHT = 230;
const DEFAULT_SCALE = 148;
const DEFAULT_CENTER: [number, number] = [10, 12];

interface GeoExposureMapProps {
  bankShares: BankShare[];
  currency: string;
  tooltipBg: string;
  tooltipText: string;
  tooltipShadow: string;
}

export const GeoExposureMap: React.FC<GeoExposureMapProps> = ({
  bankShares, currency, tooltipBg, tooltipText, tooltipShadow,
}) => {
  const [hover, setHover] = useState<{ name: string; amount: number; x: number; y: number } | null>(null);
  const [zoomToFit, setZoomToFit] = useState(false);
  const [showUnmapped, setShowUnmapped] = useState(false);

  const { byCountry, maxAmount, unmapped } = useMemo(() => {
    const byCountry = new Map<string, number>();
    // Was a Set<string> (code only) — now a Map so the "N countries not
    // shown" indicator can actually say how much is missing, not just that
    // something is.
    const unmapped = new Map<string, number>();
    for (const share of bankShares) {
      const alpha2 = countryFromBic(share.bankBic);
      if (!alpha2) continue;
      if (!ALPHA2_TO_NUMERIC[alpha2]) {
        unmapped.set(alpha2, (unmapped.get(alpha2) ?? 0) + share.amount);
        continue;
      }
      byCountry.set(alpha2, (byCountry.get(alpha2) ?? 0) + share.amount);
    }
    const maxAmount = Math.max(0, ...byCountry.values());
    return { byCountry, maxAmount, unmapped };
  }, [bankShares]);

  // Flag gaps instead of silently mis-coloring — a real BIC country this
  // app doesn't yet have a numeric-code mapping for should be visible to a
  // developer, not invisibly dropped from the map. (The on-screen
  // indicator below covers the end-user side of this; this warning is for
  // whoever's watching the console when a genuinely new country shows up.)
  if (unmapped.size > 0) {
    // eslint-disable-next-line no-console
    console.warn(`GeoExposureMap: no ISO numeric mapping for country code(s): ${[...unmapped.keys()].join(', ')} — add to ALPHA2_TO_NUMERIC.`);
  }

  const numericToAmount = new Map<string, number>();
  for (const [alpha2, amount] of byCountry) {
    numericToAmount.set(ALPHA2_TO_NUMERIC[alpha2], amount);
  }

  const hasData = numericToAmount.size > 0;

  // Zoom-to-fit projection: only built when toggled on and there's
  // something to fit — d3-geo's fitExtent computes translate+scale to
  // frame exactly the countries holding a balance, regardless of how
  // spread out or clustered they are. Falls back to the fixed default
  // projection (full world) otherwise.
  const zoomProjection = useMemo(() => {
    if (!zoomToFit || !hasData) return null;
    const matchingFeatures = (WORLD_COUNTRIES as any).features
      .filter((f: any) => numericToAmount.has(String(f.id)))
      .map(largestRing);
    if (matchingFeatures.length === 0) return null;
    const padding = 20;
    return geoEqualEarth().fitExtent(
      [[padding, padding], [MAP_WIDTH - padding, MAP_HEIGHT - padding]],
      { type: 'FeatureCollection', features: matchingFeatures } as any,
    );
    // numericToAmount is derived fresh each render from byCountry/ALPHA2_TO_NUMERIC;
    // keying off byCountry (stable across re-renders unless bankShares changes)
    // avoids rebuilding the projection every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zoomToFit, hasData, byCountry]);

  return (
    <div className="relative">
      <div className="relative h-[220px] overflow-hidden rounded-md">
        <ComposableMap
          width={MAP_WIDTH}
          height={MAP_HEIGHT}
          preserveAspectRatio="xMidYMid slice"
          projection={zoomProjection ?? 'geoEqualEarth'}
          projectionConfig={zoomProjection ? undefined : { scale: DEFAULT_SCALE, center: DEFAULT_CENTER }}
          className="w-full h-full"
        >
          <Geographies geography={WORLD_COUNTRIES}>
            {({ geographies }) =>
              geographies.map((geo) => {
                const amount = numericToAmount.get(String(geo.id));
                const intensity = amount && maxAmount > 0 ? 0.15 + 0.85 * (amount / maxAmount) : 0;
                return (
                  <Geography
                    key={geo.rsmKey}
                    geography={geo}
                    fill={intensity > 0 ? `rgba(25, 133, 161, ${intensity})` : 'var(--color-border, #dcdcdd)'}
                    stroke="var(--color-bg-page, #fff)"
                    strokeWidth={0.5}
                    style={{ outline: 'none' }}
                    onMouseEnter={(e) => {
                      if (amount == null) return;
                      setHover({ name: String(geo.properties?.name ?? ''), amount, x: e.clientX, y: e.clientY });
                    }}
                    onMouseMove={(e) => setHover((h) => (h ? { ...h, x: e.clientX, y: e.clientY } : h))}
                    onMouseLeave={() => setHover(null)}
                  />
                );
              })
            }
          </Geographies>
        </ComposableMap>

        {/* Full world / Zoom to fit toggle — only worth showing once
            there's actually something to zoom to; with zero colored
            countries there's nothing for "fit" to mean. */}
        {hasData && (
          <div className="absolute top-2 right-2 inline-flex rounded-sm border border-neutral-200 dark:border-primary-800 overflow-hidden bg-white/90 dark:bg-primary-950/90 backdrop-blur-sm">
            <button
              type="button"
              onClick={() => setZoomToFit(false)}
              className={`px-2 py-1 text-[11px] transition-colors ${
                !zoomToFit
                  ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-primary-950'
                  : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
              }`}
            >
              Full world
            </button>
            <button
              type="button"
              onClick={() => setZoomToFit(true)}
              className={`px-2 py-1 text-[11px] transition-colors ${
                zoomToFit
                  ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-primary-950'
                  : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
              }`}
            >
              Zoom to fit
            </button>
          </div>
        )}

        {hover && (
          <div
            className="fixed z-10 pointer-events-none rounded-xl px-3.5 py-2.5 text-sm"
            style={{ left: hover.x + 12, top: hover.y + 12, backgroundColor: tooltipBg, color: tooltipText, boxShadow: tooltipShadow }}
          >
            <p className="font-semibold">{hover.name}</p>
            <p>{formatCurrency(hover.amount, currency)}</p>
          </div>
        )}
      </div>

      {/* "N countries not shown" — a real data-trust gap, not a cosmetic
          one: money at banks in a country we don't yet have a coloring
          mapping for is silently absent from the map above with nothing
          on-screen saying so. This makes the absence visible and, on
          click, exactly how much and where. */}
      {unmapped.size > 0 && (
        <div className="relative mt-1.5 inline-block">
          <button
            type="button"
            onClick={() => setShowUnmapped((v) => !v)}
            onMouseEnter={() => setShowUnmapped(true)}
            className="caption text-warning-700 dark:text-warning-400 underline decoration-dotted underline-offset-2 hover:decoration-solid"
          >
            {unmapped.size} {unmapped.size === 1 ? 'country' : 'countries'} not shown on map
          </button>
          {showUnmapped && (
            <div
              onMouseLeave={() => setShowUnmapped(false)}
              className="absolute z-10 left-0 top-full mt-1 min-w-[220px] rounded-xl px-3.5 py-2.5 text-sm"
              style={{ backgroundColor: tooltipBg, color: tooltipText, boxShadow: tooltipShadow }}
            >
              <p className="font-semibold mb-1">Not shown on map</p>
              {[...unmapped.entries()]
                .sort((a, b) => b[1] - a[1])
                .map(([alpha2, amount]) => (
                  <div key={alpha2} className="flex items-center justify-between gap-3 py-0.5">
                    <span>{alpha2}</span>
                    <span>{formatCurrency(amount, currency)}</span>
                  </div>
                ))}
              <p className="caption mt-1.5 opacity-75">Country coloring not yet configured for these BICs.</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
