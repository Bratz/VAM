import React, { useEffect, useMemo, useState } from 'react';
import { ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip, Legend, CartesianGrid } from 'recharts';
import { formatCurrency } from '../../utils';
import { multiBankLiquidityApi, TrendPoint } from '../../services/api';
import { useTheme } from '../../design-system/ThemeProvider';
import { Card } from '../ui';

// ============================================================================
// Multi-Bank Liquidity — historical trend.
//
// One line per currency (never a single blended line — same FX-honesty rule
// as the rest of this page) of daily effective bank balance, from
// shadow_balance_snapshot rows captured by BalanceRefreshService on every
// successful refresh (plus 90 days of seeded demo history from V18).
//
// Unlike the other views, this one owns its own fetch: the page's load()
// only fetches the summary, and fetching 90 days of trend data on every
// summary reload (including while the user is on Overview/By Bank/etc.)
// would be wasted work. Trend data is fetched here, on demand, only while
// this tab is mounted.
// ============================================================================

const PALETTE_LIGHT = ['#146b80', '#276a54', '#8a5f14', '#a8443c', '#1d4ed8', '#5d6165', '#9333ea', '#0d9488'];
const PALETTE_DARK = ['#81bccb', '#7da698', '#b99f72', '#cb8f8a', '#60a5fa', '#b5b6b7', '#c084fc', '#5eead4'];

interface TrendViewProps {
  corporateId?: string;
}

export const TrendView: React.FC<TrendViewProps> = ({ corporateId }) => {
  const { resolvedMode } = useTheme();
  const isDark = resolvedMode === 'dark';
  const palette = isDark ? PALETTE_DARK : PALETTE_LIGHT;
  const tickFill = isDark ? '#b5b6b7' : '#5d6165';
  const tooltipBg = isDark ? '#343638' : '#ffffff';
  const tooltipText = isDark ? '#f2f2f3' : '#46494c';

  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    multiBankLiquidityApi.getTrend(corporateId || undefined, 90)
      .then((res) => { if (alive) setTrend(res.success && Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (alive) setTrend([]); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [corporateId]);

  const { rows, currencies } = useMemo(() => {
    const currencySet = new Set<string>();
    const byDate = new Map<string, Record<string, number | string>>();
    for (const p of trend) {
      currencySet.add(p.currencyCode);
      const row = byDate.get(p.asOf) ?? { asOf: p.asOf };
      row[p.currencyCode] = p.totalEffective;
      byDate.set(p.asOf, row);
    }
    const sortedCurrencies = Array.from(currencySet).sort();
    const sortedRows = Array.from(byDate.values()).sort((a, b) => (a.asOf as string).localeCompare(b.asOf as string));
    return { rows: sortedRows, currencies: sortedCurrencies };
  }, [trend]);

  if (!loading && rows.length === 0) {
    return (
      <Card padding="md" className="text-center text-neutral-500 dark:text-neutral-400">
        No trend history yet — snapshots accumulate as shadow balances are refreshed.
      </Card>
    );
  }

  return (
    <Card padding="sm">
      <p className="label mb-3">Daily effective balance by currency (90 days)</p>
      <div style={{ height: 360 }}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows} margin={{ top: 8, right: 16, bottom: 4, left: 8 }}>
            <CartesianGrid stroke={tickFill} strokeOpacity={0.1} vertical={false} />
            <XAxis
              dataKey="asOf"
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: tickFill, fontFamily: 'var(--font-mono)' }}
              tickFormatter={(v: string) => new Date(v).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
              minTickGap={24}
            />
            <YAxis type="number" hide />
            <Tooltip
              contentStyle={{
                backgroundColor: tooltipBg,
                border: 'none',
                borderRadius: '12px',
                padding: '10px 14px',
                color: tooltipText,
              }}
              labelFormatter={(v: string) => new Date(v).toLocaleDateString()}
              formatter={(value: number, name: string) => [formatCurrency(value, name), name]}
            />
            <Legend wrapperStyle={{ fontSize: 12, color: tickFill }} />
            {currencies.map((code, i) => (
              <Line
                key={code}
                type="monotone"
                dataKey={code}
                name={code}
                stroke={palette[i % palette.length]}
                strokeWidth={2}
                dot={false}
                connectNulls
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </Card>
  );
};
