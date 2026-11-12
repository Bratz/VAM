import type { StorybookConfig } from '@storybook/react-vite';

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-docs', '@storybook/addon-a11y'],
  framework: '@storybook/react-vite',
  // services/api.ts throws at import in a production build without VITE_API_BASE_URL. That guard
  // is for the app bundle; `storybook build` is a production build too, and CI has no .env.local,
  // so any story importing apiClient died on load. Stories stub the adapter and never call out.
  viteFinal: (vite) => ({
    ...vite,
    define: { ...vite.define, 'import.meta.env.VITE_API_BASE_URL': JSON.stringify('/api/v1') },
  }),
};
export default config;
