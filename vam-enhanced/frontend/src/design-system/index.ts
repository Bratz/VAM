/**
 * VAM Portal Design System - Token Utilities
 * 
 * This module provides type-safe access to design tokens and utilities
 * for generating CSS custom properties and Tailwind configuration.
 */

import tokens from './tokens.json';

// ==================== Type Definitions ====================

export type ColorPrimitive = keyof typeof tokens.color.primitive;
export type ColorSemantic = keyof typeof tokens.color.semantic;
export type TextStyle = keyof typeof tokens.typography.textStyle;
export type SpacingScale = keyof typeof tokens.spacing.scale;
export type BorderRadius = keyof typeof tokens.borderRadius;
export type Shadow = keyof typeof tokens.shadow;
export type AnimationDuration = keyof typeof tokens.animation.duration;
export type AnimationEasing = keyof typeof tokens.animation.easing;
export type ZIndex = keyof typeof tokens.zIndex;
export type Breakpoint = keyof typeof tokens.breakpoint;

export interface TokenValue {
  value: string;
  description?: string;
}

// ==================== Token Resolution ====================

/**
 * Resolves token references like "{color.primitive.navy.900}" to actual values
 */
export function resolveTokenReference(reference: string, tokenRoot: any = tokens): string {
  if (!reference.startsWith('{') || !reference.endsWith('}')) {
    return reference;
  }

  const path = reference.slice(1, -1).split('.');
  let current = tokenRoot;

  for (const key of path) {
    if (current && typeof current === 'object' && key in current) {
      current = current[key];
    } else {
      console.warn(`Token reference not found: ${reference}`);
      return reference;
    }
  }

  if (typeof current === 'object' && 'value' in current) {
    // Recursively resolve nested references
    return resolveTokenReference(current.value, tokenRoot);
  }

  return current;
}

/**
 * Gets a token value by dot-notation path
 */
export function getToken(path: string): string | undefined {
  const parts = path.split('.');
  let current: any = tokens;

  for (const part of parts) {
    if (current && typeof current === 'object' && part in current) {
      current = current[part];
    } else {
      return undefined;
    }
  }

  if (typeof current === 'object' && 'value' in current) {
    return resolveTokenReference(current.value);
  }

  return undefined;
}

// ==================== CSS Custom Properties Generation ====================

/**
 * Flattens nested token object into CSS custom property format
 */
function flattenTokens(
  obj: any,
  prefix: string = '',
  result: Record<string, string> = {}
): Record<string, string> {
  for (const [key, value] of Object.entries(obj)) {
    const newPrefix = prefix ? `${prefix}-${key}` : key;

    if (typeof value === 'object' && value !== null) {
      if ('value' in value) {
        result[`--${newPrefix}`] = resolveTokenReference(value.value as string);
      } else {
        flattenTokens(value, newPrefix, result);
      }
    }
  }

  return result;
}

/**
 * Generates CSS custom properties string for all color tokens
 */
export function generateColorCSSVariables(): string {
  const colorVars = flattenTokens(tokens.color, 'color');
  return Object.entries(colorVars)
    .map(([key, value]) => `  ${key}: ${value};`)
    .join('\n');
}

/**
 * Generates CSS custom properties string for all spacing tokens
 */
export function generateSpacingCSSVariables(): string {
  const spacingVars = flattenTokens(tokens.spacing.scale, 'spacing');
  return Object.entries(spacingVars)
    .map(([key, value]) => `  ${key}: ${value};`)
    .join('\n');
}

/**
 * Generates CSS custom properties string for all tokens
 */
export function generateAllCSSVariables(): string {
  const sections = [
    '/* Colors */',
    generateColorCSSVariables(),
    '',
    '/* Spacing */',
    generateSpacingCSSVariables(),
    '',
    '/* Typography */',
    flattenTokensToCSS(tokens.typography.fontSize, 'font-size'),
    flattenTokensToCSS(tokens.typography.fontWeight, 'font-weight'),
    flattenTokensToCSS(tokens.typography.lineHeight, 'line-height'),
    flattenTokensToCSS(tokens.typography.letterSpacing, 'letter-spacing'),
    '',
    '/* Border Radius */',
    flattenTokensToCSS(tokens.borderRadius, 'radius'),
    '',
    '/* Shadows */',
    flattenTokensToCSS(tokens.shadow, 'shadow'),
    '',
    '/* Z-Index */',
    flattenTokensToCSS(tokens.zIndex, 'z'),
    '',
    '/* Animation */',
    flattenTokensToCSS(tokens.animation.duration, 'duration'),
    flattenTokensToCSS(tokens.animation.easing, 'easing'),
  ];

  return `:root {\n${sections.join('\n')}\n}`;
}

function flattenTokensToCSS(obj: any, prefix: string): string {
  const vars = flattenTokens(obj, prefix);
  return Object.entries(vars)
    .map(([key, value]) => `  ${key}: ${value};`)
    .join('\n');
}

// ==================== Tailwind Config Generation ====================

/**
 * Generates Tailwind-compatible color configuration
 */
export function generateTailwindColors(): Record<string, Record<string, string>> {
  const result: Record<string, Record<string, string>> = {};

  // Primitive colors
  for (const [colorName, shades] of Object.entries(tokens.color.primitive)) {
    result[colorName] = {};
    for (const [shade, token] of Object.entries(shades as Record<string, TokenValue>)) {
      result[colorName][shade] = token.value;
    }
  }

  return result;
}

/**
 * Generates Tailwind-compatible spacing configuration
 */
export function generateTailwindSpacing(): Record<string, string> {
  const result: Record<string, string> = {};

  for (const [key, token] of Object.entries(tokens.spacing.scale)) {
    result[key] = (token as TokenValue).value;
  }

  return result;
}

/**
 * Generates Tailwind-compatible font size configuration
 */
export function generateTailwindFontSize(): Record<string, [string, Record<string, string>]> {
  const result: Record<string, [string, Record<string, string>]> = {};

  for (const [name, style] of Object.entries(tokens.typography.textStyle)) {
    const styleObj = style as any;
    const fontSize = resolveTokenReference(styleObj.fontSize);
    const lineHeight = resolveTokenReference(styleObj.lineHeight);
    const fontWeight = resolveTokenReference(styleObj.fontWeight);
    const letterSpacing = styleObj.letterSpacing 
      ? resolveTokenReference(styleObj.letterSpacing)
      : '0';

    result[name] = [
      fontSize,
      {
        lineHeight,
        fontWeight,
        letterSpacing,
      },
    ];
  }

  return result;
}

/**
 * Generates complete Tailwind theme extension
 */
export function generateTailwindTheme() {
  return {
    colors: generateTailwindColors(),
    spacing: generateTailwindSpacing(),
    fontSize: generateTailwindFontSize(),
    fontFamily: {
      sans: [tokens.typography.fontFamily.sans.value],
      mono: [tokens.typography.fontFamily.mono.value],
      display: [tokens.typography.fontFamily.display.value],
    },
    borderRadius: Object.fromEntries(
      Object.entries(tokens.borderRadius).map(([key, token]) => [
        key,
        (token as TokenValue).value,
      ])
    ),
    boxShadow: Object.fromEntries(
      Object.entries(tokens.shadow).map(([key, token]) => [
        key,
        resolveTokenReference((token as TokenValue).value),
      ])
    ),
    zIndex: Object.fromEntries(
      Object.entries(tokens.zIndex).map(([key, token]) => [
        key,
        (token as TokenValue).value,
      ])
    ),
    transitionDuration: Object.fromEntries(
      Object.entries(tokens.animation.duration).map(([key, token]) => [
        key,
        (token as TokenValue).value,
      ])
    ),
    transitionTimingFunction: Object.fromEntries(
      Object.entries(tokens.animation.easing).map(([key, token]) => [
        key,
        (token as TokenValue).value,
      ])
    ),
  };
}

// ==================== Component Token Helpers ====================

/**
 * Gets button variant tokens
 */
export function getButtonVariant(variant: keyof typeof tokens.component.button.variants) {
  const variantTokens = tokens.component.button.variants[variant];
  return {
    background: resolveTokenReference(variantTokens.background),
    backgroundHover: resolveTokenReference(variantTokens.backgroundHover),
    backgroundActive: resolveTokenReference(variantTokens.backgroundActive),
    text: resolveTokenReference(variantTokens.text),
    border: variantTokens.border === 'none' 
      ? 'none' 
      : resolveTokenReference(variantTokens.border),
  };
}

/**
 * Gets button size tokens
 */
export function getButtonSize(size: keyof typeof tokens.component.button.sizes) {
  const sizeTokens = tokens.component.button.sizes[size];
  return {
    paddingX: resolveTokenReference(sizeTokens.paddingX),
    paddingY: resolveTokenReference(sizeTokens.paddingY),
    fontSize: resolveTokenReference(sizeTokens.fontSize),
    gap: resolveTokenReference(sizeTokens.gap),
  };
}

/**
 * Gets badge variant tokens
 */
export function getBadgeVariant(variant: keyof typeof tokens.component.badge.variants) {
  const variantTokens = tokens.component.badge.variants[variant];
  return {
    background: resolveTokenReference(variantTokens.background),
    text: resolveTokenReference(variantTokens.text),
    border: resolveTokenReference(variantTokens.border),
  };
}

/**
 * Gets modal size tokens
 */
export function getModalSize(size: keyof typeof tokens.component.modal.sizes) {
  return tokens.component.modal.sizes[size];
}

// ==================== Semantic Color Helpers ====================

/**
 * Gets semantic status colors
 */
export function getStatusColor(status: keyof typeof tokens.color.semantic.status) {
  const statusTokens = tokens.color.semantic.status[status];
  return {
    default: resolveTokenReference(statusTokens.default.value),
    subtle: resolveTokenReference(statusTokens.subtle.value),
    border: resolveTokenReference(statusTokens.border.value),
    text: resolveTokenReference(statusTokens.text.value),
  };
}

/**
 * Gets chart colors array
 */
export function getChartColors(): string[] {
  const chart = tokens.color.semantic.chart;
  return [
    resolveTokenReference(chart.primary.value),
    resolveTokenReference(chart.secondary.value),
    resolveTokenReference(chart.tertiary.value),
    resolveTokenReference(chart.quaternary.value),
    resolveTokenReference(chart.quinary.value),
  ];
}

// ==================== Dark Mode Helpers ====================

/**
 * Gets dark mode color overrides
 */
export function getDarkModeColors() {
  const dm = tokens.color.darkMode;
  return {
    text: {
      primary: resolveTokenReference(dm.text.primary.value),
      secondary: resolveTokenReference(dm.text.secondary.value),
      tertiary: resolveTokenReference(dm.text.tertiary.value),
    },
    background: {
      primary: resolveTokenReference(dm.background.primary.value),
      secondary: resolveTokenReference(dm.background.secondary.value),
      tertiary: resolveTokenReference(dm.background.tertiary.value),
    },
    border: {
      default: resolveTokenReference(dm.border.default.value),
      subtle: resolveTokenReference(dm.border.subtle.value),
    },
  };
}

// ==================== Export Raw Tokens ====================

export { tokens };

// ==================== Default Export ====================

export default {
  tokens,
  getToken,
  resolveTokenReference,
  generateAllCSSVariables,
  generateTailwindTheme,
  getButtonVariant,
  getButtonSize,
  getBadgeVariant,
  getModalSize,
  getStatusColor,
  getChartColors,
  getDarkModeColors,
};
