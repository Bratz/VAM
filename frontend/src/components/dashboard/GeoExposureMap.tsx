import React, { useMemo, useState } from 'react';
import { ComposableMap, Geographies, Geography } from 'react-simple-maps';
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
// app's seed data actually uses banks in. ponytail: covers only seeded
// countries; extend this map (and re-verify the numeric id against
// world-atlas's countries-50m.json) if new ones are added to seed data.
const ALPHA2_TO_NUMERIC: Record<string, string> = {
  AE: '784', US: '840', GB: '826', DE: '276', FR: '250', IT: '380', SA: '682', SG: '702',
};

/** BIC positions 5-6 are the ISO 3166-1 alpha-2 country code (SWIFT standard). */
function countryFromBic(bic: string): string | null {
  const cc = bic.slice(4, 6).toUpperCase();
  return /^[A-Z]{2}$/.test(cc) ? cc : null;
}

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

  const { byCountry, maxAmount, unmapped } = useMemo(() => {
    const byCountry = new Map<string, number>();
    const unmapped = new Set<string>();
    for (const share of bankShares) {
      const alpha2 = countryFromBic(share.bankBic);
      if (!alpha2) continue;
      if (!ALPHA2_TO_NUMERIC[alpha2]) { unmapped.add(alpha2); continue; }
      byCountry.set(alpha2, (byCountry.get(alpha2) ?? 0) + share.amount);
    }
    const maxAmount = Math.max(0, ...byCountry.values());
    return { byCountry, maxAmount, unmapped };
  }, [bankShares]);

  // Flag gaps instead of silently mis-coloring — a real BIC country this
  // app doesn't yet have a numeric-code mapping for should be visible to a
  // developer, not invisibly dropped from the map.
  if (unmapped.size > 0) {
    // eslint-disable-next-line no-console
    console.warn(`GeoExposureMap: no ISO numeric mapping for country code(s): ${[...unmapped].join(', ')} — add to ALPHA2_TO_NUMERIC.`);
  }

  const numericToAmount = new Map<string, number>();
  for (const [alpha2, amount] of byCountry) {
    numericToAmount.set(ALPHA2_TO_NUMERIC[alpha2], amount);
  }

  return (
    <div className="relative h-[220px]">
      <ComposableMap projectionConfig={{ scale: 118, center: [10, 12] }} className="w-full h-full">
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
  );
};
