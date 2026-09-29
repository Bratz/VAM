import React, { createContext, useContext, useEffect } from 'react';

/**
 * Context object + consumer hooks for the Aperture page header. The provider
 * component lives in `PageHeaderContext.tsx`; these live here so that file can
 * export a component alone and keep Fast Refresh working.
 */

export interface PageHeaderState {
  actions: React.ReactNode;
  setActions: (node: React.ReactNode | null) => void;
  title: string | null;
  description: React.ReactNode;
  setTitle: (title: string | null) => void;
  setDescription: (node: React.ReactNode | null) => void;
}

export const PageHeaderContext = createContext<PageHeaderState | null>(null);

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
