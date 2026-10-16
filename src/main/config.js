export const RELAY_URL = process.env.LOCALCHAT_RELAY || 'wss://birds-black-northeast-amounts.trycloudflare.com';
export const RELAY_URL_SOURCE = 'https://raw.githubusercontent.com/Nagasakiyoshimi/LocalChat/main/relay/url.txt';

export function normalizeInviteCode(input) {
  const code = String(input ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (code.length < 4 || code.length > 12) throw new Error('Enter the invite code');
  return code;
}
