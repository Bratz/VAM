import React from 'react';
import { Clock } from 'lucide-react';
import type { ChatMessage } from '../types';

interface UserMessageProps {
  message: ChatMessage;
}

/**
 * User-side chat bubble. Right-aligned, navy fill, white text — matches
 * the corporate-banking aesthetic.
 */
export const UserMessage: React.FC<UserMessageProps> = ({ message }) => (
  <div className="flex justify-end">
    <div className="max-w-[85%] flex flex-col items-end gap-1">
      <div className={[
        'px-4 py-2.5 rounded-2xl rounded-tr-md',
        'bg-primary-700 dark:bg-primary-700',
        'text-white text-sm leading-relaxed',
        'shadow-sm',
        message.pending && 'opacity-60',
      ].filter(Boolean).join(' ')}>
        {message.content}
      </div>
      {message.pending && (
        <span className="text-xs text-neutral-400 flex items-center gap-1">
          <Clock className="w-3 h-3" /> sending…
        </span>
      )}
    </div>
  </div>
);
