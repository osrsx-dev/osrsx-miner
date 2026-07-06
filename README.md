# osrsx-miner

The **Miner** plugin for [osrsx](https://github.com/osrsx/osrsx-client). Mines a configured rock and drops or banks the ore.

Built on the shared [`osrsx-skilling-lib`](https://github.com/MrManiacc/osrsx-skilling-lib) library
(declared `requires 'skilling-lib:1.0.0'`) — the client auto-installs the library when you install this plugin.

## Install (in-game marketplace)

Open the **Marketplace** panel in the client, search **Miner**, and click **Install**.

## Build it yourself

```
./gradlew build          # produces build/libs/osrsx-miner-<version>.jar
./gradlew installPlugin  # copies it into ~/.osrsx/plugins
```

The entire build is `apply plugin: 'io.osrsx.plugin'` + the `osrsxPlugin { }` block in [`build.gradle`](build.gradle).

## License

GPL-3.0 (matching the osrsx client).
