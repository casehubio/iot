import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  resolve: {
    alias: {
      '@casehubio/blocks-ui-core': path.resolve(__dirname, '.casehub-packages/packages/blocks-ui-core/src/index.ts'),
      '@casehubio/graph-core': path.resolve(__dirname, '.casehub-packages/packages/graph-core/src/index.ts'),
      '@casehubio/graph-renderer': path.resolve(__dirname, '.casehub-packages/packages/graph-renderer/src/index.ts'),
    },
  },
  test: {
    include: ['src/**/*.test.ts', '.casehub-packages/packages/blocks-topology-*/**/*.test.ts'],
    environment: 'jsdom',
  },
});
