import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// GitHub Pages serves the project site under /<repo>/. Override with VITE_BASE
// (e.g. "/" for a custom domain or local preview).
export default defineConfig({
  base: process.env.VITE_BASE ?? '/callbridge/',
  plugins: [react()],
});
