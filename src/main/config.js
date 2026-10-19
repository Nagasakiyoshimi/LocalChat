export const RELAY_URL = process.env.LOCALCHAT_RELAY || 'wss://herself-rich-bringing-years.trycloudflare.com';
export const RELAY_URL_SOURCE = 'https://api.github.com/repos/Nagasakiyoshimi/LocalChat/contents/relay/url.txt?ref=main';

export function normalizeInviteCode(input) {
  const code = String(input ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (code.length < 4 || code.length > 12) throw new Error('Enter the invite code');
  return code;
}
