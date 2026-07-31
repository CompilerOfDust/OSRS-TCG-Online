# CLAUDE.md — thecardexchange-tcg (RuneLite plugin)

The **TheCardExchange TCG** RuneLite plugin (`com.thecardexchange.tcg.TheCardExchangeTcgPlugin`) — our
card game for Old School RuneScape, wired to the OSRS Card Exchange. Java / Gradle.

- `./gradlew.bat run` side-loads the plugin via the `*PluginTest` main (`ExternalPluginManager.loadBuiltin`
  → `RuneLite.main`) in `--developer-mode`; stops at the login screen, click **Play**.
- `./gradlew.bat shadowJar` packages a self-contained jar (used by the two-client dev harness).

## The backend is the Bun api — always

The plugin talks to the **Bun `api` service** (`../api`), **never the Next.js app**. Its API base URL
defaults to `http://localhost:3001` (`TheCardExchangeTcgConfig.DEFAULT_API_BASE_URL`), overridable via the
`THECARDEXCHANGE_API_URL` env var, `-Dthecardexchange.apiUrl`, or the config field. **The Bun api is the
project's API going forward — the official API; the Next.js app's API is legacy and we are migrating away
from it.** Every endpoint the plugin calls lives in the Bun api: device-pairing (`/api/v1/plugin/link/*`),
`/api/v1/plugin/session`, and the in-game trade broker (`/api/v1/plugin/trade/*`).

## Layout

- `account/` — device-pairing account link. `AccountLinkManager` owns the flow and holds the `octp_`
  plugin token in RuneLite config; `ExchangeApiClient` is the thin HTTP wrapper over the Bun api;
  everything the plugin does authenticates with the token.
- `trade/` — in-game card trading, end to end. Right-click **"Trade Cards"** on a player → offers a trade
  via the Bun api's WebSocket broker; the other side accepts (clickable chat line or a reciprocal "Trade
  Cards" click), and both open `TradeWindow` — an Old School trade-interface styled window **painted on
  the game canvas** (a RuneLite `Overlay`, not a pop-out Swing frame) that consumes every mouse event
  inside its bounds so clicks never reach the game behind it. It opens with the card list
  (`CardPacksInterface`) in trade mode: **duplicates only** (quantity ≥ 2 — your last copy never trades),
  no close button, counts shown minus what's on the table. Clicking a card offers it (`offer_cards` →
  server validates → `trade_cards` pushed to **both**, so the windows can't disagree); clicking it in
  your trade slots takes it back off. **Accept settles**: once both sides accept, the server moves one
  copy of every offered card between the two account characters in one transaction (re-validating
  duplicates inside it), both windows close, and the collection/locks refresh. Any offer change resets
  both accepts. Wire parsing lives in `TradeSocket` — every server message type needs a `case` there or
  it is silently dropped as `UNKNOWN`.
- `packs/` — two orbs, one on each side of the screen. `CardPacksOrb` paints a 40px orb pinned to the
  top-right corner of the canvas (Mystic cards item icon, item id 27645, on a violet plate that lifts on
  hover) — deliberately *not* anchored to the game's minimap orb column, which runs straight into the tab
  row. Clicking it toggles `CardPacksInterface` over the inventory: a search field that filters locally
  and the **collection grid** — the whole catalogue ordered by `Card.id`, four to a row, scrolling by
  whole rows, owned cards drawn with their picture and clickable, unowned ones the card back dimmed;
  credits show in the footer. **Opening packs is its own ceremony**: `PackOpeningOrb` (top-left corner,
  wearing the pack artwork) toggles `PackOpeningInterface` — a modal scrim over the viewport with the
  pack at full size in the middle. Clicking the pack posts to the api; the pulls land as a row of
  face-down card backs, clicking each turns it over onto the `card_front.png` template (artwork in the
  art window, name in the banner, gem tier + NEW/×N on the parchment box), and once all are up Continue
  puts the pack back out for another. It swallows every mouse and key event while open (Escape closes)
  and refreshes the collection view + item locks after each pack.
  - Backed by three plugin-token endpoints: `GET /api/v1/plugin/cards` (whole catalogue, fetched once a
    session), `GET /api/v1/plugin/collection` (credits + owned), `POST /api/v1/plugin/packs/open`. **The
    server owns the economy** — price, tier roll, foil roll, card pick; the plugin only asks and draws.
  - The wire carries the gem tier (`t` 1-7), curated-special flag (`sp` — gold framing in grid and
    detail), the examine-line description (`d` — drawn on the card face's parchment box), a cluster
    master's unlock ids (`u` — named in the detail view via the client's own definitions; the item
    *locking* still runs on the server-computed unlocked set) and combine recipes (`cf`/`ci`, integer
    card ids — shown read-only in the detail view; the craft *action* awaits the consume-or-keep design
    decision, CARDS.md §8). Root `CARDS.md` is the
    behaviour contract; the full-fidelity snapshot lives in `cards/` (re-copied from `api/cards`).
  - `CardArt` supplies pictures: item cards use the client's own item icons (`ItemManager`), NPC cards
    have no client-side sprite so their wiki art is fetched in the background and cached (never on the
    render thread — a miss returns null and the next frame gets it).
  - Assets `pack_standard.png` / `card_back.png` in resources are the site's `Pack_Standard.webp` /
    `Cardback.webp` converted and downscaled; `card_front.png` is the card-face template the reveal
    paints onto. `CardPacksManager` is just the start/stop wiring for all four overlays.
  - **Sounds** (`CardSounds`): `pack_opening.wav` on the pack click; then, once the server says what
    was inside, a flourish for a rare pull — `special_opening` if any card is a curated special, else
    `zenyte_opening` if any is Zenyte (it can't play on the click, since nothing knows the contents
    yet); then a woosh per card as it turns — `woosh_1` below Diamond, `woosh_2` for Diamond/Onyx,
    `woosh_3` for Zenyte. `tick.wav` taps as the cursor arrives on a dealt card, edge-triggered on the
    card index so a sweep along the row is one tap per card and sitting still is silent. They are **WAV because
    `javax.sound.sampled` has no MP3 decoder**; the sources are the root `sound effects/*.mp3` (the
    `*-old.mp3` files are superseded takes, not used), converted with ffmpeg to mono 44.1 kHz 16-bit.
    Check a new source for leading silence before bundling it — a woosh that starts late makes the flip
    feel laggy — and trim any trailing tail, which is pure jar weight. Each plays from its own
    `Clip` so quick flips overlap, and playback runs on the scheduler — opening a mixer line can block.
    Config: "Pack sounds" and "Pack sound volume".
- **Every request that touches a collection names its character** (`X-TCG-Character`), and the server
  resolves a binding it holds. This is what stops an alt being served the linked character's collection —
  it is a server-side property, not a client discipline, so a modified plugin can't get round it. The
  server falls back to an account's sole binding when the header is absent, which is what keeps older
  builds working; that fallback comes out once nobody is on them.
- `items/` — the **item lock**, this game mode's core rule: an item you have no card for is greyed with a
  padlock (`LockedItemOverlay`, over inventory / equipment / bank) and its use is consumed
  (`ItemLockManager` on `MenuEntryAdded` + `MenuOptionClicked`). An item is unlocked when a card you own
  *is* it (`gameIds`) or unlocks it (`unlocksItems`); the server computes that set (`unlocked` on
  `/collection`) and the catalogue's `collectable` set is what tells "not earned yet" from "no card
  exists, so never lockable". **Always allowed:** taking items off the ground, banking (deposit and
  withdraw), drop/destroy/examine/remove, and *all* skilling — the lock never touches world objects.
  Config: "Lock uncollected items". Written from our own data. Known limitation of any menu-based lock:
  keybind and spacebar-"make" actions bypass `MenuOptionClicked` and can't be consumed.
- `mode/` — the **game mode**: `GameMode` (`NOT_SELECTED` / `NORMAL` / `CARDMAN`) and `GameModeManager`.
  The mode is **per character**, stored in RSProfile config (`getRSProfileKey()` is null before login, so
  the panel has an explicit logged-out state), and **the server is the authority** — `cacheServerMode()`
  is a write-through cache of what `POST /v1/plugin/character/mode` returned, never a decision. A one-shot
  `adoptLegacyMode()` migrates the old global key. **CardMan** is the community *Cardcore* ruleset (an item
  is yours only once you've pulled its card) and requires a brand-new ironman account, checked server side;
  **Normal** is unrestricted play. **CardMan trades only with CardMan, Normal only with Normal**, enforced
  by the broker.
- `account/CharacterSnapshot` + `account/CharacterTracker` — **who is playing, and how they're doing.**
  `CharacterSnapshot.capture(Client)` is the *only* place the client is read (on the client thread, from
  `onGameTick`), and it carries the **raw ACCOUNT_TYPE varbit** — never `AccountType.values()[n]`, which
  throws on unranked GIM. The tracker binds on login, heartbeats every 5 minutes, closes the session on
  logout, and holds the server's verdict (mode, review state) for the panel. Nothing is sent from
  seasonal/beta/tournament worlds. `CharacterState` is the server's answer; the plugin renders it and
  decides none of it. **The full contract is `../api/docs/cardman-mode.md` — read it before changing any
  of this.**
- `ui/OsrsSkin` — the shared painted look (palette + plate/bevel/well/text primitives) every in-game
  interface is built from, so the trade window and card packs can't drift apart.
- **Both painted windows are alt-draggable** (alt+click anywhere moves them; without alt, clicks work the
  controls) and both anchor in *game* coordinates — `client.getRealDimensions()`, widget bounds — never the
  AWT canvas, which is the blown-up size under stretched mode.
- `TheCardExchangeTcgPlugin` / `TheCardExchangeTcgConfig` / `TheCardExchangeTcgPanel` — plugin entry,
  config, side panel.

## Dev

Two-account in-game testing runs two isolated dev clients via `../run-two-tcg-clients.ps1` (each with its
own `-Duser.home`, so configs / collections / Jagex sessions never collide). See the root `CLAUDE.md`.
