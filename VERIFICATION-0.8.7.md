# Lumen 0.8.7 verification

- Root cause reproduced outside Android with Kotlin 2.4.21 (the project's version): an `object : Listener by listener` over a Java interface with default methods forwards only the methods it overrides.
- `BrandedListener` compiled with Kotlin 2.4.21 against a stub of Media3 1.11.1 `Player.Listener` (37 callbacks, signatures taken from the 1.11.1 source). Reflection check: 37 of 37 forwarded, none missing.
- Full Android compile, unit tests and signing run in the GitHub workflow (`testDebugUnitTest assembleRelease`).
- Phone checks for Aaron: play, pause, seeker movement, notification and lock screen controls, resume after force stop.
