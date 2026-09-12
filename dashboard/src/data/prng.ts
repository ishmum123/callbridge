/** mulberry32: small, fast, deterministic PRNG. Same seed ⇒ same sequence. */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export class Rng {
  private readonly next: () => number;
  constructor(seed: number) {
    this.next = mulberry32(seed);
  }
  float(): number {
    return this.next();
  }
  int(min: number, maxInclusive: number): number {
    return min + Math.floor(this.next() * (maxInclusive - min + 1));
  }
  chance(p: number): boolean {
    return this.next() < p;
  }
  pick<T>(arr: readonly T[]): T {
    return arr[Math.floor(this.next() * arr.length)];
  }
  /** Pick `n` distinct items without replacement. */
  sample<T>(arr: readonly T[], n: number): T[] {
    const copy = [...arr];
    const out: T[] = [];
    while (out.length < n && copy.length) {
      out.push(copy.splice(Math.floor(this.next() * copy.length), 1)[0]);
    }
    return out;
  }
  /** Pick from items weighted by `weights`. */
  weighted<T>(items: readonly T[], weights: readonly number[]): T {
    const total = weights.reduce((a, b) => a + b, 0);
    let r = this.next() * total;
    for (let i = 0; i < items.length; i++) {
      r -= weights[i];
      if (r <= 0) return items[i];
    }
    return items[items.length - 1];
  }
  /** Approximately normal via sum of uniforms. */
  gauss(mean: number, sd: number): number {
    let s = 0;
    for (let i = 0; i < 6; i++) s += this.next();
    return mean + ((s - 3) / Math.sqrt(0.5)) * sd;
  }
}
