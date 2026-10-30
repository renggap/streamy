# DJ protocol finding (2026-10-31, live probes)

Users reported DJ mode failing. Probed three real DJ endpoints (credentials supplied by the
station owner, kept out of the repo). Each probe used a wrong-password control so a "works"
result means the server actually checked the login.

| Server | Software | Port shown to the DJ | What accepts the login |
|---|---|---|---|
| centova2.radioislam.my.id | Liquidsoap harbor (`realm="Liquidsoap harbor"`) | 8048 | `SOURCE / ICE/1.0` + Basic auth on 8048 → `200 OK`. Ultravox is ignored (silent). |
| centova1.radioislam.my.id | sc_trans2 behind Centova; panel says "Protocol: Shoutcast (v1)" | 8092 (an HTTP "Weblet" page) | v1 line `djlogin:djpassword\r\n` on **8093 (= 8092 + 1)** → `OK2\r\nicy-caps:11\r\n\r\n`. Ultravox DJ login also works on 8094 (not shown in the panel). |
| streamy1.radioislam.my.id | custom panel, DNAS-style source port | 9185 | Ultravox on 9185 (non-blank cipher key); UID is ignored, password checked. 9186 hangs up on a v1 line in ~0.2 s. |

Each accepted ~6 s of MP3 without dropping. Whether the DJ actually replaced AutoDJ on air was
NOT confirmed (no decoder on the probe box; DNAS `/stats` SONGTITLE did not change on centova1).

## Consequence for the app
- Icecast-style option (checkbox) = Liquidsoap / AzuraCast DJ ports.
- Unticked = `FallbackSourceClient`: SHOUTcast v1 on port + 1, then Ultravox on the entered port.
  Covers centova1 (v1 succeeds) and streamy1 (v1 fails fast, Ultravox succeeds). Sticky after the
  first success so reconnects never switch protocol mid-show.
- v1 has no DJ-side now-playing (needs the server admin password); titles are a no-op there.
