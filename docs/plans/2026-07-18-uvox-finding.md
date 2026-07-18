# SHOUTcast v2 protocol finding (2026-07-18, live probe)

## What the plan assumed
- DNAS2 accepts legacy ICY source (`IcyClient`, HTTP-PUT style) as a fallback.
- Full Ultravox (UVOX2) binary protocol is the risky Task 8, built via golden-byte capture.

## What the live server (DNAS2 2.6.1.777 @ az1.radioislam.my.id:8030) actually does
Empirically (tcp open; python probes; ffmpeg publish):

1. **Icecast HTTP-PUT source → rejected.** Server closes immediately ("End of file"). This is
   what Task 7's `IcyClient` implemented — wrong protocol for this DNAS2.
2. **SHOUTcast v1 source protocol → works.** The v1 password-line login succeeds:
   - Client sends `<password>\r\n`
   - Server replies `OK2\r\nicy-caps:11\r\n\r\n`
   - Client sends icy headers (`icy-name`, `icy-genre`, `icy-br`, `icy-pub`, `icy-url`, …) + `\r\n\r\n`
   - Client streams raw MP3 bytes
   - Result: `STREAMSTATUS=1`, `BITRATE=128`, `CONTENT=audio/mpeg`, `STREAMPATH=/radio.mp3`.
3. **`icy-caps:11` advertises capabilities but raw v1 MP3 is accepted as-is** — no Ultravox framing
   required for MP3.

## Consequences
- **Task 8 is rewritten:** instead of building `UvoxClient` (Ultravox binary), **fix `IcyClient` to
  the real SHOUTcast v1 source protocol** and live-verify with the real Kotlin client.
- `IcyClient` (v1) becomes the primary client for DNAS2. Icecast HTTP-PUT support is dropped (YAGNI;
  no Icecast server in scope — re-add as a separate client if ever needed).
- **AAC over v1 source on DNAS2 is unverified.** MP3 is the verified path (and the speech default).
  AAC may require Ultravox on DNAS2; documented as a known limitation, revisit if AAC-to-DNAS2 is needed.
- **Now-playing metadata updates** via v1 source aren't done inline; DNAS2 supports
  `admin.cgi?sid=N&mode=updinfo&song=...` (needs admin/source creds). Deferred — secondary feature.
- The listener URL for sid=1 is `/radio.mp3` (server-configured streampath), NOT `/stream`.

## Verified byte sequence (python client proof)
```
connect TCP az1.radioislam.my.id:8030
-> "6naZQ3gy\r\n"
<- "OK2\r\nicy-caps:11\r\n\r\n"
-> "icy-name:Streamy\r\nicy-genre:Speech\r\nicy-br:128\r\nicy-pub:1\r\nicy-url:http://streamy.app\r\nicy-irc:N/A\r\nicy-icq:N/A\r\nicy-aim:N/A\r\n\r\n"
-> <raw MP3 frames>
=> STREAMSTATUS=1
```
