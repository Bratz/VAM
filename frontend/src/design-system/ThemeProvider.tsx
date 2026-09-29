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
import type { ColorScheme, ThemeContextValue, ThemeMode } from './useTheme';

// ==================== Types ====================

interface ThemeProviderProps {
  children: React.ReactNode;
  defaultMode?: ThemeMode;
  defaultColorScheme?: ColorScheme;
  storageKey?: string;
}

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

  const value = useMemo<ThemeContextValue>(
    () => ({
      mode,
      colorScheme,
      resolvedMode,
      setMode,
      setColorScheme,
      toggleMode,
    }),
    [
      mode,
      colorScheme,
      resolvedMode,
      setMode,
      setColorScheme,
      toggleMode,
    ]
  );

  return (
    <ThemeContext.Provider value={value}>
      {children}
    </ThemeContext.Provider>
  );
};

export default ThemeProvider;
