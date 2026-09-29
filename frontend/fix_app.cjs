const fs = require('fs'); let c = fs.readFileSync('src/App.tsx', 'utf8'); c = c.replace(/import React from 'react';\n/g, ''); fs.writeFileSync('src/App.tsx', c);  
