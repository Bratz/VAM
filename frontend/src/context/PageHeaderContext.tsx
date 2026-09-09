import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';

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
 */

interface PageHeaderState {
  actions: React.ReactNode;
  setActions: (node: React.ReactNode | null) => void;
  title: string | null;
  description: React.ReactNode;
  setTitle: (title: string | null) => void;
  setDescription: (node: React.ReactNode | null) => void;
}

const PageHeaderContext = createContext<PageHeaderState | null>(null);

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

/** Pages register their toolbar buttons via this hook. */
export function usePageHeaderActions(
  render: () => React.ReactNode,
  deps: React.DependencyList
): void {
  const ctx = useContext(PageHeaderContext);
  useEffect(() => {
    if (!ctx) return;
    ctx.setActions(render());
    return () => ctx.setActions(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/**
 * Pages register their title + description via this hook — same
 * register-on-mount/clear-on-unmount shape as {@link usePageHeaderActions}.
 * This is how the sticky header becomes the single place a page's title
 * renders, instead of a duplicated in-page `<h1>`.
 */
export function usePageHeaderTitle(
  title: string,
  description: React.ReactNode,
  deps: React.DependencyList
): void {
  const ctx = useContext(PageHeaderContext);
  useEffect(() => {
    if (!ctx) return;
    ctx.setTitle(title);
    ctx.setDescription(description ?? null);
    return () => {
      ctx.setTitle(null);
      ctx.setDescription(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/** Layout reads the currently-registered actions via this. */
export function useRegisteredPageHeaderActions(): React.ReactNode {
  return useContext(PageHeaderContext)?.actions ?? null;
}

/** Layout reads the currently-registered title + description via this. */
export function useRegisteredPageHeader(): { title: string | null; description: React.ReactNode } {
  const ctx = useContext(PageHeaderContext);
  return { title: ctx?.title ?? null, description: ctx?.description ?? null };
}
