// The FX hedges of one invoice (fx-hedge-links R7), for the Payables and Receivables lists and their detail modals.

import React from 'react';
import { ShieldCheck } from 'lucide-react';
import { Badge } from '../ui';
import { formatCurrency } from '../../utils';
import type { HedgeLink } from '../../services/fxDealApi';
import { hedgeShortfall } from '../../pages/fxDealBadges';

/** "Hedged" with the deal reference(s), when a deal still hedges part of the invoice; nothing otherwise. */
export const HedgedMarker: React.FC<{ links?: HedgeLink[] }> = ({ links }) => {
  const refs = [...new Set((links ?? []).filter((l) => l.effective > 0).map((l) => l.dealRef ?? 'FX deal'))];
  if (!refs.length) return null;
  return (
    <span className="inline-flex flex-col gap-0.5">
      <Badge variant="info" size="sm" className="gap-1"><ShieldCheck className="w-3 h-3" /> Hedged</Badge>
      <span className="caption font-mono">{refs.join(', ')}</span>
    </span>
  );
};

/** The deals linked to the invoice: what each still hedges, and why it is less than linked. For a 2-column grid. */
export const InvoiceHedgeList: React.FC<{ links?: HedgeLink[] }> = ({ links }) => {
  if (!links?.length) return null;
  return (
    <div className="col-span-2">
      <p className="text-neutral-500 dark:text-neutral-400">FX hedges</p>
      <ul className="mt-1 space-y-1">
        {links.map((l) => {
          const short = hedgeShortfall(l);
          return (
            <li key={l.id}>
              <div className="flex items-center justify-between gap-2">
                <span className="font-mono">{l.dealRef}</span>
                <span className="font-mono tabular-nums">
                  {formatCurrency(l.effective, l.currency)}
                  {l.effective < l.linked && <span className="caption"> of {formatCurrency(l.linked, l.currency)} linked</span>}
                </span>
              </div>
              {short && <p className="caption">{short}</p>}
            </li>
          );
        })}
      </ul>
    </div>
  );
};
