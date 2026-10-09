import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const MAX_TEXT = 10_000;
const MAX_FILE_BYTES = 8 * 1024 * 1024;
const MAX_FRAME = 12 * 1024 * 1024;
const HISTORY_LIMIT = 200;
const DATA_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'data');
const STORE_FILE = path.join(DATA_DIR, 'rooms.json');

const rooms = new Map();
const clients = new Set();

function loadRooms() {
  try {
    const saved = JSON.parse(fs.readFileSync(STORE_FILE, 'utf8'));
    for (const room of saved.rooms ?? []) {
      if (!room?.code || !room?.roomId) continue;
      rooms.set(room.code, {
        code: room.code,
        name: String(room.name ?? 'Room').slice(0, 40),
        roomId: room.roomId,
        members: new Set(),
        history: Array.isArray(room.history) ? room.history.slice(-HISTORY_LIMIT) : [],
      });
    }
  } catch {
    // First run.
  }
}

let saveTimer = null;
function saveRooms() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const payload = {
      rooms: [...rooms.values()].map((room) => ({
        code: room.code,
        name: room.name,
        roomId: room.roomId,
        history: room.history.slice(-HISTORY_LIMIT).map((item) => ({
          envelope: item.envelope,
          file: item.file ? { name: item.file.name, size: item.file.size } : undefined,
        })),
      })),
    };
    const tmp = `${STORE_FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(payload));
    fs.renameSync(tmp, STORE_FILE);
  }, 200);
}

function makeCode() {
  const bytes = crypto.randomBytes(8);
  let code = '';
  for (let i = 0; i < 8; i++) code += ALPHABET[bytes[i] % ALPHABET.length];
  return code;
}

function membersOf(room) {
  return [...room.members].map((member) => ({ id: member.id, name: member.name }));
}

function sendAll(room, packet, except) {
  for (const member of room.members) {
    if (member !== except) member.send(packet);
  }
}

function remember(room, envelope, file) {
  room.history.push({
    envelope,
    file: file ? { name: file.name, size: file.size } : undefined,
  });
  if (room.history.length > HISTORY_LIMIT) room.history.splice(0, room.history.length - HISTORY_LIMIT);
  saveRooms();
}

function acceptMessage(client, packet) {
  if (packet?.type === 'hello') {
    const id = String(packet.id ?? '');
    const name = String(packet.name ?? '').trim().slice(0, 40);
    if (!id || id.length > 100 || !name) return client.send({ type: 'error', error: 'Invalid profile' });
    client.id = id;
    client.name = name;
    client.device = String(packet.device ?? '').slice(0, 100);
    client.ready = true;
    client.send({ type: 'hello', ok: true });
    for (const code of client.rooms) {
      const room = rooms.get(code);
      if (room) room.members.add(client);
    }
    return;
  }

  if (packet.type === 'ping') return;

  if (!client.ready) return client.send({ type: 'error', error: 'Say hello first' });

  if (packet.type === 'create') {
    const name = String(packet.name ?? '').trim().slice(0, 40);
    if (!name) return client.fail(packet, 'Name cannot be empty');
    let code = String(packet.code ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    const existing = /^[A-Z0-9]{8}$/.test(code) ? rooms.get(code) : null;
    if (existing) {
      existing.members.add(client);
      client.rooms.add(code);
      client.send({ type: 'created', req: packet.req, code, roomId: existing.roomId, name: existing.name });
      client.send({ type: 'members', code, roomId: existing.roomId, members: membersOf(existing) });
      return;
    }
    if (!/^[A-Z0-9]{8}$/.test(code)) {
      code = makeCode();
      while (rooms.has(code)) code = makeCode();
    }
    const room = {
      code,
      name,
      roomId: `invite:${code}`,
      members: new Set([client]),
      history: [],
    };
    rooms.set(code, room);
    client.rooms.add(code);
    saveRooms();
    client.send({ type: 'created', req: packet.req, code, roomId: room.roomId, name });
    client.send({ type: 'members', code, roomId: room.roomId, members: membersOf(room) });
    return;
  }

  if (packet.type === 'join') {
    const code = normalize(packet.code);
    const room = rooms.get(code);
    if (!room) return client.fail(packet, 'Invite code not found');
    room.members.add(client);
    client.rooms.add(code);
    client.send({
      type: 'joined',
      req: packet.req,
      code,
      roomId: room.roomId,
      name: room.name,
      members: membersOf(room),
    });
    for (const item of room.history) {
      client.send({ type: 'message', history: true, envelope: item.envelope, file: item.file });
    }
    sendAll(room, { type: 'members', code, roomId: room.roomId, members: membersOf(room) });
    return;
  }

  if (packet.type === 'leave') {
    leave(client, normalize(packet.code));
    return;
  }

  if (packet.type === 'message') {
    const code = normalize(packet.code);
    const room = rooms.get(code);
    if (!room || !client.rooms.has(code)) return client.fail(packet, 'Join this room first');
    let envelope;
    try {
      envelope = sanitizeEnvelope(packet.envelope, room);
    } catch (error) {
      return client.fail(packet, error.message);
    }
    let file;
    if (packet.file) {
      const name = safeName(packet.file.name);
      const data = String(packet.file.data ?? '');
      const bytes = Buffer.from(data, 'base64');
      if (!name || bytes.length !== Number(packet.file.size) || bytes.length > MAX_FILE_BYTES) {
        return client.fail(packet, 'File is too large or incomplete');
      }
      file = { name, size: bytes.length, data };
    }
    const outgoing = { type: 'message', envelope, ...(file ? { file: { name: file.name, size: file.size, data: file.data } } : {}) };
    remember(room, envelope, file);
    sendAll(room, outgoing, client);
    client.send({ type: 'ack', req: packet.req });
    return;
  }

  client.send({ type: 'error', error: 'Unknown request' });
}

function leave(client, code) {
  const room = rooms.get(code);
  client.rooms.delete(code);
  if (!room) return;
  room.members.delete(client);
  sendAll(room, { type: 'members', code, roomId: room.roomId, members: membersOf(room) });
}

function normalize(code) {
  return String(code ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function safeName(name) {
  return String(name ?? '')
    .split(/[/\\]/)
    .pop()
    .replace(/[<>:"|?*\u0000-\u001f]/g, '_')
    .replace(/^\.+/, '')
    .slice(0, 200);
}

function sanitizeEnvelope(input, room) {
  const id = String(input?.id ?? '');
  const fromId = String(input?.from?.id ?? '');
  const fromName = String(input?.from?.name ?? '').trim().slice(0, 40);
  const text = String(input?.text ?? '');
  if (!id || id.length > 100 || !fromId || fromId.length > 100 || !fromName) throw new Error('Invalid message');
  if (text.length > MAX_TEXT) throw new Error('Message is too long');
  return {
    id,
    from: { id: fromId, name: fromName },
    ts: Number.isFinite(input?.ts) ? input.ts : Date.now(),
    text,
    room: { id: room.roomId, type: 'group', name: room.name },
  };
}

class Client {
  constructor(socket) {
    clients.add(this);
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.fragments = [];
    this.fragmentOpcode = 0;
    this.id = '';
    this.name = '';
    this.device = '';
    this.ready = false;
    this.rooms = new Set();
    socket.on('data', (chunk) => this.#push(chunk));
    socket.on('close', () => this.#closed());
    socket.on('error', () => socket.destroy());
  }

  send(obj) {
    if (this.socket.destroyed) return;
    const payload = Buffer.from(JSON.stringify(obj));
    let header;
    if (payload.length < 126) header = Buffer.from([0x81, payload.length]);
    else if (payload.length < 65536) header = Buffer.from([0x81, 126, payload.length >> 8, payload.length & 0xff]);
    else {
      header = Buffer.alloc(10);
      header[0] = 0x81;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(payload.length), 2);
    }
    this.socket.write(Buffer.concat([header, payload]));
  }

  fail(packet, error) {
    this.send({ type: 'error', req: packet?.req, error });
  }

  #closed() {
    clients.delete(this);
    for (const code of [...this.rooms]) leave(this, code);
  }

  #push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const opcode = first & 0x0f;
      const fin = (first & 0x80) !== 0;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (this.buffer.length < 4) return;
        length = this.buffer.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (this.buffer.length < 10) return;
        const big = this.buffer.readBigUInt64BE(2);
        if (big > BigInt(MAX_FRAME)) return this.socket.destroy();
        length = Number(big);
        offset = 10;
      }
      const maskLength = masked ? 4 : 0;
      if (this.buffer.length < offset + maskLength + length) return;
      let payload = this.buffer.subarray(offset + maskLength, offset + maskLength + length);
      if (masked) {
        const mask = this.buffer.subarray(offset, offset + 4);
        payload = Buffer.from(payload);
        for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      }
      this.buffer = this.buffer.subarray(offset + maskLength + length);
      if (length > MAX_FRAME) return this.socket.destroy();
      this.#frame(opcode, fin, payload);
    }
  }

  #frame(opcode, fin, payload) {
    if (opcode === 0x8) return this.socket.end();
    if (opcode === 0x9) {
      const header = Buffer.from([0x8a, payload.length]);
      this.socket.write(Buffer.concat([header, payload]));
      return;
    }
    if (opcode === 0x0 || opcode === 0x1) {
      if (opcode === 0x1) {
        this.fragments = [payload];
        this.fragmentOpcode = opcode;
      } else this.fragments.push(payload);
      if (!fin) return;
      const text = Buffer.concat(this.fragments).toString('utf8');
      this.fragments = [];
      try {
        acceptMessage(this, JSON.parse(text));
      } catch (error) {
        this.send({ type: 'error', error: error.message || 'Bad message' });
      }
    }
  }
}

loadRooms();

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
    return;
  }
  res.writeHead(404);
  res.end();
});

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key) return socket.destroy();
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );
  new Client(socket);
});

setInterval(() => {
  for (const client of clients) client.send({ type: 'ping' });
}, 20_000);

server.listen(PORT, HOST, () => {
  console.log(`LocalChat relay on ws://${HOST}:${PORT} (${rooms.size} rooms)`);
});
