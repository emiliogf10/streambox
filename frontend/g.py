def w(p,c):  
    with open(p,'w',encoding='utf-8') as f: f.write(c)  
w('src/api.ts', 'export const API_URL = \" "/api\;\nexport function authHeader() { const token = localStorage.getItem(\token\); return token ? { Authorization: \Bearer" "\ + token } : {}; }')  
