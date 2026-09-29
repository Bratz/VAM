/**
 * VAM Portal Design System - Theme Provider
 * 
 * React context provider for theme management including
 * dark mode, color schemes, and responsive breakpoints.
 */

import React, {
  useState,
  useEffect,
  useCallback,
  useMemo,
} from 'react';
import { ThemeContext } from './useTheme';
import type { Breakpoint, ColorScheme, ThemeContextValue, ThemeMode } from './useTheme';

// ==================== Types ====================

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

export default ThemeProvider;
