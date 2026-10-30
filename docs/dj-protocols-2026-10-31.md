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
- ~~v1 has no DJ-side now-playing~~ — corrected below.

## Follow-up: DJ titles (same day, 1.13 field report)
Users saw no title change on centova1 (v1) or on a Liquidsoap server (Icecast-style).
- **centova1, v1 DJ:** during a ~12 s silent DJ session, `GET :8092/admin.cgi?mode=updinfo&pass=djlogin:djpassword&song=…`
  (User-Agent containing "Mozilla") → `200 Title Updated`. Same request with Basic auth instead
  of `pass=` → 404; port 8093 drops it; 8094 times out. DNAS `/stats` SONGTITLE still showed the
  AutoDJ title within those seconds, so on-air effect is unconfirmed — same open question as above.
- **Root cause on release builds (both Icecast-style and the new v1 path):** the Icecast
  `/admin/metadata` push used HttpURLConnection, and `network_security_config.xml` forbids
  cleartext for the HTTP stack in release builds — the request was refused locally and the error
  swallowed. A Liquidsoap harbor acts on `/admin/metadata` (what BUTT/Mixxx send), not on
  inline ICY blocks, so harbor DJs got no title at all. Not re-probed: centova2:8048 refused TCP
  at the time.
- Fix: `net/PlainHttp` sends both updates over a raw socket (the source password already goes in
  the clear on the source socket), only while logged in, and replays the last title after every
  connect — a title typed before going live used to hit the admin endpoint before the mount existed.

## Follow-up: the v1 title path on real sc_trans (2026-10-31, local live rig)

"Title Updated" is NOT a fake ACK — reproduced end-to-end with TRANSCast 2.0.0.54 (`sc_trans` DJ
port → `sc_serv` output) on a local rig: `GET <djport>/admin.cgi?mode=updinfo&pass=djlogin:djpassword&song=…`
logs `[TITLEUPDATE] Receiving DJ metadata … / Relaying DJ metadata` and the title DOES reach the
DNAS `SONGTITLE` — but only after sc_trans's DJ "Timed content" audio-pipeline delay (~15 s),
synced to the (equally delayed) DJ audio. That delay is why the earlier field probe "saw no change
within those seconds"; checking `/stats` seconds after an update proves nothing here.

What WAS broken in the app (fixed now): `URLEncoder` form-encoding put `+` for spaces in `song=`.
The Weblet percent-decodes but keeps `+` literally — live-proven: `song=Kajian+Pagi` aired as
**"Kajian+Pagi"**. Every real title has spaces, so titles landed mangled. `WireSafety.urlEncode`
now emits `%20` (the pre-Ultravox `MetadataClient` had this right; the regression lost it).
Verified after the fix with the real `ShoutcastV1Client` (`ShoutcastV1ClientLiveTest`): the typed
title airs verbatim. Same encoding applied to the Icecast `/admin/metadata` path.

Other rig findings (no app action needed, but they explain field reports):

- Inline ICY `StreamTitle` blocks in a v1 DJ's MP3 stream are IGNORED by sc_trans (no TITLEUPDATE
  log, no effect) — the Weblet `admin.cgi` GET is the only DJ title channel there.
- The working `admin.cgi` endpoint is the **djport base** port (the "panel port" the app already
  uses as `adminPort`); sc_trans's own `adminport` Weblet answers `Resource Not Found` for it.
- HTTP GETs aimed at sc_trans's DJ *listener* port (djport+1) are parsed as a bogus login and
  dropped (production logs: `[DJPROTOCOLSHOUTCAST] Login denied from GET …`) — never send title
  updates to the v1 listener port.
- With `encoder_1=aacp` the DJ never takes over at all (the playlist keeps decoding and keeps
  airing its own titles) — same "DJ override never airs" finding as the streamy-panel djMux; with
  `encoder_1=mp3` (licensed) the DJ takes over and titles land. A DJ show that "connects fine but
  airs nothing" on a sc_trans station is this server-side limitation, not the app.
