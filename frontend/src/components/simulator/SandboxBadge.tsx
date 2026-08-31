import React from 'react';
import { FlaskConical } from 'lucide-react';
import { Badge } from '../ui';

// ============================================================================
// SandboxBadge — the sandbox-isolation visual contract.
//
// Every Simulator surface renders this so the "this is NOT live" signal can
// never drift across pages (the divergence that motivated the ScopeSelector
// consolidation). Warning tone from the semantic palette — distinct from the
// gold `accent` scale after the amber/gold unification, so it reads as a
// caution, not a brand flourish.
//
// Single source of truth: never hand-roll a sandbox chip elsewhere.
// ============================================================================

export interface SandboxBadgeProps {
  size?: 'xs' | 'sm' | 'md';
  className?: string;
}

export const SandboxBadge: React.FC<SandboxBadgeProps> = ({
  size = 'sm',
  className,
}) => (
  <Badge
    variant="warning"
    size={size}
    className={className}
    icon={<FlaskConical className="w-3 h-3" />}
  >
    Sandbox
  </Badge>
);
