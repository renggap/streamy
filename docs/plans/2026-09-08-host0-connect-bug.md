# 2026-09-08 host:0 connect bug (live finding)

## Symptom
App could not connect to DNAS: `streamy1.radioislam.my.id:9125`, valid creds (dj / djpassword).
Server answered uvox correctly from Python probes, so the server was fine.

## Root cause
`Socket().apply { connect(InetSocketAddress(host, port), TIMEOUT) }` — inside `apply`, the
unqualified `port` resolves to **Socket.getPort()**, not the constructor parameter (Kotlin
implicit-receiver shadowing). An unconnected socket returns port **0**, so the client dialed
`host:0` → instant `ConnectException: Connection refused`.

Bytecode proof (`javap -c UvoxClient.connect`):
```
78: getfield      #28   // Field host
81: aload         6           // the receiver socket (s), NOT the ctor param
83: invokevirtual #105  // Method java/net/Socket.getPort:()I
86: invokespecial #108  // InetSocketAddress."<init>":(Ljava/lang/String;I)V
```

Both `UvoxClient` and `IcecastSourceClient` had the same pattern.

## Fix (c876864)
Bind the address to a named local BEFORE the apply scope:
```kotlin
val addr = InetSocketAddress(host, port)
val s = Socket().apply { connect(addr, HANDSHAKE_TIMEOUT_MS) }
```

## Verification
- `ConnectPortTest` (local listener; would have caught the bug): 2/2 PASS.
- Live against the real DNAS: full uvox handshake, MP3 publish, metadata push
  (`SONGTITLE` observed), AAC publish (`CONTENT=audio/aacp`). PASS.
- Full unit suite 66/0.

## Server notes (this DNAS 2.6 deployment)
- Source port 9125; public portbase (stats `/stats?sid=1`) is **9120** → tests take
  `STREAMY_LIVE_STATS_PORT` (default `port - 1`).
- Auth: both `2.1:<sid>:<xtea(uid)>:<xtea(pw)>` and blank-UID forms ACK when connections are
  spaced out; rapid repeated failed auths trigger an IP temp-ban (~10–15 min, cipher gets
  instant-close).
- aacp BITRATE readback is unreliable: reports scaled values (24/32) for a true 64k stream at
  either declared ADTS rate. `CONTENT=audio/aacp` is the regression oracle; strict BITRATE
  assert behind `STREAMY_LIVE_STRICT_BITRATE=1` (43f0ac2).

## Lesson
Never reference an outer-scope property named like a member inside `X().apply {}` — either
rename the parameter or hoist the expression out. Regression test guards this class of bug.
