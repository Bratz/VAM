import { useEffect, useState } from 'react';
import { fxDealApi, type HedgeKind, type HedgeLink } from '../services/fxDealApi';

/**
 * The FX hedge links of a list's invoices, by invoice id (fx-hedge-links R7): the Payables and Receivables lists mark
 * the hedged ones. Intercompany invoices cannot be hedged, so they are not asked about; the server leaves out the
 * ones without links (home-currency invoices never have any). `invoices` should be the loaded list itself (state),
 * so the links are read again each time the list reloads, not on every render.
 */
export function useInvoiceHedges(corporateId: string | undefined, kind: HedgeKind,
                                 invoices: { id: string; isIntercompany?: boolean }[]): Record<string, HedgeLink[]> {
  const [hedges, setHedges] = useState<Record<string, HedgeLink[]>>({});
  useEffect(() => {
    setHedges({});
    const ids = invoices.filter((i) => !i.isIntercompany).map((i) => i.id);
    if (!corporateId || !ids.length) return;
    let live = true;
    // ponytail: 100 ids per call keeps the query string short; a filtered tab of 1,000 rows is 10 calls
    const chunks: string[][] = [];
    for (let i = 0; i < ids.length; i += 100) chunks.push(ids.slice(i, i + 100));
    Promise.all(chunks.map((c) => fxDealApi.invoiceHedges(corporateId, kind, c)))
      .then((parts) => { if (live) setHedges(Object.assign({}, ...parts)); })
      .catch(() => undefined); // the marker is extra: the list works without it
    return () => { live = false; };
  }, [corporateId, kind, invoices]);
  return hedges;
}
