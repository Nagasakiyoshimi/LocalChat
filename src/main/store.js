import fs from 'node:fs';
import path from 'node:path';

export class Store {
  #file;
  #timer = null;
  data;

  constructor(file, defaults) {
    this.#file = file;
    let loaded = {};
    try {
      loaded = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      // First run or corrupt file: start fresh.
    }
    this.data = { ...defaults(), ...loaded };
  }

  save() {
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => this.flush(), 300);
  }

  flush() {
    clearTimeout(this.#timer);
    this.#timer = null;
    fs.mkdirSync(path.dirname(this.#file), { recursive: true });
    const tmp = `${this.#file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data));
    fs.renameSync(tmp, this.#file);
  }
}
