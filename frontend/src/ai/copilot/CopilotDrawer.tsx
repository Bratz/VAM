import React, { Fragment } from 'react';
import { Dialog, Transition } from '@headlessui/react';
import { Sparkles, X, MessageSquarePlus } from 'lucide-react';
import { useCopilot } from './CopilotProvider';
import { MessageList } from './components/MessageList';
import { Composer } from './components/Composer';

/**
 * Right-anchored drawer hosting the full Copilot experience.
 *
 * 420px wide on desktop, full-width on mobile. Backdrop-blur over the page
 * with a soft scrim. Closes via X button, Esc, or backdrop click.
 *
 * Uses Headless UI's Dialog + Transition for accessibility (focus trap,
 * aria roles) — same primitive used elsewhere in the design system.
 */
/** snake_case enum → "Title Case" for the header subtitle. */
function humaniseIntent(intent: string | null | undefined): string {
  if (!intent) return 'idle';
  return intent
    .split('_')
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}

export const CopilotDrawer: React.FC = () => {
  const { isOpen, close, newConversation, lastIntent } = useCopilot();

  return (
    <Transition show={isOpen} as={Fragment}>
      <Dialog as="div" className="copilot-drawer relative z-50" onClose={close}>
        {/* Backdrop */}
        <Transition.Child
          as={Fragment}
          enter="ease-out duration-200"
          enterFrom="opacity-0"
          enterTo="opacity-100"
          leave="ease-in duration-150"
          leaveFrom="opacity-100"
          leaveTo="opacity-0"
        >
          <div className="fixed inset-0 bg-primary-950/30 dark:bg-black/50 backdrop-blur-[2px]" />
        </Transition.Child>

        {/* Panel */}
        <div className="fixed inset-0 overflow-hidden">
          <div className="absolute inset-y-0 right-0 flex max-w-full">
            <Transition.Child
              as={Fragment}
              enter="transform transition ease-out duration-250"
              enterFrom="translate-x-full"
              enterTo="translate-x-0"
              leave="transform transition ease-in duration-200"
              leaveFrom="translate-x-0"
              leaveTo="translate-x-full"
            >
              <Dialog.Panel
                className={[
                  'pointer-events-auto w-screen sm:max-w-[420px]',
                  'h-full flex flex-col',
                  'bg-white dark:bg-primary-950',
                  'shadow-2xl shadow-primary-900/30',
                  'border-l border-neutral-200 dark:border-primary-800',
                ].join(' ')}
              >
                {/* Header */}
                <header className={[
                  'flex items-center gap-3 px-4 py-3.5',
                  'border-b border-neutral-200 dark:border-primary-800',
                  'bg-gradient-to-r from-primary-700 to-primary-900',
                  'dark:from-primary-900 dark:to-primary-950',
                  'text-white',
                ].join(' ')}>
                  <div className="shrink-0 w-9 h-9 rounded-xl bg-accent-400/20 flex items-center justify-center">
                    <Sparkles className="w-4 h-4 text-accent-300" strokeWidth={2.5} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <Dialog.Title className="font-display text-base font-semibold tracking-tight">
                      Treasury Copilot
                    </Dialog.Title>
                    <p className="text-xs text-accent-300/80">
                      Stub prototype · {humaniseIntent(lastIntent)}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={newConversation}
                    className="p-2 rounded-lg hover:bg-white/10 transition-colors"
                    title="New conversation"
                    aria-label="New conversation"
                  >
                    <MessageSquarePlus className="w-4 h-4" />
                  </button>
                  <button
                    type="button"
                    onClick={close}
                    className="p-2 rounded-lg hover:bg-white/10 transition-colors"
                    aria-label="Close Copilot"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </header>

                {/* Messages */}
                <MessageList />

                {/* Composer */}
                <Composer />
              </Dialog.Panel>
            </Transition.Child>
          </div>
        </div>
      </Dialog>
    </Transition>
  );
};
