import React, { useMemo, useState } from 'react';
import { ComposableMap, Geographies, Geography } from 'react-simple-maps';
import { geoEqualEarth } from 'd3-geo';
import { BankShare } from '../multiBank/BankSplitBar';
import { formatCurrency } from '../../utils';
import { WORLD_COUNTRIES, ALPHA2_TO_NUMERIC, largestRing } from './worldGeo';

/** BIC positions 5-6 are the ISO 3166-1 alpha-2 country code (SWIFT standard). */
function countryFromBic(bic: string): string | null {
  const cc = bic.slice(4, 6).toUpperCase();
  return /^[A-Z]{2}$/.test(cc) ? cc : null;
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
              className={`px-2 py-1 text-caption transition-colors ${
                !zoomToFit
                  ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-white'
                  : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
              }`}
            >
              Full world
            </button>
            <button
              type="button"
              onClick={() => setZoomToFit(true)}
              className={`px-2 py-1 text-caption transition-colors ${
                zoomToFit
                  ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-white'
                  : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
              }`}
            >
              Zoom to fit
            </button>
          </div>
        )}

        {hover && (
          <div
            className="fixed z-10 pointer-events-none rounded-lg px-3.5 py-2.5 text-body-sm"
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
              className="absolute z-10 left-0 top-full mt-1 min-w-[220px] rounded-lg px-3.5 py-2.5 text-body-sm"
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
