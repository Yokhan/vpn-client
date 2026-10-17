# GATES Android HY recovery backport

Base: github.com/sagernet/sing-quic v0.5.2.
Go module checksum: h1:I3vlfRImhr0uLwRS3b3ib70RMG9FcXtOKKUDz3eKRWc=.
All upstream files and licenses retained; only hysteria2/client.go is changed.
GATES adds focused recovery tests and this provenance note.

The fix follows the shared-offer ownership pattern in upstream v0.6.1 without
upgrading the Android sing-box/quic-go APIs. Caller cancellation stops waiting,
not the shared dial. Client reset cancels and detaches the old attempt; a late
completion cannot install a stale connection or clear a newer pending attempt.
Nil reset errors become net.ErrClosed rather than an apparent successful dial.
Initial socket creation uses the offer context; later hops use client lifetime.

This is not a protocol, credential, obfuscation or server configuration change.
It repairs recovery deadlines, not arbitrary network packet loss or censorship.
Native bridge/APK and real-device recovery acceptance remain separate gates.

Checks from the parent libcore module: seven no-socket tests pass10 repetitions;
all eight tests including real loopback QUIC publication/reuse/reset pass three.
The independent original50ms-deadline reproducer now returns in50-53ms instead
of500ms (three repetitions). No production sockets are opened by these tests.
The loopback test also verifies that later hop socket creation retains a live
context after authentication. It uses one local port, not multi-port payload
migration. Race-detector and forced stale-success disposal remain separate
gaps. A cancellation-ignoring custom dialer can still hold existing
waiters until it returns or their contexts expire.

Remove this fork when a verified upstream-core migration supplies equivalent
recovery behavior while preserving the Android bridge contract and passing
these regressions. Do not drop the replacement merely to silence drift checks.
