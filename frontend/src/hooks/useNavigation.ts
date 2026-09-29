// Navigation context + hook, lifted out of App.tsx so that file exports its
// components only and Fast Refresh keeps working. `PageType` stays in App.tsx
// (it enumerates App's own routes) and is imported here type-only, so nothing
// of App.tsx is pulled in at runtime.
import { createContext, useContext } from 'react';
import type { PageType } from '../App';

export interface NavigationContextType {
  currentPage: PageType;
  navigate: (page: PageType, params?: Record<string, string>) => void;
  goBack: () => void;
  params: Record<string, string>;
  selectedProgramId: string | null;
}

export const NavigationContext = createContext<NavigationContextType | null>(null);

export const useNavigation = () => {
  const context = useContext(NavigationContext);
  if (!context) {
    throw new Error('useNavigation must be used within NavigationProvider');
  }
  return context;
};
