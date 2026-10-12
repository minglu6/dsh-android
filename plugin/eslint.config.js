import js from '@eslint/js'
import globals from 'globals'
export default [
  { ignores: ['lib/**', 'src/client/**', 'tsdown.config.ts'] },
  js.configs.recommended,
  {
    languageOptions: { ecmaVersion: 2024, sourceType: 'module', globals: { ...globals.node } },
    rules: { 'no-unused-vars': ['error', { varsIgnorePattern: '^_', argsIgnorePattern: '^_' }] },
  },
]
