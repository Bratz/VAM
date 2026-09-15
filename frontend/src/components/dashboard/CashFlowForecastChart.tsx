import React from 'react';
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, ReferenceLine } from 'recharts';
import { formatCurrency } from '../../utils';

export interface CashFlowWeek {
  weekStart: string;
  weekEnd: string;
  receivables: number;
  payables: number;
}

interface CashFlowForecastChartProps {
  weeklyData: CashFlowWeek[];
  loading: boolean;
  hasCorporate: boolean;
  currency: string;
  receivableFill: string;
  payableFill: string;
  tickFill: string;
  tooltipBg: string;
  tooltipText: string;
  tooltipShadow: string;
}

/**
 * Weekly Receivables (AR_COLLECTIONS, projected via aging) vs Payables
 * (AP_DISBURSEMENTS, projected via aging) from the cash forecast engine —
 * a diverging bar per week from a zero baseline, mirroring
 * IntercompanyPositionChart's stacked-negative-series pattern, rotated into
 * a time series (weeks on X, not subsidiaries on Y).
 */
export const CashFlowForecastChart: React.FC<CashFlowForecastChartProps> = ({
  weeklyData, loading, hasCorporate, currency, receivableFill, payableFill, tickFill, tooltipBg, tooltipText, tooltipShadow,
}) => {
  if (!hasCorporate) {
    return <p className="body-sm py-6 text-center">Select a corporate to see its cash flow forecast.</p>;
  }
  if (!loading && weeklyData.length === 0) {
    return <p className="body-sm py-6 text-center">No forecast data for this corporate.</p>;
  }

  const data = weeklyData.map((w) => ({
    label: new Date(w.weekStart).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
    weekStart: w.weekStart,
    weekEnd: w.weekEnd,
    receivables: w.receivables,
    payables: -w.payables,
  }));

  return (
    <div style={{ height: 180 }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 4, right: 8, bottom: 4, left: 8 }}>
          <XAxis
            dataKey="label"
            tickLine={false}
            axisLine={false}
            tick={{ fontSize: 11, fill: tickFill, fontFamily: 'var(--font-mono)' }}
          />
          <YAxis type="number" hide />
          <ReferenceLine y={0} stroke={tickFill} strokeOpacity={0.3} />
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
              const w = item.payload as (typeof data)[number];
              if (key === 'receivables') return [formatCurrency(w.receivables, currency), 'Receivables'];
              return [formatCurrency(-w.payables, currency), 'Payables'];
            }}
            labelFormatter={(_label: string, item: any) => {
              const w = item?.[0]?.payload as (typeof data)[number] | undefined;
              return w ? `Week of ${w.label}` : _label;
            }}
          />
          <Bar dataKey="receivables" stackId="cashflow" fill={receivableFill} radius={[3, 3, 0, 0]} isAnimationActive={false} />
          <Bar dataKey="payables" stackId="cashflow" fill={payableFill} radius={[0, 0, 3, 3]} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
};
