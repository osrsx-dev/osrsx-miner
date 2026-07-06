# osrsx-plugin-template

A starter for an [osrsx](https://github.com/osrsx/osrsx-client) plugin. Click **Use this template**, then
edit `src/main/kotlin/.../PingPlugin.kt` and the `osrsxPlugin { }` block in `build.gradle`.

## The whole build

```gradle
plugins {
    id 'io.osrsx.plugin' version '0.1.0'
}

group = 'com.example'
version = '1.0.0'

osrsxPlugin {
    id = 'ping'
    name = 'Ping'
    description = 'Logs "pong" when started.'
    authors = ['Your Name']
    tags = ['utility']
}
```

Applying `io.osrsx.plugin` does everything else for you:
- applies **Kotlin** + pins the **JDK-11 toolchain** the client runs on (auto-provisioned by foojay — you
  don't need JDK 11 installed; write plain Java in `src/main/java` if you prefer),
- wires the **osrsx SDK** repository (served anonymously over `raw.githubusercontent`, **no token**) and
  the `compileOnly io.osrsx:osrsx-api` + `testImplementation io.osrsx:osrsx-testkit` dependencies,
- stamps the jar manifest and **generates `plugin.yaml`** into the jar from the `osrsxPlugin { }` block —
  that block is the single source of truth; there is no hand-written manifest to keep in sync,
- registers the `installPlugin`, `osrsxRun`, and `publishPlugin` tasks.

You can still add your own `repositories { }` / `dependencies { }` — the above is just the minimal setup.

## Dev loop (edit → save → live reload)

1. Launch the client once (from an osrsx checkout: `./gradlew :osrsx-core:runClient`).
2. In this project: `./gradlew -t installPlugin` — rebuilds + reinstalls into `~/.osrsx/plugins` on every
   save; the client's directory watcher hot-reloads it live. Enable it from the in-game Plugin Manager.

Install into a specific launcher account instead: `-Posrsx.pluginsDir=~/.osrsx/homes/<account>/.osrsx/plugins`.

## Publish to the registry

```
./gradlew publishPlugin
```
Collects the version/changelog (a small dialog, or `-PpluginVersion=`/`-Pnoninteractive`), pushes + tags
your repo, and opens the submission issue on `osrsx/osrsx-central` (using your local `gh` token, a
GitHub OAuth sign-in, or a token you paste). The registry CI builds + publishes it once a maintainer
approves.
