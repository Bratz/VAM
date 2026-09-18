import React, { useMemo, useState } from 'react';
import { ComposableMap, Geographies, Geography, Marker } from 'react-simple-maps';
import { geoCentroid } from 'd3-geo';
import { WORLD_COUNTRIES, ALPHA2_TO_NUMERIC, largestRing } from '../dashboard/worldGeo';
import { useTheme } from '../../design-system/ThemeProvider';
import { formatCurrency } from '../../utils';

// ============================================================================
// Multi-Bank Liquidity — By Country world map.
//
// Two layers on the same shared geography as the Dashboard's GeoExposureMap
// (see components/dashboard/worldGeo.ts): a choropleth base (country shaded
// by intensity, same technique as the Dashboard) plus bubble markers sized
// by converted balance — a genuinely new treatment, not just a reskin, since
// no bubble/marker map exists anywhere else in the app.
//
// Unlike GeoExposureMap (which infers country from a bank's BIC — a proxy),
// this one is keyed directly off ShadowSummary.owningEntityCountry, already
// a real alpha-2 code, so it's more accurate for this data.
// ============================================================================

const MAP_WIDTH = 1000;
const MAP_HEIGHT = 230;
const DEFAULT_SCALE = 148;
const DEFAULT_CENTER: [number, number] = [10, 12];
const MIN_RADIUS = 4;
const MAX_RADIUS = 24;

interface CountryExposureMapProps {
  /** Alpha-2 country code -> already-converted balance in `currency`. */
  countryTotals: { code: string; amount: number }[];
  currency: string;
}

export const CountryExposureMap: React.FC<CountryExposureMapProps> = ({ countryTotals, currency }) => {
  const { resolvedMode } = useTheme();
  const isDark = resolvedMode === 'dark';
  const tooltipBg = isDark ? '#343638' : '#ffffff';
  const tooltipText = isDark ? '#f2f2f3' : '#46494c';
  const tooltipShadow = '0 4px 12px rgba(70,73,76,0.15)';

  const [hover, setHover] = useState<{ name: string; amount: number; x: number; y: number } | null>(null);

  const { numericToAmount, unmapped, maxAmount } = useMemo(() => {
    const numericToAmount = new Map<string, number>();
    const unmapped = new Map<string, number>();
    for (const { code, amount } of countryTotals) {
      const numeric = ALPHA2_TO_NUMERIC[code];
      if (!numeric) {
        unmapped.set(code, (unmapped.get(code) ?? 0) + amount);
        continue;
      }
      numericToAmount.set(numeric, (numericToAmount.get(numeric) ?? 0) + amount);
    }
    const maxAmount = Math.max(0, ...numericToAmount.values());
    return { numericToAmount, unmapped, maxAmount };
  }, [countryTotals]);

  if (unmapped.size > 0) {
    // eslint-disable-next-line no-console
    console.warn(`CountryExposureMap: no ISO numeric mapping for country code(s): ${[...unmapped.keys()].join(', ')} — add to ALPHA2_TO_NUMERIC.`);
  }

  const hasData = numericToAmount.size > 0;

  // Bubble centroids — computed once per data change, using the largest ring
  // per feature so a territoried country's bubble lands on its mainland, not
  // pulled toward an overseas territory (same rationale as GeoExposureMap's
  // zoom-to-fit bounds).
  type Bubble = { id: string; name: string; coordinates: [number, number]; amount: number; radius: number };
  const bubbles = useMemo<Bubble[]>(() => {
    if (!hasData) return [];
    return (WORLD_COUNTRIES as any).features
      .filter((f: any) => numericToAmount.has(String(f.id)))
      .map((f: any) => {
        const amount = numericToAmount.get(String(f.id))!;
        const [lng, lat] = geoCentroid(largestRing(f));
        const radius = MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * Math.sqrt(amount / maxAmount);
        return { id: f.id, name: f.properties?.name ?? '', coordinates: [lng, lat] as [number, number], amount, radius };
      });
  }, [hasData, numericToAmount, maxAmount]);

  return (
    <div className="relative">
      <div className="relative h-[220px] overflow-hidden rounded-md">
        <ComposableMap
          width={MAP_WIDTH}
          height={MAP_HEIGHT}
          preserveAspectRatio="xMidYMid slice"
          projection="geoEqualEarth"
          projectionConfig={{ scale: DEFAULT_SCALE, center: DEFAULT_CENTER }}
          className="w-full h-full"
        >
          <Geographies geography={WORLD_COUNTRIES}>
            {({ geographies }) =>
              geographies.map((geo) => {
                const amount = numericToAmount.get(String(geo.id));
                const intensity = amount && maxAmount > 0 ? 0.12 + 0.6 * (amount / maxAmount) : 0;
                return (
                  <Geography
                    key={geo.rsmKey}
                    geography={geo}
                    fill={intensity > 0 ? `rgba(25, 133, 161, ${intensity})` : 'var(--color-border, #dcdcdd)'}
                    stroke="var(--color-bg-page, #fff)"
                    strokeWidth={0.5}
                    style={{ outline: 'none' }}
                  />
                );
              })
            }
          </Geographies>

          {bubbles.map((b) => (
            <Marker key={b.id} coordinates={b.coordinates}>
              <circle
                r={b.radius}
                fill="rgba(212, 165, 61, 0.55)"
                stroke="rgba(163, 120, 30, 0.9)"
                strokeWidth={1}
                onMouseEnter={(e) => setHover({ name: b.name, amount: b.amount, x: e.clientX, y: e.clientY })}
                onMouseMove={(e) => setHover((h) => (h ? { ...h, x: e.clientX, y: e.clientY } : h))}
                onMouseLeave={() => setHover(null)}
              />
            </Marker>
          ))}
        </ComposableMap>

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

      {unmapped.size > 0 && (
        <p className="caption text-warning-700 dark:text-warning-400 mt-1.5">
          {unmapped.size} {unmapped.size === 1 ? 'country' : 'countries'} not shown on map (coloring not yet configured for {[...unmapped.keys()].join(', ')}).
        </p>
      )}
    </div>
  );
};
