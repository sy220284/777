# Contributing

Thanks for your interest in DSH Mobile!

## Setup

- Android Studio (Koala or newer) with JDK 17+ and Android SDK 36.
- `./gradlew :app:assembleDebug` builds the debug APK.

## Development against a real harness

1. Run DeepSeek Harness on the computer with its default loopback listener.
2. Configure an HTTPS relay and pair the app through Remote control.
3. See `harness/README.md`. Direct LAN connections and network scanning are no longer supported.

## Repository layout

- `core/` — pure JVM: wire protocol (DTOs, RPC client, WebSocket downlinks,
  reconnect loop), session event folding, notification classifier.
- `app/` — Android UI: Compose screens, relay connection, foreground
  service, notifications, i18n.
- `mock-harness/` — Ktor mock of the harness `/api` server for tests.
- `tools/capture/` — Node script that records real harness traffic into JSON
  fixtures for conformance tests.

## Conventions

- Kotlin official code style; one file per screen/component where sensible.
- The wire layer parses leniently (unknown keys/events/tool cards must fall
  back, never crash).
- UI strings live in `values*/strings.xml` only — never hardcode.
- Pull requests need CI green (unit tests + lint + assemble).

## Release

Tag `v*` → the Release workflow builds and publishes a signed APK (when
signing secrets are configured) or an unsigned APK.
