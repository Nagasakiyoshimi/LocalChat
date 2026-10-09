import { app, shell } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';

const REPO = 'Nagasakiyoshimi/LocalChat';
const RELEASES = `https://api.github.com/repos/${REPO}/releases/latest`;

export function isNewerVersion(latest, current) {
  const parse = (value) =>
    String(value)
      .replace(/^v/, '')
      .split('.')
      .map((part) => Number.parseInt(part, 10) || 0);
  const next = parse(latest);
  const now = parse(current);
  const length = Math.max(next.length, now.length);
  for (let i = 0; i < length; i++) {
    const diff = (next[i] || 0) - (now[i] || 0);
    if (diff !== 0) return diff > 0;
  }
  return false;
}

function assetForThisComputer(assets = []) {
  if (process.platform === 'win32') return assets.find((asset) => asset.name.endsWith('.exe'));
  if (process.platform === 'darwin') {
    const arch = process.arch === 'arm64' ? 'arm64' : 'x64';
    return (
      assets.find((asset) => asset.name.endsWith('.dmg') && asset.name.includes(arch)) ||
      assets.find((asset) => asset.name.endsWith('.dmg'))
    );
  }
  if (process.platform === 'linux') return assets.find((asset) => asset.name.endsWith('.AppImage'));
  return null;
}

export async function findUpdate() {
  const response = await fetch(RELEASES, {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'LocalChat' },
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(`Update check failed (${response.status})`);
  const release = await response.json();
  const version = String(release.tag_name ?? '').replace(/^v/, '');
  if (!version || !isNewerVersion(version, app.getVersion())) return null;
  const asset = assetForThisComputer(release.assets);
  if (!asset?.browser_download_url) return null;
  return { version, name: asset.name, url: asset.browser_download_url };
}

export async function downloadUpdate(update, onProgress) {
  const response = await fetch(update.url, { headers: { 'User-Agent': 'LocalChat' } });
  if (!response.ok || !response.body) throw new Error(`Download failed (${response.status})`);
  const dir = path.join(app.getPath('temp'), 'LocalChat-updates');
  await fs.promises.mkdir(dir, { recursive: true });
  const filePath = path.join(dir, update.name);
  const total = Number(response.headers.get('content-length')) || 0;
  let received = 0;
  await pipeline(
    Readable.fromWeb(response.body),
    async function* (source) {
      for await (const chunk of source) {
        received += chunk.length;
        if (total) onProgress?.(received / total);
        yield chunk;
      }
    },
    fs.createWriteStream(filePath),
  );
  return filePath;
}

export async function openInstaller(filePath) {
  const error = await shell.openPath(filePath);
  if (error) throw new Error(error);
  if (process.platform === 'win32') app.quit();
}
