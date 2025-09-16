import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'
import path from 'node:path'

// Helper function to safely read certificate files
const getHttpsConfig = () => {
  const keyPath = path.resolve(__dirname, 'localhost-key.pem');
  const certPath = path.resolve(__dirname, 'localhost.pem');

  try {
    const key = fs.readFileSync(keyPath, 'utf8');
    const cert = fs.readFileSync(certPath, 'utf8');
    return { key, cert };
  } catch (error) {
    console.warn('⚠️  HTTPS certificates not found. Run "npm run generate-certs" to create them.');
    console.warn('⚠️  Falling back to HTTP for development (insecure for medical data).');
    return false;
  }
};

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    https: getHttpsConfig(),
    host: 'localhost',
    port: 5173,
    headers: {
      // Security headers for development server
      'X-Frame-Options': 'DENY',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Permissions-Policy': 'camera=(), microphone=(self), geolocation=(), payment=()',
      'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: undefined,
      },
    },
    // Remove console.log in production builds
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true,
        drop_debugger: true,
      },
    },
  },
  // Define global constants
  define: {
    // Enable console logging only in development
    __DEV__: JSON.stringify(process.env.NODE_ENV === 'development'),
  },
})
