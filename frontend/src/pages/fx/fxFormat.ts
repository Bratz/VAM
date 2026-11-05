// Formatting and small helpers shared by the Foreign Exchange pages.

export const fmt = (n: number, dp = 2) => n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
export const fmtSigned = (n: number, dp = 2) => `${n > 0 ? '+' : n < 0 ? '-' : ''}${fmt(Math.abs(n), dp)}`;
export const rateDigits = (n: number) => (n >= 50 ? 4 : 6);
export const fmtRate = (n: number) => n.toFixed(rateDigits(n));
export const when = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '-');

export const toneFor = (n: number) =>
  n > 0 ? 'text-success-700 dark:text-success-300' : n < 0 ? 'text-error-700 dark:text-error-300' : 'text-neutral-500 dark:text-neutral-400';

export const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string; error?: string } }; message?: string };
  return e?.response?.data?.message || e?.response?.data?.error || e?.message || 'Request failed';
};

/** Downloads rows as a CSV file. Values are quoted, so commas and quotes in text are safe. */
export const downloadCsv = (filename: string, header: string[], rows: (string | number | undefined)[][]) => {
  const cell = (v: string | number | undefined) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const csv = [header, ...rows].map((r) => r.map(cell).join(',')).join('\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
};
