# CLAUDE.md — thecardexchange-tcg (RuneLite plugin)

The **OSRS TCG Online** RuneLite plugin (`com.thecardexchange.tcg.TheCardExchangeTcgPlugin`) — our card
game for Old School RuneScape, wired to the OSRS Card Exchange. Java / Gradle.

> The directory and Java package still say `thecardexchange`; only the *display* name changed (the
> `@PluginDescriptor` name and `runelite-plugin.properties` displayName). Renaming the package would
> break every stored config key, including the plugin token — the config group is `thecardexchangetcg`
> and that string is load-bearing. `panel_icon.png` is the brand mark: a card with a green centre dot,
> reused as the network badge, so the dot doubles as the "online" signal.

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
    server owns the economy** — price, tier roll, foil roll, card pick, *and now the grants*; the plugin
    only asks and draws.
  - **The collection view expands.** The compact panel is pinned over the inventory (~190px), which is
    why it only ever had a search box — there is no room beside a grid for anything else. The expand
    button beside the close button swaps it for a larger free-floating panel, centred in the game area,
    seven columns wide, with a **sort** strip (collected first / most duplicates / resale value / gem
    tier / name) and a **filter** strip (all / collected / duplicates / missing). The choice is
    remembered in config (`cardViewExpanded`). `COLUMNS` is therefore a *per-layout* number, carried on
    `Layout`, not a constant — the grid, the hit test and the scroll extent all have to agree with what
    the frame actually drew.
    - **Collapsing back to the compact panel resets the sort and the filter.** The small view has
      nowhere to draw those controls, so a filter left on would follow the player into a view that
      neither shows what is being hidden nor offers a way to undo it — half a collection missing with
      no visible cause reads as a broken grid, not as a filter working.
    - **The trade picker's duplicates-only rule outranks the filter and must keep doing so.** It exists
      so the picker can never offer a card you own exactly one of; it ANDs with whatever the player
      chose and wins. `CardGridTest` guards this — it is the thing generalising the filter could quietly
      break.
  - **Duplicates sell back**, from the card detail window: a "Sell spare" button, shown only at two or
    more copies, priced by the server (`saleValues` on the catalogue response — never hardcoded here,
    because the rate is pinned to the pack price). **The last copy can never be sold**, and that is a
    safety rule rather than politeness: the item lock is derived from cards held, so selling out of a
    card would take away a game item you had unlocked. The server enforces it; the button being hidden
    is a courtesy.
  - **`Wallet` is the one place the balance lives.** It used to be a private field in each of the two
    windows, filled in only when that window opened — fine while spending was the only thing that moved
    it, useless once the server started *granting* credits (3,000 at first bind, 550 a skill level), since
    a reward would have been invisible until the player next opened something. Everything that learns a
    balance writes it: the collection fetch, the pack response, the item-lock refresh, and — the one that
    matters — **every character heartbeat**, which the plugin already sends every 5 minutes and within a
    minute of any level-up. `CharacterTracker.clearCharacter()` clears it, or hopping to an alt would show
    the main's wallet.
  - **The credit balance sits at the top centre of the screen** (`CreditsBanner`, config "Credit
    balance"). Credits are now *earned* — levels, quests, diaries, boss kills, pack milestones — so the
    number moves while the player is doing something else entirely, and a reward nobody sees is not a
    reward. It reads `Wallet` like everything else, and **hides itself until the balance is known**
    rather than showing 0, for the same reason `Wallet` keeps "unknown" and "broke" apart. Top centre
    because the corners hold the two orbs and the middle of the top edge is the one place in an Old
    School client with nothing in it. `PRIORITY_LOW`, so it never paints over the pack ceremony.
  - **Both orbs are 54px.** Everything inside their `render` derives from `orb.width`, never from the
    `SIZE` constant, so the well, the icon fit, the rings, the readiness pip and the click test all
    follow a change to it — the pip in particular is sized proportionally *because* it has to stay
    inside the inscribed circle the hit test uses, or a bigger orb would hang it off the edge as an
    unclickable limb.
  - **The pack orb lights up when you can afford a pack** — a green pip bottom-right plus the rim
    breathing between its resting colour and `OsrsSkin.GOOD`, gated on `FeatureGate.isPlayable()` too so a
    held character is never invited to click. Note `Wallet.canOpenPack()` reads *unknown* as **not ready**,
    the deliberate opposite of `PackOpeningInterface`'s affordability check: one is deciding whether to
    disable a control, the other whether to make a promise. Don't unify them.
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
  - **`Showcase` is for cards worth more than their tier says.** Coins is common and Emerald, yet it is
    what unlocks spending money at all — so it gets the halo and the special fanfare while keeping its
    tier and `special: false`. Deliberately neither of the two easy routes: the `special` flag has its
    own curated register (`api/cards/special_item_unlocks.md`) and its own gold framing, so borrowing it
    would corrupt the register and make a showcase indistinguishable from a trophy; a tier bump would
    change pull odds and resale price for a card meant to stay common. It borrows only presentation, and
    in its own coin-gold rather than the special's antique gold. Keep the list short — everything on it
    is loud.
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
- **Two URLs, two origins.** `apiBaseUrl` is the Bun api; `webAppUrl` is the website (the game-mode
  guide, the marketplace). Both resolve property → env → baked-in local default, so a deployment points
  them without a rebuild. Don't build website links off the api base — in production they are different
  hosts.
- **`CharacterTracker.activeGameMode()` is the single answer to "which mode are we in"** — the server's
  reply once bound, the cache only for the gap before the first bind. The panel and all three card
  renderers read it; a second copy of that decision is how you get rose-tinted cards next to a panel
  saying Normal.
- **CardMan cards carry a silvery-rose wash** (`CardFace.CARDMAN_WASH`, blended at
  `CARDMAN_WASH_STRENGTH`), so the two economies — which cannot trade with each other — are tellable
  apart at a glance. A *blend*, not a replacement: the backdrop colour **is** the gem tier, so a flat
  wash would trade the mode signal for the rarity one. Set the strength to 1.0 if that ever inverts.
- **The panel greys out "Play CardMan" for a character that has already trained**
  (`CharacterSnapshot.isBrandNew()` — every skill 1, Hitpoints 10, derived per skill, never hardcoded to
  33). A **pre-check only**: the server still runs the real ironman + freshness + hiscores gate, because
  anything a client decides is forgeable. "Unknown" (no snapshot yet) reads as *not* allowed, never as
  allowed. The panel also links to `/guide/game-modes` on the website for the full rules.
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
  throws on unranked GIM. It also carries **finished quests and completed diary tiers**, as integer ids
  the server pays credits for. Two things there are load-bearing: `AchievementDiaries.VARBITS` **must
  never be reordered**, because its indices *are* the ids the server has already banked (it is one
  contract with `api/src/lib/osrs/diaries.ts`, written twice); and an **empty list is never a reset** —
  quest varbits aren't populated for the first tick or two after login, and the server's ratchet only
  adds, so an early empty claim costs nothing. The tracker binds on login, closes the session on logout, and holds the server's verdict (mode, review
  state) for the panel. **Reporting is event-driven with a 5-minute safety net**, not a poll: gaining a
  real level, finishing a quest or completing a diary tier pulls the next report forward within seconds
  (`nudge()`, 5s debounce), so credits land while the player is still looking at the fireworks. Two
  details there are load-bearing — `StatChanged` fires on *every* XP gain, so the tracker compares the
  **level** rather than treating the event as a level-up (throttling the event instead, as this first
  shipped, meant training anything produced a beat a minute and a real level still took up to one); and
  the milestone count is compared against what the server **accepted**, not against the previous tick, so
  a failed report keeps retrying instead of being marked done. Nothing is sent from
  seasonal/beta/tournament worlds. `CharacterState` is the server's answer; the plugin renders it and
  decides none of it. **The full contract is `../api/docs/cardman-mode.md` — read it before changing any
  of this.**
- `network/` — the **in-game network badge**: an icon beside other OSRS TCG Online players who are
  logged in. `NetworkPresence` polls `GET /api/v1/plugin/network/online` every 30s and holds the online
  set; `NetworkBadge` appends **two** marks to `client.getModIcons()` — the same card in a dark face for
  Normal and a red one for CardMan, both keeping the green "online" dot, so the dot means *online* and
  the face means *ruleset*. Chat and menu names carry an `<img=N>` tag (the same mechanism as
  clan/ironman icons — client thread only, and idempotent, or a world hop leaks a slot per hop; both
  variants are appended in one pass so a partial failure cannot badge one ruleset and not the other); `NetworkBadgeDecorator` prefixes chat names and player menu entries;
  `NetworkBadgeOverlay` draws it above nearby members, capped at 30 a frame so a crowded bank stays
  readable. Config: "Show network badges" plus a toggle per placement, and **"Show me as online"**,
  which pushes to the server (`POST /network/visibility`) because only the server can actually stop
  publishing you.
  - **Nothing about who is nearby leaves the machine.** The list is downloaded whole and matched
    locally, deliberately: per-name lookups would mean sending third parties' names to a server, which
    plugin review refuses. Do not "optimise" it into a lookup.
  - Two things there that look like micro-details and are not: the overhead image is resolved **per
    player**, not hoisted out of the frame loop (a CardMan and a Normal standing together would
    otherwise share whichever badge was looked up first), and "already decorated" matches **either**
    variant (somebody can change ruleset between one chat line and the next).
  - **This cannot change how anyone appears in the normal game.** Only players running this plugin see
    the badge; Jagex renders the client and nothing here touches an actual RuneScape account.
  - **The presence set also gates "Trade Cards"** (`CardTradeManager.addTradeCards`): the entry appears
    only on players who are on the network, because an offer to anyone else is one the broker can never
    deliver. So "Show me as online" is not purely cosmetic — hiding yourself also stops others
    right-clicking you into a trade. Keep the config copy honest about that.
- **`FeatureGate` is the single answer to "may this character play".** Blocked when the install is not
  linked, the character was released from the account's profile, it belongs to a different account, or
  it is held for review. The pack orb, the collection orb and `requestTrade` all consult it, so they
  cannot disagree — before it existed the panel showed a hold while the orbs still opened, which reads
  as the hold not being real. Every reason in it is one the **server** decided; nothing is judged
  locally, so a modified client can re-enable its own buttons and still be refused by every endpoint
  behind them. It is honesty for the player, not a security boundary.
- **`ui/BlockedNotice` is the dialogue that says why** — painted on the canvas like the other windows,
  not a Swing pop-up, which would steal focus mid-game and can land on another monitor. It shows the
  server's own words, because the reasons need different actions (link an account, re-link a character
  on the website, wait for a review) and "unavailable" leaves a player nowhere to go. It closes itself
  if the reason clears while it is up, and `CharacterTracker`'s listener shuts the open windows when a
  hold lands mid-session rather than waiting for the next click to be refused.
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
