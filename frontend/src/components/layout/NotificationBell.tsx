// The header bell: the selected corporate's latest notifications (every corporate's when none is selected),
// checked every 30 seconds. Opening one marks it read and goes to the page it names.

import React, { useCallback, useEffect, useState } from 'react';
import { Bell } from 'lucide-react';
import { useUser } from '../../context/useUser';
import { notificationApi, AppNotification, Inbox } from '../../services/notificationApi';
import { cn } from '../../utils';

const ago = (iso: string) => {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.round(mins / 60);
  return hours < 24 ? `${hours} h ago` : new Date(iso).toLocaleDateString();
};

const DOT: Record<string, string> = {
  WARNING: 'bg-warning-500', URGENT: 'bg-error-500', ERROR: 'bg-error-500', SUCCESS: 'bg-success-500', INFO: 'bg-info-500',
};

export const NotificationBell: React.FC<{ onNavigate: (page: string) => void }> = ({ onNavigate }) => {
  const { currentCorporateId } = useUser();
  const [open, setOpen] = useState(false);
  const [inbox, setInbox] = useState<Inbox>({ unread: 0, items: [] });

  const load = useCallback(() => {
    notificationApi.inbox(currentCorporateId).then(setInbox).catch(() => undefined);
  }, [currentCorporateId]);

  useEffect(() => {
    load();
    const id = setInterval(load, 30000);
    return () => clearInterval(id);
  }, [load]);

  const openItem = async (n: AppNotification) => {
    setOpen(false);
    if (!n.read) await notificationApi.read(n.id).catch(() => undefined);
    load();
    if (n.actionUrl) onNavigate(n.actionUrl);
  };

  const readAll = async () => {
    await notificationApi.readAll(currentCorporateId).catch(() => undefined);
    load();
  };

  return (
    <div className="relative">
      <button
        onClick={() => { setOpen(!open); if (!open) load(); }}
        aria-label={inbox.unread ? `Notifications, ${inbox.unread} unread` : 'Notifications'}
        className="relative p-2.5 hover:bg-neutral-100 dark:hover:bg-primary-800/50 rounded-lg transition-colors"
      >
        <Bell className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
        {inbox.unread > 0 && <span className="absolute top-2 right-2 w-2 h-2 bg-error-500 rounded-full ring-2 ring-white" />}
      </button>

      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className={cn(
            'absolute right-0 top-full mt-2 w-96 max-w-[calc(100vw-2rem)]',
            'bg-surface-card rounded-lg shadow-2xl border border-neutral-200/60 z-20 overflow-hidden',
            'animate-scale-in origin-top-right'
          )}>
            <div className="p-4 border-b border-edge-subtle flex items-center justify-between">
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">
                Notifications{inbox.unread > 0 && <span className="ml-2 caption">{inbox.unread} unread</span>}
              </h3>
              {inbox.unread > 0 && (
                <button onClick={readAll} className="text-body-sm text-primary-600 hover:text-primary-700 dark:hover:text-primary-200 font-medium dark:text-primary-200">
                  Mark all read
                </button>
              )}
            </div>
            <div className="max-h-96 overflow-y-auto">
              {inbox.items.length === 0 && <p className="p-6 text-body-sm text-neutral-500 dark:text-neutral-400 text-center">Nothing new.</p>}
              {inbox.items.map((n) => (
                <button
                  key={n.id}
                  onClick={() => openItem(n)}
                  className={cn('w-full text-left p-4 hover:bg-neutral-50 dark:hover:bg-primary-800/50 border-b border-edge-subtle last:border-0 transition-colors',
                    n.read && 'opacity-70')}
                >
                  <div className="flex items-start gap-3">
                    <div className={cn('w-2.5 h-2.5 rounded-full mt-1.5 shrink-0', n.read ? 'bg-neutral-300 dark:bg-neutral-600' : DOT[n.category] ?? 'bg-info-500')} />
                    <div className="min-w-0">
                      <p className="body-strong">{n.title}</p>
                      <p className="body-sm mt-0.5 line-clamp-2">{n.message}</p>
                      <p className="caption mt-1.5">{ago(n.createdAt)}{n.actionLabel ? `, ${n.actionLabel.toLowerCase()}` : ''}</p>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
};
