// ============================================================================
// CsvAccountUpload — dropzone-styled file input (pattern adapted from
// CreateReceivablePage's DocumentsTab: click-only despite dropzone styling,
// no real drag-and-drop) for bulk-resolving VA numbers into a candidate set.
//
// Parsing idiom reused from WalletPage's handleBulkLoad: split pasted/loaded
// text on newlines AND commas, trim, drop blanks — then dedupe.
// ============================================================================

import React, { useState } from 'react';
import { Upload, FileText, X, Loader2, AlertTriangle } from 'lucide-react';
import { apiClient } from '../../services/api';
import { Badge } from '../ui';

export interface ResolvedVaSummary {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  balance?: number;
}

interface CsvAccountUploadProps {
  onResolved: (matched: ResolvedVaSummary[], unmatched: string[], parsedCount: number) => void;
}

export const CsvAccountUpload: React.FC<CsvAccountUploadProps> = ({ onResolved }) => {
  const [fileName, setFileName] = useState<string | null>(null);
  const [parsedCount, setParsedCount] = useState(0);
  const [matchedCount, setMatchedCount] = useState<number | null>(null);
  const [unmatched, setUnmatched] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = () => {
    setFileName(null);
    setParsedCount(0);
    setMatchedCount(null);
    setUnmatched([]);
    setError(null);
  };

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file
    if (!file) return;
    reset();
    setFileName(file.name);

    const reader = new FileReader();
    reader.onload = async () => {
      const text = String(reader.result || '');
      // Same split-on-newlines-and-commas idiom as WalletPage's bulk load.
      const accountNumbers = Array.from(new Set(
        text.split(/[\n,]/).map(s => s.trim()).filter(Boolean)
      ));
      setParsedCount(accountNumbers.length);
      if (accountNumbers.length === 0) {
        setError('No account numbers found in file');
        return;
      }
      setLoading(true);
      try {
        const res = await apiClient.post('/virtual-accounts/resolve-by-numbers', { accountNumbers });
        const data = res.data?.data ?? { matched: [], unmatched: accountNumbers };
        // Backend confirmed: VirtualAccountDto.Summary — currentBalance/availableBalance,
        // not a generic `balance` field. Map explicitly rather than casting.
        const matched: ResolvedVaSummary[] = (data.matched ?? []).map((r: Record<string, any>) => ({
          id: r.id, vaNumber: r.vaNumber, vaName: r.vaName, currencyCode: r.currencyCode,
          balance: r.currentBalance ?? r.availableBalance,
        }));
        const notFound: string[] = data.unmatched ?? [];
        setMatchedCount(matched.length);
        setUnmatched(notFound);
        onResolved(matched, notFound, accountNumbers.length);
      } catch {
        setError('Failed to resolve account numbers');
        onResolved([], accountNumbers, accountNumbers.length);
      } finally {
        setLoading(false);
      }
    };
    reader.onerror = () => setError('Failed to read file');
    reader.readAsText(file);
  };

  return (
    <div className="space-y-3">
      <div className="border-2 border-dashed border-neutral-200 dark:border-primary-800 rounded-lg p-6 text-center hover:border-primary-300 transition-colors">
        <input
          type="file"
          accept=".csv,.txt"
          onChange={handleFile}
          className="hidden"
          id="csv-account-upload"
        />
        <label htmlFor="csv-account-upload" className="cursor-pointer">
          <Upload className="w-8 h-8 text-neutral-400 dark:text-neutral-500 mx-auto mb-2" />
          <p className="text-sm text-neutral-600 dark:text-neutral-300">Click to upload a CSV or TXT file</p>
          <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">One account number per line, or comma-separated</p>
        </label>
      </div>

      {fileName && (
        <div className="flex items-center justify-between p-2 bg-neutral-50 dark:bg-primary-950 rounded-lg">
          <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300 min-w-0">
            <FileText className="w-4 h-4 shrink-0" />
            <span className="truncate">{fileName}</span>
          </div>
          <button onClick={reset} className="p-1 rounded hover:bg-neutral-200 dark:hover:bg-primary-800 shrink-0">
            <X className="w-3.5 h-3.5 text-neutral-400" />
          </button>
        </div>
      )}

      {loading && (
        <div className="flex items-center gap-2 text-sm text-neutral-500 dark:text-neutral-400">
          <Loader2 className="w-4 h-4 animate-spin" /> Resolving {parsedCount} account number(s)…
        </div>
      )}

      {error && (
        <p className="text-xs text-danger-600 dark:text-danger-400 flex items-center gap-1.5">
          <AlertTriangle className="w-3.5 h-3.5" /> {error}
        </p>
      )}

      {!loading && matchedCount !== null && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 flex-wrap">
            <Badge variant="neutral" size="sm">{parsedCount} parsed</Badge>
            <Badge variant="success" size="sm">{matchedCount} matched</Badge>
            {unmatched.length > 0 && (
              <Badge variant="warning" size="sm">{unmatched.length} unmatched</Badge>
            )}
          </div>
          {/* Never silently hide unmatched entries — a treasury user needs to
              see data-entry errors. */}
          {unmatched.length > 0 && (
            <div className="p-2 border border-warning-200 dark:border-warning-500/30 bg-warning-50/50 dark:bg-warning-500/10 rounded-lg">
              <p className="text-xs font-medium text-warning-700 dark:text-warning-300 mb-1">
                Not found — check for typos:
              </p>
              <div className="flex flex-wrap gap-1 max-h-28 overflow-y-auto">
                {unmatched.map(u => (
                  <span key={u} className="text-xs font-mono px-1.5 py-0.5 rounded bg-white dark:bg-primary-900 border border-warning-200 dark:border-warning-500/30 text-neutral-700 dark:text-neutral-200">
                    {u}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default CsvAccountUpload;
