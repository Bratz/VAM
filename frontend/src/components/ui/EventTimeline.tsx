// ============================================================================
// EventTimeline — extracted from ExceptionDashboardPage's ExceptionDetailDrawer
// (the dot+connector chronological list it already rendered inline) so the
// file-ingest pipeline's live timeline (design doc "Timeline UI", build order
// step 7) can reuse it instead of a one-off. Two callers, one component.
// ============================================================================

import React from 'react';
import { Loader2 } from 'lucide-react';

export interface TimelineEntry {
  timestamp: string;
  action: string;
  actor?: string;
  details?: string;
}

interface EventTimelineProps {
  entries: TimelineEntry[];
  loading?: boolean;
}

export const EventTimeline: React.FC<EventTimelineProps> = ({ entries, loading = false }) => {
  if (loading) {
    return (
      <div className="flex justify-center py-8">
        <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {entries.map((entry, idx) => (
        <div key={idx} className="flex gap-3">
          <div className="flex flex-col items-center">
            <div className="w-2 h-2 rounded-full bg-primary-600" />
            {idx < entries.length - 1 && <div className="w-px h-full bg-neutral-200 dark:bg-primary-800 mt-1" />}
          </div>
          <div className="pb-4">
            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{entry.action}</p>
            {entry.actor && <p className="text-xs text-neutral-500 dark:text-neutral-400">by {entry.actor}</p>}
            {entry.details && <p className="text-xs text-neutral-600 dark:text-neutral-300 mt-1">{entry.details}</p>}
            <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">{new Date(entry.timestamp).toLocaleString()}</p>
          </div>
        </div>
      ))}
    </div>
  );
};

export default EventTimeline;
