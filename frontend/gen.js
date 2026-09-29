const fs = require('fs');  
const path = require('path');  
function write(f, c) { fs.mkdirSync(path.dirname(f), {recursive:true}); fs.writeFileSync(f, c); }  
write('src/api.ts', 'export const API_URL = \" "/api\;\\nexport function authHeader() { const token = localStorage.getItem(\token\); return token ? { Authorization: Bearer  } : {}; }');  
