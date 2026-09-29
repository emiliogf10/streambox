export const API_URL = '/api';

export function authHeader(): Record<string, string> {
  const token = localStorage.getItem('token');
  return token ? { Authorization: 'Bearer ' + token } : {};
}