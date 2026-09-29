const fs = require('fs');
fs.writeFileSync('tailwind.config.js', `/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {},
  },
  plugins: [],
};`);

fs.writeFileSync('postcss.config.js', `export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};`);

fs.writeFileSync('src/index.css', `@tailwind base;
@tailwind components;
@tailwind utilities;

body {
  background-color: #141414;
  color: #ffffff;
}`);