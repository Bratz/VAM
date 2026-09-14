import React from 'react';
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, ReferenceLine } from 'recharts';
import { SubsidiaryIntercompanyPosition } from '../../services/api';
import { formatCurrency } from '../../utils';

interface IntercompanyPositionChartProps {
  positions: SubsidiaryIntercompanyPosition[];
  loading: boolean;
  receivableFill: string;
  payableFill: string;
  tickFill: string;
  tooltipBg: string;
  tooltipText: string;
  tooltipShadow: string;
}

/**
 * IC Receivable (POBO — subsidiary owes Treasury) and IC Payable (COBO —
 * Treasury owes subsidiary) per subsidiary, as a diverging bar from a zero
 * baseline: receivable extends right, payable extends left (stored negative
 * so recharts' stacking naturally diverges both bars from the same origin).
 * Sourced from the real VA ledger (see getIntercompanyPositions on the
 * backend) — the same balances settleBilateralPosition() itself nets, not a
 * separate recomputation that could drift from what "Settle" actually does.
 */
export const IntercompanyPositionChart: React.FC<IntercompanyPositionChartProps> = ({
  positions, loading, receivableFill, payableFill, tickFill, tooltipBg, tooltipText, tooltipShadow,
}) => {
  if (!loading && positions.length === 0) {
    return <p className="body-sm py-6 text-center">No outstanding intercompany positions for this corporate.</p>;
  }

  const data = positions.map((p) => ({
    name: p.subsidiaryEntityCode,
    fullName: p.subsidiaryEntityName,
    currency: p.currencyCode,
    receivable: p.receivableBalance,
    payable: -p.payableBalance,
    net: p.netPosition,
  }));

  return (
    <div style={{ height: Math.max(data.length * 36, 90) }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 4, left: 4 }}>
          <XAxis type="number" hide />
          <YAxis
            type="category"
            dataKey="name"
            width={72}
            tickLine={false}
            axisLine={false}
            tick={{ fontSize: 12, fill: tickFill, fontFamily: 'var(--font-mono)' }}
          />
          <ReferenceLine x={0} stroke={tickFill} strokeOpacity={0.3} />
          <Tooltip
            cursor={{ fill: tickFill, opacity: 0.06 }}
            contentStyle={{
              backgroundColor: tooltipBg,
              border: 'none',
              borderRadius: '12px',
              boxShadow: tooltipShadow,
              padding: '10px 14px',
              color: tooltipText,
            }}
            labelStyle={{ color: tooltipText, fontWeight: 600 }}
            formatter={(_value: number, key: string, item: any) => {
              const p = item.payload as (typeof data)[number];
              if (key === 'receivable') return [formatCurrency(p.receivable, p.currency), 'IC Receivable (owes Treasury)'];
              return [formatCurrency(-p.payable, p.currency), 'IC Payable (Treasury owes)'];
            }}
            labelFormatter={(name: string, item: any) => {
              const p = item?.[0]?.payload as (typeof data)[number] | undefined;
              return p ? `${p.fullName} — net ${formatCurrency(p.net, p.currency)}` : name;
            }}
          />
          <Bar dataKey="receivable" stackId="ic" fill={receivableFill} radius={[4, 4, 4, 4]} barSize={16} isAnimationActive={false} />
          <Bar dataKey="payable" stackId="ic" fill={payableFill} radius={[4, 4, 4, 4]} barSize={16} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
};
