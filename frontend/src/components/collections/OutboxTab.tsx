import React, { useCallback, useEffect, useState } from 'react';
import { Mail, RefreshCw } from 'lucide-react';
import QRCode from 'react-qr-code';
import { Badge, Button, DataTable } from '../ui';
import { Modal } from '../ui/enhanced';
import { outboxApi, OutboxEmail } from '../../services/collectionsApi';
import { errorText } from '../../pages/fx/fxFormat';

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** One email: who it went to, what it said, and the QR of the link it carries. */
export const OutboxEmailView: React.FC<{ email: OutboxEmail }> = ({ email }) => (
  <div className="space-y-3">
    <div className="text-body-sm space-y-0.5">
      <p><span className="text-neutral-500 dark:text-neutral-400">To </span>
        {email.toAddress ? `${email.toName ?? ''} <${email.toAddress}>` : <span className="text-warning-700 dark:text-warning-300">no email address on {email.toName ?? 'the customer'} — not sent</span>}
      </p>
      <p><span className="text-neutral-500 dark:text-neutral-400">Subject </span>{email.subject}</p>
      <p className="text-caption text-neutral-500 dark:text-neutral-400">{when(email.createdAt)}</p>
    </div>
    <pre className="whitespace-pre-wrap break-words text-body-sm font-sans bg-surface-page rounded-lg p-3 border border-neutral-200 dark:border-neutral-700">{email.body}</pre>
    {email.link && (
      <div className="flex items-center gap-3">
        <div className="p-2 bg-white rounded-lg border border-neutral-200 shrink-0"><QRCode value={email.link} size={96} /></div>
        <p className="text-caption text-neutral-500 dark:text-neutral-400">The pay link as a QR code, for the email or a printed invoice.</p>
      </div>
    )}
  </div>
);

/**
 * The simulated outbox: every email the platform would have sent for this corporate (Requests to Pay so far).
 * There is no mail server, so nothing leaves the platform; this is what would have.
 */
export const OutboxTab: React.FC<{ corporateId: string }> = ({ corporateId }) => {
  const [emails, setEmails] = useState<OutboxEmail[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<OutboxEmail | null>(null);

  const load = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    setError(null);
    try {
      setEmails(await outboxApi.forCorporate(corporateId));
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <p className="text-body-sm text-neutral-500 dark:text-neutral-400">
          Emails the platform would have sent. There is no mail server connected, so they are kept here instead of being delivered.
        </p>
        <Button size="sm" variant="outline" onClick={load} leftIcon={<RefreshCw className="w-4 h-4" />}>Refresh</Button>
      </div>
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
      <DataTable
        data={emails}
        loading={loading}
        keyExtractor={(e) => e.id}
        onRowClick={(e) => setOpen(e)}
        emptyIcon={<Mail className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
        emptyTitle={corporateId ? 'No emails yet' : 'Choose a corporate'}
        emptyDescription={corporateId ? 'A Request to Pay puts its email here.' : undefined}
        columns={[
          { key: 'createdAt', header: 'When', render: (_, e) => <span className="text-body-sm whitespace-nowrap">{when(e.createdAt)}</span> },
          {
            key: 'toAddress', header: 'To',
            render: (_, e) => (
              <div className="text-body-sm">
                <p className="body-strong">{e.toName ?? '—'}</p>
                <p className="text-caption text-neutral-500 dark:text-neutral-400">{e.toAddress ?? 'no email address'}</p>
              </div>
            ),
          },
          { key: 'subject', header: 'Subject', render: (_, e) => <span className="text-body-sm">{e.subject}</span> },
          {
            key: 'status', header: 'Status', align: 'center',
            render: (_, e) => e.status === 'SENT'
              ? <Badge variant="success" size="sm">Sent (simulated)</Badge>
              : <Badge variant="warning" size="sm">No address</Badge>,
          },
        ]}
      />
      <Modal isOpen={!!open} onClose={() => setOpen(null)} title={open?.relatedRef ? `Email · ${open.relatedRef}` : 'Email'} size="lg"
        footer={<Button variant="ghost" onClick={() => setOpen(null)}>Close</Button>}>
        {open && <OutboxEmailView email={open} />}
      </Modal>
    </div>
  );
};

export default OutboxTab;
