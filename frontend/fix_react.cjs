const fs = require('fs');  
['HomePage.tsx', 'MyListPage.tsx', 'MovieDetailsModal.tsx', 'SearchBar.tsx'].forEach(file => { let p = 'src/' + file; let c = fs.readFileSync(p, 'utf8'); c = 'import { useEffect, useState } from \" "react\;\n' + c; fs.writeFileSync(p, c); });  
let lp = 'src/LoginPage.tsx'; let lpc = fs.readFileSync(lp, 'utf8'); lpc = lpc.replace(/import React, \{ useState \} from 'react';/g, 'import { useState } from \react\;'); fs.writeFileSync(lp, lpc);  
