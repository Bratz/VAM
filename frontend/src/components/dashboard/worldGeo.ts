import { geoArea } from 'd3-geo';
import { feature } from 'topojson-client';
import worldAtlas from 'world-atlas/countries-50m.json';
import type { Topology, GeometryCollection } from 'topojson-specification';

// ============================================================================
// Shared world-geography plumbing for every choropleth/bubble map in this
// app. Extracted from GeoExposureMap.tsx (the Dashboard's map) so a second
// consumer (Multi-Bank Liquidity's By Country map) reuses this exact,
// already-verified data instead of forking it — the alpha-2→numeric table
// below has two real, hard-won gotchas documented on it; a fork risks losing
// that verification silently.
// ============================================================================

// react-simple-maps' Geographies expects real GeoJSON, not raw TopoJSON —
// convert once at module scope (world-atlas's file never changes at
// runtime) rather than re-converting every render.
export const WORLD_COUNTRIES = feature(
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
export const ALPHA2_TO_NUMERIC: Record<string, string> = {
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
export function largestRing<T extends { geometry: any }>(f: T): T {
  if (f.geometry?.type !== 'MultiPolygon') return f;
  let best = f.geometry.coordinates[0];
  let bestArea = 0;
  for (const coords of f.geometry.coordinates) {
    const area = geoArea({ type: 'Polygon', coordinates: coords });
    if (area > bestArea) { bestArea = area; best = coords; }
  }
  return { ...f, geometry: { type: 'Polygon', coordinates: best } };
}
