// ============================================================================
// PayInvoicePage — the public "pay this invoice" page a debtor lands on after
// clicking a payment link from outside the app (email/SMS). Rendered without
// the internal Layout chrome (see App.tsx's isFullScreenPage) since this page
// is meant for an external payer, not a logged-in treasury user. Fetches from
// PublicReceivablesController, an unauthenticated, read-only endpoint that
// deliberately returns only payer-facing fields — no customer/internal IDs.
//
// This is the "basic minimum" version: a real, hosted bank-transfer
// instruction page (real VIBAN, real amount) — not online card capture,
// which is a separate, larger scope left for later.
// ============================================================================

import React, { useEffect, useState } from 'react';
import { Check, Copy, AlertTriangle, Building2 } from 'lucide-react';
import QRCode from 'react-qr-code';
import { Card } from '../components/ui';

interface PublicInvoice {
  receivableNumber: string;
  corporateName: string | null;
  amount: number;
  outstandingAmount: number;
  currencyCode: string;
  dueDate: string | null;
  description: string | null;
  status: string;
  viban: string | null;
}

const PayInvoicePage: React.FC<{ token?: string }> = ({ token }) => {
  const [invoice, setInvoice] = useState<PublicInvoice | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!token) {
      setNotFound(true);
      setLoading(false);
      return;
    }
    fetch(`/api/v1/public/receivables/pay/${token}`)
      .then((res) => {
        if (!res.ok) throw new Error('not found');
        return res.json();
      })
      .then(setInvoice)
      .catch(() => setNotFound(true))
      .finally(() => setLoading(false));
  }, [token]);

  const copyViban = () => {
    if (!invoice?.viban) return;
    navigator.clipboard.writeText(invoice.viban);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="min-h-screen bg-surface-page flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        {loading ? (
          <Card padding="lg" className="text-center text-neutral-500 dark:text-neutral-400">
            Loading…
          </Card>
        ) : notFound || !invoice ? (
          <Card padding="lg" className="text-center">
            <AlertTriangle className="w-8 h-8 text-warning-500 dark:text-warning-300 mx-auto mb-3" />
            <p className="body-strong">
              This payment link is invalid or has expired
            </p>
          </Card>
        ) : (
          <Card padding="lg">
            <div className="flex items-center gap-2 mb-1 text-neutral-500 dark:text-neutral-400">
              <Building2 className="w-4 h-4" />
              <span className="text-body-sm">{invoice.corporateName || 'Invoice'}</span>
            </div>
            <p className="caption mb-4">{invoice.receivableNumber}</p>

            <p className="text-heading-lg font-semibold text-primary-900 dark:text-neutral-50">
              {invoice.currencyCode} {invoice.outstandingAmount.toFixed(2)}
            </p>
            {invoice.description && (
              <p className="body-sm mt-1">{invoice.description}</p>
            )}
            {invoice.dueDate && (
              <p className="caption mt-1">Due {invoice.dueDate}</p>
            )}

            {invoice.viban ? (
              <div className="mt-6 p-4 bg-accent-50 dark:bg-accent-500/10 rounded-lg border border-accent-100 dark:border-accent-500/30">
                <p className="caption mb-1">
                  Transfer the amount above to this account, quoting {invoice.receivableNumber} as the reference
                </p>
                <div className="flex items-center justify-between gap-2">
                  <code className="text-body-sm font-mono text-primary-900 dark:text-neutral-50 break-all">{invoice.viban}</code>
                  <button onClick={copyViban} className="p-2 hover:bg-accent-100 dark:hover:bg-accent-500/20 rounded-lg flex-shrink-0" title="Copy account number">
                    {copied ? <Check className="w-4 h-4 text-success-600 dark:text-success-300" /> : <Copy className="w-4 h-4 text-accent-600 dark:text-accent-300" />}
                  </button>
                </div>
                <div className="mt-4 flex flex-col items-center gap-2 pt-4 border-t border-accent-100 dark:border-accent-500/30">
                  <div className="bg-white p-2 rounded-lg">
                    <QRCode value={window.location.href} size={128} />
                  </div>
                  <p className="caption">Scan to open this page on another device</p>
                </div>
              </div>
            ) : (
              <p className="mt-6 body-sm">
                No collection account is set up for this invoice yet — please contact {invoice.corporateName || 'the sender'} directly.
              </p>
            )}
          </Card>
        )}
      </div>
    </div>
  );
};

export default PayInvoicePage;
