# Project Role — Read This First

This is a short signpost, not a full state doc. For the complete picture of the project this repo belongs to —
vision, architecture, milestone status, research findings — see **`osrsproject/docs/PROJECT_STATE.md`** (sibling
repo). That file is the canonical source of truth across the whole project; this one just orients you to what
*this* repo is for.

## What this repo is

`elvarg-rsps` is the **Java environment side** of an OSRS reinforcement-learning project. The RL agent is trained
in Python (`osrsproject`, sibling repo) to play OSRS melee combat; this repo hosts the game engine that agent
will eventually train against — a private-server (RSPS) reimplementation of Old School RuneScape, used as the
sim-to-real validation step before the live game.

This repo is a fork of [`RSPSApp/elvarg-rsps`](https://github.com/RSPSApp/elvarg-rsps), tracked via a `rspsapp`
git remote for pulling upstream changes.

**Training NEVER runs here.** This repo only hosts the environment; the trainer, the RL logic, and all model
code live in `osrsproject`.

## What lives here

- **`ElvargServer`** — the Elvarg game server itself. Builds and boots headless on **Java 17**:
  ```
  cd ElvargServer
  ./gradlew run       # or .\gradlew run on Windows
  ```
  Reaches a live "RspsApp is now online!" ready state. The game cache/data ships with this fork already — no
  separate cache-acquisition step needed.
- **The client** — NOT built, NOT needed. Requires Java 11 (vs. the server's Java 17) and has no role in
  training.
- **PvM environment class** — **(to be built)**. Will own observation encoding and action application
  server-side, the Elvarg equivalent of Naton1's `NhEnvironment.java`, simplified for single-agent PvM (no
  self-play, no opponent-observation tracking).
- **Socket server** — **(to be built)**. Will accept the Python trainer's connection and expose
  reset/step/login/logout over a socket, so the Python-side socket-client env (built in `osrsproject`) can drive
  this server as a Gymnasium environment.

## Known-fixed issues

- **NPC attack-accuracy fix** (commit `28746de3`, `master`): NPC effective attack level was missing the OSRS
  Wiki's documented +1 neutral-stance bonus (`level + 8` instead of the correct `level + 9`). Fixed and verified
  live against a real Hobgoblin (npc id 3049). Full audit, verification numbers, and process notes are in
  `osrsproject/docs/PROJECT_STATE.md` (sections 9-10).

## Fidelity is not assumed

This is a private-server reimplementation, not an official Jagex artifact — its mechanics are verified against
the OSRS Wiki per-subsystem before anything is built or trained on top of them, not trusted by default. See
`osrsproject/docs/PROJECT_STATE.md` section 4 for the full operating principle.
