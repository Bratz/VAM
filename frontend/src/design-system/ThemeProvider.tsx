/**
 * VAM Portal Design System - Theme Provider
 * 
 * React context provider for theme management including
 * dark mode, color schemes, and responsive breakpoints.
 */

import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
} from 'react';
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

interface ThemeProviderProps {
  children: React.ReactNode;
  defaultMode?: ThemeMode;
  defaultColorScheme?: ColorScheme;
  storageKey?: string;
}

// ==================== Breakpoint Values ====================

const breakpointValues: Record<Breakpoint, number> = {
  sm: 640,
  md: 768,
  lg: 1024,
  xl: 1280,
  '2xl': 1536,
};

// ==================== Context ====================

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

// ==================== Provider ====================

export const ThemeProvider: React.FC<ThemeProviderProps> = ({
  children,
  defaultMode = 'system',
  defaultColorScheme = 'default',
  storageKey = 'vam-theme',
}) => {
  // Initialize state from localStorage or defaults
  const [mode, setModeState] = useState<ThemeMode>(() => {
    if (typeof window === 'undefined') return defaultMode;
    const stored = localStorage.getItem(`${storageKey}-mode`);
    return (stored as ThemeMode) || defaultMode;
  });

  const [colorScheme, setColorSchemeState] = useState<ColorScheme>(() => {
    if (typeof window === 'undefined') return defaultColorScheme;
    const stored = localStorage.getItem(`${storageKey}-color-scheme`);
    return (stored as ColorScheme) || defaultColorScheme;
  });

  const [systemPreference, setSystemPreference] = useState<'light' | 'dark'>('light');
  const [breakpoint, setBreakpoint] = useState<Breakpoint>('lg');

  // Determine system preference
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    setSystemPreference(mediaQuery.matches ? 'dark' : 'light');

    const handler = (e: MediaQueryListEvent) => {
      setSystemPreference(e.matches ? 'dark' : 'light');
    };

    mediaQuery.addEventListener('change', handler);
    return () => mediaQuery.removeEventListener('change', handler);
  }, []);

  // Calculate resolved mode
  const resolvedMode = useMemo(() => {
    if (mode === 'system') return systemPreference;
    return mode;
  }, [mode, systemPreference]);

  // Apply theme class to document
  useEffect(() => {
    if (typeof document === 'undefined') return;

    const root = document.documentElement;
    
    // Remove existing theme classes
    root.classList.remove('light', 'dark');
    root.removeAttribute('data-theme');
    
    // Apply new theme
    root.classList.add(resolvedMode);
    root.setAttribute('data-theme', resolvedMode);

    // Apply color scheme
    if (colorScheme === 'high-contrast') {
      root.setAttribute('data-color-scheme', 'high-contrast');
    } else {
      root.removeAttribute('data-color-scheme');
    }
  }, [resolvedMode, colorScheme]);

  // Handle breakpoint changes
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const updateBreakpoint = () => {
      const width = window.innerWidth;
      
      if (width < breakpointValues.sm) {
        setBreakpoint('sm');
      } else if (width < breakpointValues.md) {
        setBreakpoint('md');
      } else if (width < breakpointValues.lg) {
        setBreakpoint('lg');
      } else if (width < breakpointValues.xl) {
        setBreakpoint('xl');
      } else {
        setBreakpoint('2xl');
      }
    };

    updateBreakpoint();
    window.addEventListener('resize', updateBreakpoint);
    return () => window.removeEventListener('resize', updateBreakpoint);
  }, []);

  // Setters with localStorage persistence
  const setMode = useCallback((newMode: ThemeMode) => {
    setModeState(newMode);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(`${storageKey}-mode`, newMode);
    }
  }, [storageKey]);

  const setColorScheme = useCallback((scheme: ColorScheme) => {
    setColorSchemeState(scheme);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(`${storageKey}-color-scheme`, scheme);
    }
  }, [storageKey]);

  const toggleMode = useCallback(() => {
    setMode(resolvedMode === 'light' ? 'dark' : 'light');
  }, [resolvedMode, setMode]);

  // Responsive helpers
  const isMobile = breakpoint === 'sm';
  const isTablet = breakpoint === 'md';
  const isDesktop = ['lg', 'xl', '2xl'].includes(breakpoint);

  const value = useMemo<ThemeContextValue>(
    () => ({
      mode,
      colorScheme,
      resolvedMode,
      setMode,
      setColorScheme,
      toggleMode,
      breakpoint,
      isMobile,
      isTablet,
      isDesktop,
    }),
    [
      mode,
      colorScheme,
      resolvedMode,
      setMode,
      setColorScheme,
      toggleMode,
      breakpoint,
      isMobile,
      isTablet,
      isDesktop,
    ]
  );

  return (
    <ThemeContext.Provider value={value}>
      {children}
    </ThemeContext.Provider>
  );
};

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

export default ThemeProvider;
