/**
 * VAM Portal Design System - Theme Hooks
 *
 * Theme context plus the hooks that read from it. Kept separate from
 * ThemeProvider.tsx so that file exports components only (React Fast Refresh).
 */

import { createContext, useContext, useState, useEffect, useMemo } from 'react';
import { tokens } from './index';

// ==================== Types ====================

export type ThemeMode = 'light' | 'dark' | 'system';
export type ColorScheme = 'default' | 'high-contrast';

export interface ThemeContextValue {
  mode: ThemeMode;
  colorScheme: ColorScheme;
  resolvedMode: 'light' | 'dark';
  setMode: (mode: ThemeMode) => void;
  setColorScheme: (scheme: ColorScheme) => void;
  toggleMode: () => void;
  breakpoint: Breakpoint;
  isMobile: boolean;
  isTablet: boolean;
  isDesktop: boolean;
}

export type Breakpoint = 'sm' | 'md' | 'lg' | 'xl' | '2xl';

// ==================== Context ====================

export const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

// ==================== Hook ====================

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
}

// ==================== Additional Hooks ====================

/**
 * Hook to check if a specific breakpoint is active
 */
export function useBreakpoint(targetBreakpoint: Breakpoint): boolean {
  const { breakpoint } = useTheme();
  const breakpointOrder: Breakpoint[] = ['sm', 'md', 'lg', 'xl', '2xl'];
  const currentIndex = breakpointOrder.indexOf(breakpoint);
  const targetIndex = breakpointOrder.indexOf(targetBreakpoint);
  return currentIndex >= targetIndex;
}

/**
 * Hook for responsive values
 */
export function useResponsiveValue<T>(values: Partial<Record<Breakpoint, T>>): T | undefined {
  const { breakpoint } = useTheme();
  const breakpointOrder: Breakpoint[] = ['sm', 'md', 'lg', 'xl', '2xl'];
  const currentIndex = breakpointOrder.indexOf(breakpoint);

  // Find the closest defined value at or below current breakpoint
  for (let i = currentIndex; i >= 0; i--) {
    const bp = breakpointOrder[i];
    if (values[bp] !== undefined) {
      return values[bp];
    }
  }

  return undefined;
}

/**
 * Hook for media query matching
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;

    const mediaQuery = window.matchMedia(query);
    setMatches(mediaQuery.matches);

    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mediaQuery.addEventListener('change', handler);
    return () => mediaQuery.removeEventListener('change', handler);
  }, [query]);

  return matches;
}

/**
 * Hook for preferred reduced motion
 */
export function usePrefersReducedMotion(): boolean {
  return useMediaQuery('(prefers-reduced-motion: reduce)');
}

/**
 * Hook for color scheme preference
 */
export function usePrefersColorScheme(): 'light' | 'dark' {
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)');
  return prefersDark ? 'dark' : 'light';
}

// ==================== Token Access Hook ====================

/**
 * Hook to access design tokens with theme awareness
 */
export function useDesignTokens() {
  const { resolvedMode } = useTheme();

  return useMemo(() => ({
    colors: {
      ...tokens.color.semantic,
      ...(resolvedMode === 'dark' ? tokens.color.darkMode : {}),
    },
    typography: tokens.typography,
    spacing: tokens.spacing,
    borderRadius: tokens.borderRadius,
    shadow: tokens.shadow,
    animation: tokens.animation,
    zIndex: tokens.zIndex,
    component: tokens.component,
  }), [resolvedMode]);
}

// ==================== Style Helper Hook ====================

/**
 * Hook that returns CSS variable references for common tokens
 */
export function useCSSVariables() {
  return useMemo(() => ({
    // Colors
    textPrimary: 'var(--color-text-primary)',
    textSecondary: 'var(--color-text-secondary)',
    textTertiary: 'var(--color-text-tertiary)',
    bgPrimary: 'var(--color-bg-primary)',
    bgSecondary: 'var(--color-bg-secondary)',
    bgTertiary: 'var(--color-bg-tertiary)',
    borderDefault: 'var(--color-border-default)',
    brandPrimary: 'var(--color-brand-primary)',
    brandAccent: 'var(--color-brand-accent)',

    // Status
    success: 'var(--color-status-success)',
    warning: 'var(--color-status-warning)',
    error: 'var(--color-status-error)',
    info: 'var(--color-status-info)',

    // Shadows
    shadowSoft: 'var(--shadow-soft)',
    shadowMedium: 'var(--shadow-medium)',
    shadowStrong: 'var(--shadow-strong)',

    // Transitions
    transitionDefault: 'var(--transition-default)',
    transitionFast: 'var(--transition-fast)',

    // Spacing
    spacing: (scale: string) => `var(--spacing-${scale})`,

    // Border radius
    radius: (size: string) => `var(--radius-${size})`,
  }), []);
}
