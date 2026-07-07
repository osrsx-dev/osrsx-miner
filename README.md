# osrsx-miner

The **Miner** plugin for [osrsx](https://github.com/osrsx/osrsx-client). Pick an **ore** and a **location**;
the bot web-walks to that catalogued site, mines it, and banks or power-drops the ore.

- **Ores:** Copper, Tin, Iron, Coal, and **Motherlode Mine**.
- **Locations with real requirements.** Each ore has a curated list of sites (Varrock SE/SW, Rimmington,
  the Mining Guild, Dwarven Mine, Al Kharid, Ardougne, the Motherlode Mine, …). The Location dropdown only
  lists sites your account currently qualifies for — members, combat level and Mining level are checked
  live, so sites you can't use are hidden. An **"Auto — select best"** entry picks the eligible site nearest
  the bank (when banking) or nearest you (when dropping).
- **Motherlode Mine** is a full routine: mine pay-dirt (upper level optional), deposit it in the hopper,
  collect the washed ore from the sack when it fills, bank it, and repair broken water-wheel struts. Its
  location and bank options are hidden — MLM always banks.
- **Best pickaxe** is acquired via the Loadout API (withdrawn from the bank, or bought if you own none).
- Fully **self-contained** — no external skilling library — and instrumented with the SDK Profiler
  (`miner/…` spans, zero-overhead when profiling is off).

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
