#!/bin/bash

echo "🚀 CuraSnap AI - React Frontend Setup Script"
echo "=============================================="

# Exit on any error
set -e

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js first."
    exit 1
fi

echo "✅ Node.js version: $(node --version)"
echo "✅ NPM version: $(npm --version)"

# Create React project with TypeScript template
echo ""
echo "📦 Creating React + TypeScript project with Vite..."
npm create vite@latest curasnap-frontend -- --template react-ts

# Navigate to project directory
cd curasnap-frontend

echo ""
echo "📦 Installing base dependencies..."
npm install

echo ""
echo "🎨 Installing UI & Styling packages..."
npm install @mui/material @emotion/react @emotion/styled
npm install @mui/icons-material
npm install @mui/x-data-grid
npm install @mui/x-date-pickers

echo ""
echo "🔄 Installing API & State Management..."
npm install @tanstack/react-query @tanstack/react-query-devtools
npm install axios

echo ""
echo "🧭 Installing Navigation..."
npm install react-router-dom
npm install @types/react-router-dom

echo ""
echo "📝 Installing Forms & Validation..."
npm install react-hook-form
npm install @hookform/resolvers
npm install yup

echo ""
echo "🔐 Installing Supabase Authentication..."
npm install @supabase/supabase-js

echo ""
echo "🛠️ Installing Development Tools..."
npm install -D eslint @typescript-eslint/eslint-plugin
npm install -D prettier
npm install -D @types/node

echo ""
echo "⚡ Installing Utility packages..."
npm install date-fns
npm install uuid @types/uuid
npm install react-loading-skeleton

echo ""
echo "📁 Creating project structure..."

# Create directory structure for medical application
mkdir -p src/components/auth
mkdir -p src/components/sessions
mkdir -p src/components/soap
mkdir -p src/components/ui
mkdir -p src/pages
mkdir -p src/services
mkdir -p src/hooks
mkdir -p src/types
mkdir -p src/utils
mkdir -p src/contexts

echo ""
echo "⚙️ Creating configuration files..."

# Create prettier config
cat > .prettierrc << 'EOF'
{
  "semi": true,
  "trailingComma": "es5",
  "singleQuote": true,
  "printWidth": 80,
  "tabWidth": 2
}
EOF

# Create ESLint config update
cat > .eslintrc.cjs << 'EOF'
module.exports = {
  root: true,
  env: { browser: true, es2020: true },
  extends: [
    'eslint:recommended',
    '@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: ['dist', '.eslintrc.cjs'],
  parser: '@typescript-eslint/parser',
  plugins: ['react-refresh'],
  rules: {
    'react-refresh/only-export-components': [
      'warn',
      { allowConstantExport: true },
    ],
    '@typescript-eslint/no-unused-vars': 'warn',
  },
}
EOF

# Create environment file template
cat > .env.local.example << 'EOF'
# Supabase Configuration
VITE_SUPABASE_URL=your_supabase_url_here
VITE_SUPABASE_ANON_KEY=your_supabase_anon_key_here

# Backend API Configuration  
VITE_API_BASE_URL=http://localhost:8080

# Development
VITE_ENVIRONMENT=development
EOF

# Create basic type definitions for medical domain
cat > src/types/index.ts << 'EOF'
// Medical domain types for CuraSnap AI

export interface User {
  id: string;
  email: string;
  created_at: string;
}

export interface Session {
  id: string;
  user_id: string;
  start_time: string;
  end_time?: string;
  status: 'active' | 'completed' | 'archived';
  created_at: string;
  updated_at: string;
}

export interface Transcript {
  id: string;
  session_id: string;
  content: string;
  created_at: string;
  updated_at: string;
}

export interface SoapNote {
  id: string;
  transcript_id: string;
  soap_data: {
    subjective: string;
    objective: string;
    assessment: string;
    plan: string;
  };
  created_at: string;
  updated_at: string;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
  status: 'success' | 'error';
}
EOF

echo ""
echo "🎉 Setup completed successfully!"
echo ""
echo "📋 Next steps:"
echo "1. cd curasnap-frontend"
echo "2. Copy .env.local.example to .env.local and fill in your values"
echo "3. npm run dev"
echo ""
echo "🤖 Ready for Claude Code development!"
echo "Use Magic MCP (/ui commands) to create UI components"
echo "Use Context7 MCP for React best practices"
echo ""
echo "✅ All dependencies installed and configured"
echo "✅ Project structure ready for medical application"
echo "✅ TypeScript types defined for medical domain"