import React, { useMemo, useState } from 'react';
import { PageHeaderContext } from './usePageHeader';

/**
 * Lets a page push its toolbar buttons (Reload, Export, Configure, …) up into
 * the Aperture Layout header, alongside the page title. This is how we kill
 * the duplicated in-page H1 pattern — the page renders its content body only,
 * and the title + actions live in one place (the header).
 *
 * Usage in a page:
 * <pre>
 *   const reload = useCallback(() => doReload(), [deps]);
 *
 *   usePageHeaderActions(
 *     () => (
 *       <>
 *         <Button leftIcon={<RefreshCw />} onClick={reload}>Reload</Button>
 *         <Button leftIcon={<Download />} onClick={exportFn}>Export</Button>
 *       </>
 *     ),
 *     [reload, exportFn]
 *   );
 * </pre>
 *
 * The {@code deps} array controls when the action set re-registers — same
 * semantics as {@code useEffect}'s deps. The actions auto-clear on unmount.
 *
 * The context object and the consumer hooks live in `./usePageHeader`.
 */

export const PageHeaderProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [actions, setActions] = useState<React.ReactNode>(null);
  const [title, setTitle] = useState<string | null>(null);
  const [description, setDescription] = useState<React.ReactNode>(null);
  const value = useMemo(
    () => ({ actions, setActions, title, description, setTitle, setDescription }),
    [actions, title, description]
  );
  return <PageHeaderContext.Provider value={value}>{children}</PageHeaderContext.Provider>;
};
