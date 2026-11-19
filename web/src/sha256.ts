// Incremental SHA-256 also works on a LAN HTTP origin, where SubtleCrypto is unavailable.
const K = new Uint32Array([
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
  0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
  0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
  0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
])
const rotate = (value: number, bits: number) => (value >>> bits) | (value << (32 - bits))

class Sha256 {
  private state = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19])
  private words = new Uint32Array(64)
  private tail = new Uint8Array(64)
  private tailLength = 0
  private length = 0
  update(bytes: Uint8Array) {
    this.length += bytes.length
    let offset = 0
    if (this.tailLength) {
      const take = Math.min(64 - this.tailLength, bytes.length)
      this.tail.set(bytes.subarray(0, take), this.tailLength)
      this.tailLength += take
      offset += take
      if (this.tailLength === 64) { this.block(this.tail, 0); this.tailLength = 0 }
    }
    for (; offset + 64 <= bytes.length; offset += 64) this.block(bytes, offset)
    if (offset < bytes.length) {
      this.tail.set(bytes.subarray(offset), this.tailLength)
      this.tailLength += bytes.length - offset
    }
  }
  private block(bytes: Uint8Array, offset: number) {
    const w = this.words
    const view = new DataView(bytes.buffer, bytes.byteOffset + offset, 64)
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = rotate(w[i-15],7) ^ rotate(w[i-15],18) ^ (w[i-15] >>> 3)
      const s1 = rotate(w[i-2],17) ^ rotate(w[i-2],19) ^ (w[i-2] >>> 10)
      w[i] = (w[i-16] + s0 + w[i-7] + s1) >>> 0
    }
    let [a,b,c,d,e,f,g,h] = this.state
    for (let i = 0; i < 64; i++) {
      const s1 = rotate(e,6) ^ rotate(e,11) ^ rotate(e,25)
      const t1 = (h + s1 + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0
      const s0 = rotate(a,2) ^ rotate(a,13) ^ rotate(a,22)
      const t2 = (s0 + ((a & b) ^ (a & c) ^ (b & c))) >>> 0
      h=g; g=f; f=e; e=(d+t1)>>>0; d=c; c=b; b=a; a=(t1+t2)>>>0
    }
    const next = [a,b,c,d,e,f,g,h]
    for (let i = 0; i < 8; i++) this.state[i] = (this.state[i] + next[i]) >>> 0
  }
  digest() {
    const padding = new Uint8Array(this.tailLength < 56 ? 64 : 128)
    padding.set(this.tail.subarray(0, this.tailLength))
    padding[this.tailLength] = 0x80
    new DataView(padding.buffer).setBigUint64(padding.length - 8, BigInt(this.length) * 8n)
    for (let i = 0; i < padding.length; i += 64) this.block(padding, i)
    return [...this.state].map(value => value.toString(16).padStart(8, '0')).join('')
  }
}

export async function sha256Blob(blob: Blob) {
  const digest = new Sha256()
  const chunkSize = 2 * 1024 * 1024
  for (let offset = 0; offset < blob.size; offset += chunkSize) {
    digest.update(new Uint8Array(await blob.slice(offset, offset + chunkSize).arrayBuffer()))
    if (offset + chunkSize < blob.size) await new Promise(resolve => setTimeout(resolve, 0))
  }
  return digest.digest()
}
