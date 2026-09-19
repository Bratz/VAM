import type { Preview } from '@storybook/react-vite';
import '../src/styles/index.css';

// Same mechanism as src/design-system/ThemeProvider.tsx: a `dark` class on <html>.
const preview: Preview = {
  globalTypes: {
    theme: {
      description: 'Colour scheme',
      toolbar: { title: 'Theme', icon: 'circlehollow', items: ['light', 'dark'], dynamicTitle: true },
    },
  },
  initialGlobals: { theme: 'light' },
  decorators: [
    (Story, ctx) => {
      const root = document.documentElement;
      root.classList.remove('light', 'dark');
      root.classList.add(ctx.globals.theme);
      return (
        <div className="bg-white dark:bg-primary-900 p-6 min-h-screen">
          <Story />
        </div>
      );
    },
  ],
};

export default preview;
