# Ultravox 2.1 — SHOUTcast v2 source protocol reference

> **Status:** reverse-engineered and **live-verified** against **DNAS2 2.6.1** and a
> **Centova / sctrans2** server. This is an empirical reference derived from the live server's
> behaviour and the `UvoxClient` implementation — there is no published official Ultravox 2.1
> spec in the wild. Anything marked *inferred* is reasoned, not bit-confirmed on the wire.
>
> **Implementation:** [`app/src/main/java/com/streamy/app/net/UvoxClient.kt`](../app/src/main/java/com/streamy/app/net/UvoxClient.kt)
> (framing, handshake, data, metadata), [`UvoxXtea.kt`](../app/src/main/java/com/streamy/app/net/UvoxXtea.kt) (auth cipher).
> Golden vectors: [`UvoxXteaTest.kt`](../app/src/test/java/com/streamy/app/net/UvoxXteaTest.kt). Live harness: [`UvoxClientLiveTest.kt`](../app/src/test/java/com/streamy/app/net/UvoxClientLiveTest.kt).

---

## 1. What Ultravox 2.1 is

Ultravox 2.1 is the **binary source protocol** used by **SHOUTcast v2 (DNAS2)**. A *source* (the
broadcaster — in this repo, the Streamy app) opens one persistent TCP connection to the server,
runs a short handshake of control messages, then streams encoded audio as a sequence of binary
*data messages*, optionally interleaved with *metadata messages*.

It is the successor to the **SHOUTcast v1** source protocol (the legacy `password\r\n` + ICY-headers
text login), and a sibling of Ultravox 1.x (used between relays / sc_trans / liquidsoap).

**Why Streamy uses it instead of v1:**

- v1 is **MP3-only** and **mislabels AAC** — DNAS reports `CONTENT=audio/mpeg` and resets the source.
  Ultravox declares the MIME, so AAC streams correctly as `audio/aacp`.
- Ultravox gives **admin-free in-stream metadata** (message `0x3902`) — title updates need no
  DNAS admin login, just the source password.

---

## 2. Transport

| Property | Value |
|---|---|
| L4 | TCP, single socket for the whole session |
| TLS | None (plain). SHOUTcast source is plain TCP to user-defined servers. |
| Port | The server's source port (DNAS `portbase`, or Centova stream port) |
| Byte order | **Big-endian** for all multi-byte integer fields |
| Socket options (client) | `tcpNoDelay = true`, `keepAlive = true` |
| Read timeout | 6 s during handshake; **cleared (0)** once streaming (writes own the socket) |

---

## 3. Wire framing

Every message — **both directions** — is one self-delimiting frame:

```
 offset 0      1         2..3              4..5         6 .. 6+len-1      6+len
      +--------+--------+----------------+-------------+----------------+--------+
      | 0x5A   | flags  |  message id    |  length     |   payload      | 0x00   |
      | (sync) | 0x00   | (class<<12     | (u16 BE,    |  (length bytes)| trailer|
      |        |        |  | type)        |  = len)     |                |        |
      +--------+--------+----------------+-------------+----------------+--------+
       1 byte   1 byte     2 bytes BE       2 bytes BE     len bytes       1 byte
```

- `[0]` **sync** = `0x5A`
- `[1]` **flags** = `0x00` (reserved; DNAS2 does not use it in practice)
- `[2..3]` **message id**, big-endian = `(class & 0xF) << 12 | (type & 0xFFF)` — 4 bits of class
  in the high nibble, 12 bits of type in the low bits. So an id written `0x1009` means
  **class 1, type 0x009**; `0x8003` means **class 8, type 0x003**.
- `[4..5]` **payload length**, big-endian u16. Negotiated cap is **16377** (see `0x1008`).
- `[6 .. 6+len-1]` **payload**
- `[6+len]` **trailer** = `0x00` (always present, even when the payload already ends in NUL)

Header = 6 bytes. Total frame size = `6 + len + 1`.

**Reading a response** is symmetric: read the 6-byte header, read `len` payload bytes, read 1
trailer byte.

### ASCIIZ payloads

Control-message payloads are **ASCIIZ strings**: the payload bytes include a terminating `0x00`,
*and* the frame still appends its own `0x00` trailer. So a request carrying the string `"2.1"`
becomes payload bytes `32 2E 31 00` (4 bytes) with the frame trailer `0x00` after them.

> Implementation: [`UvoxClient.message()`](../app/src/main/java/com/streamy/app/net/UvoxClient.kt)
> and [`request()`](../app/src/main/java/com/streamy/app/net/UvoxClient.kt).

---

## 4. Message catalog

Observed classes: **1** = control/auth/config, **3** = metadata, **7** = MP3 data, **8** = AAC data.
The class is the high nibble of the 16-bit message id.

| Wire id | Class · Type | Dir | Name | Payload (ASCIIZ unless noted) | Server reply |
|---|---|---|---|---|---|
| `0x1009` | 1 · 0x009 | C→S / S→C | **Request Cipher** | req `"2.1"` | `"ACK:<key>"` — key is `"foobar"` on DNAS2, **empty** on Centova |
| `0x1001` | 1 · 0x001 | C→S | **Authenticate** | `"2.1:<sid>:<xtea(uid)>:<xtea(pwd)>"` (uid empty) | `ACK` / `NAK` |
| `0x1040` | 1 · 0x040 | C→S | Stream MIME Type | `"audio/mpeg"` or `"audio/aacp"` | `ACK` |
| `0x1002` | 1 · 0x002 | C→S | Stream Setup | `"<bitrate>:<bitrate>"` | `ACK` |
| `0x1003` | 1 · 0x003 | C→S | Stream Buffer | `"2048:2048"` | `ACK` |
| `0x1100` | 1 · 0x100 | C→S | ICY name | icy-name | `ACK` |
| `0x1101` | 1 · 0x101 | C→S | ICY genre | icy-genre | `ACK` |
| `0x1102` | 1 · 0x102 | C→S | ICY url | icy-url | `ACK` |
| `0x1103` | 1 · 0x103 | C→S | ICY public | `"1"` / `"0"` | `ACK` |
| `0x1008` | 1 · 0x008 | C→S | Max Payload | `"16377:16377"` | `ACK` |
| `0x1004` | 1 · 0x004 | C→S | **Standby** | (empty) | `"ACK:Data transfer mode"` |
| `0x1005` | 1 · 0x005 | C→S | **Terminate** | (empty) | — (graceful close) |
| `0x7000` | 7 · 0x000 | C→S | **MP3 audio data** | raw MP3 frame bytes (binary) | — |
| `0x8003` | 8 · 0x003 | C→S | **AACP audio data** | one ADTS-wrapped AAC access unit (binary) | — |
| `0x3902` | 3 · 0x902 | C→S | **Metadata** | `[id u16][span u16][idx u16][XML]` (binary) | — |

**ACK vs NAK.** Replies are ASCIIZ strings. `"ACK"` (optionally `":"` + detail) = success; anything
else is a failure → the client raises `ProtocolException`. Notable ACK details: `0x1009` returns
`"ACK:<cipherkey>"`, `0x1004` returns `"ACK:Data transfer mode"`.

---

## 5. Handshake flow

`UvoxClient.connect()` performs this exact sequence. Every step is ACK-checked; a NAK aborts.

```text
 ── TCP connect ──────────────────────────────────────────────────────
 1. → 0x1009 Request Cipher      payload "2.1"
    ← "ACK:foobar"  (or "ACK:" for Centova)          → cipherKey
 2. → 0x1001 Authenticate        payload "2.1:<sid>::<xtea(pwd)>"
    ← ACK                                            (xtea(uid) is blank; uid empty)
 3. → 0x1040 Stream MIME Type    payload "<mime>"            ← ACK
 4. → 0x1002 Stream Setup        payload "<br>:<br>"         ← ACK
 5. → 0x1003 Stream Buffer       payload "2048:2048"         ← ACK
 6. → 0x1100 icy-name            payload "<name>"            ← ACK
 7. → 0x1101 icy-genre           payload "<genre>"           ← ACK
 8. → 0x1102 icy-url             payload "<url>"             ← ACK
 9. → 0x1103 icy-public          payload "1"                 ← ACK
10. → 0x1008 Max Payload         payload "16377:16377"       ← ACK
11. → 0x1004 Standby             payload (empty)
    ← "ACK:Data transfer mode"
 ── streaming mode (read timeout cleared) ────────────────────────────
       → 0x7000 / 0x8003  audio data, per encoded frame
       → 0x3902           metadata (optional, any time while live)
 ── teardown ─────────────────────────────────────────────────────────
       → 0x1005 Terminate  (then socket close)
```

**Order matters.** MIME is declared before setup/buffer; `Max Payload` before `Standby`; `Standby`
is the gate into data-transfer mode.

---

## 6. Authentication — XTEA

The `0x1001` payload's UID and password fields are **XTEA-enciphered** with the server's cipher key,
then emitted as **hex** so the `:`-separated auth string never collides with a colon.

### 6.1 The key

- From the `0x1009` response: strip the `"ACK:"` prefix and the trailing NUL → ASCII key bytes.
- DNAS2 currently returns the fixed string **`"foobar"`**. Centova / sctrans2 leaves
  `uvoxcipherkey` blank and the response is **empty**.
- **Always use the key the server actually returns.** Do *not* default to `"foobar"`: with an empty
  key, XTEA pads to a 16-zero key, which the server also uses — but `"foobar"` would encipher with
  the wrong key and the server decrypts garbage → **`Bad password`** denial.

### 6.2 The cipher

- **XTEA, 32 rounds, big-endian 32-bit words**, `DELTA = 0x9E3779B9`.
- Key: ASCII bytes **zero-padded to 16 bytes** → 4 × u32 (big-endian). Empty key → four zero words.
- Data: **zero-padded to a multiple of 8 bytes**. **Empty data → no blocks → empty output string.**
  (This is how the empty UID field legitimately encodes to blank — not an error.)
- Round function, per 8-byte block, iterated 32 times:

```text
v0 = v0 + (((v1 << 4 XOR v1 >> 5) + v1) XOR (sum + k[sum & 3]))
sum = sum + DELTA
v1 = v1 + (((v0 << 4 XOR v0 >> 5) + v0) XOR (sum + k[(sum >> 11) & 3]))
```

- Output: each block's `(v0, v1)` as two lowercase 8-hex-digit words, concatenated → a hex string.
  That hex string (not raw bytes) is what goes in the auth payload.

### 6.3 Golden vectors (key `"foobar"`)

Captured from a reference port of the spec's C++ XTEA; locked by `UvoxXteaTest`:

| input | `encipher(input, "foobar")` |
|---|---|
| `"test"` | `0797c1d44e31a1f3` |
| `""` (empty) | `""` (empty) |

> Additional non-trivial vectors are locked in `UvoxXteaTest.kt`. Don't paste real server
> passwords into examples — treat every source password as a secret.

> XTEA here is **obfuscation, not security** — the key is public (`"foobar"`). It exists so the
> source password is not sent as cleartext ASCII on the wire. Do not rely on it for confidentiality.

---

## 7. Audio data framing

### 7.1 MP3 (`audio/mpeg`, data message `0x7000`)

Send **raw MP3 frame bytes**. DNAS reads MP3 frame headers natively to find frame boundaries and
the bitrate — no additional wrapping is needed.

### 7.2 AAC (`audio/aacp`, data message `0x8003`)

Each raw AAC access unit **must be wrapped in a 7-byte ADTS header** (no CRC) before sending.
`UvoxClient.send()` does this automatically when the MIME is `audio/aacp`.

**Why ADTS is mandatory:** DNAS uses the ADTS sync word (`0xFFF`) to locate AAC frame boundaries
and to compute the bitrate. **Raw AAC (no ADTS) is accepted briefly then dropped after ~2 s**;
`BITRATE` reads `0`; the source eventually hits `BrokenPipe` and disconnects. ADTS framing fixes
**both** the disconnect and the bitrate display. (Live-verified.)

### 7.3 ADTS header recipe (7 bytes, `protection_absent = 1` → no CRC)

Given one AAC access unit `au` of length `n`, let `frameLen = n + 7`, `profile = 1` (AAC-LC →
`audioObjectType 2 − 1`), `freqIdx` = the sampling-frequency-index for the sample rate, and
`channels` = 1 (mono) or 2 (stereo):

```text
b0 = 0xFF
b1 = 0xF1                       # MPEG-4, layer 0, protection_absent=1 (no CRC)
b2 = (profile << 6) | (freqIdx << 2) | (channels >> 2)
b3 = ((channels & 0x3) << 4) | ((frameLen >> 11) & 0x3)
b4 = (frameLen >> 3) & 0xFF
b5 = ((frameLen & 0x7) << 5) | 0x1F
b6 = 0xFC                       # buffer fullness (VBR) + number_of_raw_data_blocks = 1
# then the n bytes of au
```

**Sampling-frequency-index table** (`freqIdx` → Hz):

```
0=96000  1=88200  2=64000  3=48000  4=44100  5=32000
6=24000  7=22050  8=16000  9=12000 10=11025 11=8000  12=7350
```

> The app's `AacEncoder` (Android `MediaCodec`) emits **raw** AUs (ADTS stripped);
> `UvoxClient.send()` re-wraps them. If your input is already an ADTS `.aac` file, strip the ADTS
> headers first (see `stripAdts()` in `UvoxClientLiveTest`) then let `send()` re-wrap.

---

## 8. In-stream metadata (`0x3902` / class 3 · type 0x902) — admin-free

A single message updates `SONGTITLE` on a live stream with **no DNAS admin credentials** — only the
source connection is required.

### Payload layout

```text
 offset 0..1   MetadataID   (u16 BE) = 1
        2..3   Span         (u16 BE) = 1
        4..5   Index        (u16 BE) = 1
        6..    XML          (UTF-8)
```

### XML

```xml
<?xml version="1.0" encoding="UTF-8"?><metadata><TIT2>the title</TIT2></metadata>
```

`<TIT2>` (the ID3v2 "title" frame id) sets `SONGTITLE`. XML-escape `&`, `<`, `>`. **Do not** add a
NUL inside the payload — the frame's `0x00` trailer is correct as-is.

**Verified:** after the message flushes, `GET /stats?sid=<sid>` reports the new `SONGTITLE` within
~0.5–1 s.

---

## 9. Server stats — verifying a stream

```http
GET http://<host>:<port>/stats?sid=<sid>
```

Returns XML with tags including:

| Tag | Meaning |
|---|---|
| `STREAMSTATUS` | `1` = a source is connected / live |
| `CONTENT` | `audio/mpeg` (MP3) or `audio/aacp` (AAC) |
| `BITRATE` | negotiated/observed bitrate in kbps |
| `SONGTITLE` | current now-playing title |

Live test assertions: AAC over Ultravox → `CONTENT=audio/aacp`, `BITRATE=64`; MP3 + `0x3902` →
`SONGTITLE` contains the pushed title.

---

## 10. Worked example (reconstructed bytes)

**Cipher request** — message id `0x1009`, payload `"2.1"` (ASCIIZ → `32 2E 31 00`, 4 bytes):

```text
5A 00 10 09 00 04  32 2E 31 00  00
│  │  └────┘ │     └─────────┘  └─ frame trailer (0x00)
│  │   id    │      payload ("2.1\0")
│  flags     payload length = 4
sync
```

**Auth payload string** for `sid=1`, password `"test"`, cipher key `"foobar"`:

```text
xtea("")     = ""                    (uid field is blank)
xtea("test") = "0797c1d44e31a1f3"
payload string = "2.1:1::0797c1d44e31a1f3"   (+ trailing NUL, then framed as id 0x1001)
```

(`"test"` is a stand-in; use your real source password at runtime, and never commit it.)

---

## 11. Error modes & disconnect causes (observed)

| Symptom | Cause | Fix |
|---|---|---|
| `auth NAK`, server log `Bad password: <garbage>` | Wrong cipher key (e.g. defaulted to `"foobar"` when server returned empty — Centova) | Use the actual `0x1009` key; empty is valid |
| AAC dropped after ~2 s, `BITRATE=0`, `BrokenPipe` | Raw AAC sent without ADTS | Wrap each AU in a 7-byte ADTS header (`0x8003`) |
| AAC mislabelled as `audio/mpeg`, source reset | Using the v1 source protocol for AAC | Use Ultravox 2.1 with `mime=audio/aacp` |
| `cipher NAK` | Server rejected the `0x1009` exchange | Check protocol version string (`"2.1"`) |
| `socket closed mid-message` | Connection lost during handshake | Reconnect (see `StreamTransport`) |
| Framing desync | Missing frame trailer `0x00` or missing ASCIIZ NUL inside payloads | Both are required — see §3 |

---

## 12. Implementation pointers

| File | Role |
|---|---|
| [`UvoxClient.kt`](../app/src/main/java/com/streamy/app/net/UvoxClient.kt) | The client: `connect()` handshake, `send()` (ADTS wrap + data msg), `updateNowPlaying()` (`0x3902`), `message()` frame builder, `wrapAdts()` |
| [`UvoxXtea.kt`](../app/src/main/java/com/streamy/app/net/UvoxXtea.kt) | XTEA encipher (32 rounds, BE, zero-padded key/data, hex output) |
| [`SourceClient.kt`](../app/src/main/java/com/streamy/app/net/SourceClient.kt) | Interface (`connect` / `send` / `close`) — an Icecast client could slot in here |
| [`StreamTransport.kt`](../app/src/main/java/com/streamy/app/net/StreamTransport.kt) | Bounded drop-newest queue + exponential reconnect backoff wrapping `send()` |
| [`ProtocolException.kt`](../app/src/main/java/com/streamy/app/net/ProtocolException.kt) | Raised on any NAK |
| [`UvoxXteaTest.kt`](../app/src/test/java/com/streamy/app/net/UvoxXteaTest.kt) | Golden vectors |
| [`UvoxClientLiveTest.kt`](../app/src/test/java/com/streamy/app/net/UvoxClientLiveTest.kt) | Env-gated live verification vs a real DNAS2 |

---

## 13. Caveats & status

- Reverse-engineered and live-verified against **DNAS2 2.6.1.777** and a **Centova / sctrans2**
  server. Other DNAS builds (or Ultravox 1.x peers) may differ.
- **`"foobar"` is DNAS2's current default cipher key, not a protocol constant** — always negotiate
  it via `0x1009`.
- The **flags byte** (`0x00`) and some message-type semantics are *inferred* from observed
  behaviour; no official public Ultravox 2.1 spec was consulted.
- **TLS** is not implemented — DNAS source is plain TCP by default.
- The **v1 SHOUTcast source protocol** (password-line, MP3-only) is a separate path — see
  [`docs/plans/2026-07-18-uvox-finding.md`](plans/2026-07-18-uvox-finding.md).
