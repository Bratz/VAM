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
}

const PageHeaderContext = createContext<PageHeaderState | null>(null);

export const PageHeaderProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [actions, setActions] = useState<React.ReactNode>(null);
  const value = useMemo(() => ({ actions, setActions }), [actions]);
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

/** Layout reads the currently-registered actions via this. */
export function useRegisteredPageHeaderActions(): React.ReactNode {
  return useContext(PageHeaderContext)?.actions ?? null;
}
