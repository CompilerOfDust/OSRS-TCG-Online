# Card catalogue changelog

A running log of every change to the **card catalogue** under `api/cards/` — cards
added, removed, filtered, or bulk-edited, and schema changes that affect all cards.

**Policy:** whenever a request filters or changes the cards (delete a set, add/rename a
field, bulk-edit values, populate `unlocksItems`, etc.), add an entry here **with a
reason** before/alongside making the change. One entry per request; newest first. Record
what changed, how many cards, and *why*.

Dates are ISO-8601. Card counts are per catalogue (the `api/cards/` set).

---

## 2026-07-31

### Migration history squashed to a clean baseline (third-party seed data removed)
The changelog scrub below fixed the prose; the **migrations still carried the third-party
plugin's actual data**. `20260722070000_cards_and_exchange_trading` was 1.5 MB containing a
`-- Seed the Card table from the … plugin's Card.json (6376 cards)` header and 6,376 embedded
INSERT rows (names, flavour text, tags, art URLs) graded with a `RarityTier` enum
(`COMMON`…`GODLY`); `20260724040000_card_tiers_from_plugin` was named after it and moved every
card onto "plugin-faithful rarity tiers". Four more migrations named it in comments.

Nothing had been deployed, so the history was **rebuilt rather than patched**: all 60 legacy
migrations were replaced by two —
- **`20260722000000_baseline`** — the entire current schema (933 lines), generated from the
  live database so it reproduces it exactly, and
- **`20260722000001_npc_avatar_seed`** — the 4,426 NpcAvatar rows (account profile pictures
  from the wiki NPC crawl), regenerated from the live table so they match today's columns
  (the legacy seed still wrote a `facing` column that a later migration had dropped).

The dropped `Quest`/`Minigame*` catalogue seeds needed no carrying forward — later migrations
had already dropped those tables. The card catalogue comes from `bun prisma/seed.ts`, not from
migrations, so no card data lives in the history at all now.

**Verified equivalent**, not assumed: a scratch database built from `prisma migrate deploy` +
`seed.ts` was diffed against the working database — schema `pg_dump` byte-identical (742 lines,
after fixing one real drift the generator dropped, `NOT NULL` on `Card.unlocksItems`),
NpcAvatar md5 identical over 4,426 rows, and 7,869 cards / 26 specials / identical tier hash /
identical profile counts. The dev database's `_prisma_migrations` was re-pointed at the new
baseline via `migrate resolve --applied`, keeping all local data.
**Reason:** requested — the migrations must contain no reference to, or content from, the
third-party plugin, while a fresh `migrate deploy` still reproduces the exact same database.
(Scope: the `api` only. The Next.js app's migrations were deliberately left alone.)

### Changelog restated in this project's own terms; working docs cleared out
Rewrote **10 passages** that had justified early sweeps by pointing outside this project rather
than at our own rules. Each entry keeps its reasoning, restated against the rules that actually
govern the catalogue: one card per real collectible, props and bundles are not collectibles,
scrape artifacts are not items. This catalogue's history stands on its own reasoning.

Also cleared the working documents the card set was built from: deleted
**`removal_suggestions.md`** (the review backlog, now fully actioned) and repaired **6 dangling
references** to audit files that no longer exist (`quest_npcs.md`, `quest_process_props.md`,
`junk_items.md`, `warm_clothing.md`, `event_rewards.md`, `slayer_variants_combine.md`) — each
now describes the audit instead of citing a missing file. The plugin's bundled `cards/` keeps
**only `CHANGELOG.md`** alongside the card JSONs; `big_unlocks.md` and
`special_item_unlocks.md` were dropped from the snapshot (nothing in the plugin reads them).
**Kept in `api/cards/`:** `big_unlocks.md` (the generated cluster-master index required by
`api/CLAUDE.md`) and `special_item_unlocks.md` — the latter is **not documentation**, it is the
specials register `scripts/check-tiers.mjs` reads to set `special: true` and force Zenyte;
deleting it would break the tooling and lose the definition of all 26 specials.
**Reason:** requested.

### Every card re-tiered by hand; the tier-deriving script is retired
**4,994 of 7,869 cards changed tier.** Tiers are no longer computed. The old
`scripts/derive-tiers.mjs` ranked cards on GE price, high-alch and stat totals, which is
structurally wrong — prestige in OSRS is not in the card data. It had put **Infernal cape and
Fire cape on Sapphire**, **Scythe of vitur, Blue partyhat and Dizana's quiver (broken) on
Opal**, and lifted **Abyssal whip, Dharok's greataxe, Dragon scimitar and Thermonuclear smoke
devil to Zenyte/Onyx**. Radiant oathplate — one of the game's hardest grinds — sat on Onyx.

- **New doctrine: `AGENT-TIERING.md`** at the workspace root — the runbook for re-tiering.
  Tier = how much of a trophy the real thing is in OSRS (difficulty, rarity, prestige), never
  price/stats/unlock-count. It defines all seven tiers with worked anchors, per-category
  playbooks (combat gear, untradeables, skilling, consumables, quest items, NPCs, cosmetics),
  population targets, the process, and a calibration log of this run.
- **Two absolute rules, now actually enforced:** every `special` is Zenyte (the old
  `SPECIAL_TIER_OVERRIDES` that held Bryophyta/Obor/Ale of the gods on Onyx and Merlin on
  Diamond are gone) and every pet is Zenyte.
- **`pet-zenyte.json` cleaned, 94 → 76.** It was driving a hard rule while containing 8 cardIds
  for cards that no longer exist and 10 non-pets. Removed the stale ids, the crafted Toy cat,
  and 9 **NPC-follower** items (Nieve, Grubfoot, Dr Banikan, Elias White, Knight of Varlamore,
  Ivan Strom, Veliaf Hurtz, Silif, Prince Itzla Arkan) — wiki slug `<Name>_(item)`, examine
  "Follower obj". They are now hand-tiered (Ruby). The novelty pets whose slugs really are
  `…_(pet)` (Beef, Soup, Gull, Pheasant, Fox, Fishbowl, Mayor of Catherby, Pet rock) stay.
- **Tooling:** `derive-tiers.mjs` deleted and the derivation half of `src/lib/tcg/tier.ts`
  (`deriveTiers`, `tierSignal`, `naturalBreaks`, …) removed — that module is display-only now.
  Added `scripts/apply-tiers.mjs` (writes hand-assigned tiers from TSV) and
  `scripts/check-tiers.mjs` (enforces the two rules, syncs the `special` flag, prints the
  distribution, flags variant/base mismatches). `package.json`: `derive-tiers` → `apply-tiers`,
  `check-tiers`, `big-unlocks`.

Resulting distribution — Opal 3,127 / Sapphire 2,066 / Emerald 1,297 / Ruby 704 / Diamond 312 /
Onyx 181 / Zenyte 182. Sample corrections: Infernal cape, Radiant oathplate, Oathplate, Scythe
of vitur, Blue partyhat, Dizana's quiver (all forms), 3rd age (all), TzKal-Zuk, Verzik, Yama →
**Zenyte**; Bandos chestplate, Ghrazi rapier, Zulrah, GWD generals, boss jars → **Onyx**; Fire
cape, Barrows sets, Abyssal whip, Amulet of fury, Barrows gloves, Dragon defender → **Diamond**;
Dragon scimitar → Ruby; Rune platebody, Shark, Graceful → Emerald; Bones, Coins → Sapphire;
Goblin and Chicken → **Opal** (a master unlocking 137 spawn variants is still a goblin).
**Reason:** requested — the ranking felt off (Radiant oathplate only Onyx, Fire cape far too
low), specials must always be Zenyte, pets must always be Zenyte, and tiers should be judged by
hand from game knowledge rather than produced by a script.

### removal_suggestions.md actioned: 458 cards removed, 335 folded/consolidated
Executed the reviewed suggestions list class by class:
- **Removed outright:** 46 `(unobtainable item)` cards, 62 discontinued/beta leftovers,
  19 minigame currencies/tokens/tickets (incl. Quest point cape & hood — the skillcape
  class was already excluded), 2 quest props (Iban's staff (u), Palm leaf).
- **Grouped, then removed:** 25 animation-item duplicates (17 folded onto the real item's
  `unlocksItems`, 8 with no base card removed outright); 6 cocktail process states folded
  onto Choc saturday / Drunk dragon; 298 same-name scoped duplicates folded onto their
  plain card.
- **Consolidated in place (no deletion):** 37 charge-variant cards re-pointed to their
  max-charge item with lower charges unlocked (Ring of dueling(8), Games necklace(8),
  Slayer ring (8), Void seal(8), waterskins, produce sacks, spice/tea/mixture chains…).
- **Left as-is (still listed in removal_suggestions.md):** 39 single-page dose-suffixed
  items with nothing to fold (live Shayzien tier armour, CoX provisions fish/bats,
  Victor's capes) and the 2 extra Pot of tea pot types. Catalogue 8,327 → 7,869;
  big_unlocks 672 → 900 masters.
**Reason:** requested — "action the removal suggestions".

### Guardians of the Rift minigame items removed (19 cards)
The in-minigame supplies: 12 portal talismans, the 5 cell tiers, Guardian essence, and
Abyssal pearls. GotR reward uniques (abyssal lantern, needle, dice…) keep their cards.
**Reason:** requested — minigame-only supplies.

### Deadman & Leagues mode-only content removed (78 cards)
Everything the wiki's own page categories mark Deadman-mode-only or Leagues-only: the DMM
Armageddon/sigil-era items (trinkets, charms, breach uniques like Thunder khopesh/The
dogsword, Ancient Warriors' `(Deadman Mode)` copies, starter gear, Sigil scroll era items),
and Leagues rewards/props (Demonic Pacts, Raging Echoes, Trailblazer, Shattered Relics —
scroll boxes, Banker's note, trophies). Wiki maintenance tags (`Needs_League_region`) were
explicitly ignored — live NPCs like Kruk and Amoxliatl are unaffected. **Blood money kept**
(main-game LMS currency, not DMM-exclusive).
**Reason:** requested — game-mode-exclusive items are not main-game collectibles.

### Removed-from-game sweep via wiki page markers (231 cards)
Every non-special card whose downloaded wiki page carries the removed-content signal — the
historical-page banner ("preserved for historical purposes") or an infobox **Removal** date
row: RS2-beta relics, `(unobtainable item)`/`(historical)` pages, superseded beta gear
(Nightmare staves, Inquisitor's, blowpipe betas), broken axes/pickaxes & tool heads
(removed with the degradation rework), echo orbs, PvP Championship/Wilderness Wars gear,
old gnome-cooking `historical` chains, Shayzien supply armour, and more. **Kept
deliberately:** General Bentnoze (Elvarg combine roster), Dr Harlow, Prince Ali — live
NPCs whose pages only document a removed *version*.
**Reason:** requested — cards must exist in today's game.

### Monkey greegrees folded into the Karamjan monkey greegree (8 cards removed)
The standard **Karamjan monkey greegree** (4031) now unlocks all other greegrees (ninja
small/medium, zombie small/big, gorilla/bearded/ancient, Kruk: 4024–4030, 19525).
**Reason:** requested — one greegree card.

### Barbarian Assault horns removed (4 cards)
Attacker, Defender, Collector and Healer horns — in-minigame role equipment.
**Reason:** requested.

### Slug Menace elemental rune variants folded into the base runes (4 cards removed)
Water/Air/Earth/Fire rune (The Slug Menace) deleted; each base rune card now unlocks its
quest-variant id (9691/9693/9695/9699) alongside the Lunar Diplomacy ids it already had.
Catalogue 8,671 → 8,667.
**Reason:** requested — quest copies of runes belong under the standard rune card.

### Golden apron → Zenyte special: the Cooking ≤40 master
**Golden apron** (`itm_golden-apron`, 20208) joins the specials register (26 specials now):
its `unlocksItems` holds **all 219 items cooked with the Cooking skill at level 40 or
below** — derived from each wiki page's embedded recipe data (min Cooking level ≤ 40,
burnt versions excluded). Covers standard foods through Lobster/Cake/Plain pizza, gnome
cooking, sq'irkjuices, and cooking intermediates (doughs, pie shells, half-baked states).
**Reason:** requested — a curated cooking trophy card.

### Seedlings folded into saplings (23 cards removed)
Every tree/fruit-tree/special seedling card was deleted; the matching **sapling** card now
unlocks the seedling's plain + watered item ids (2 each, from the wiki version data).
**Reason:** requested — a seedling is the sapling's growth stage, not a separate collectible.

### All 42 ornament kit cards removed
Every "* ornament kit" (incl. the 5 `(beta)` duplicates). The plain quest item "Ornament"
(4432) is not a kit and stays.
**Reason:** requested — cosmetic attachment kits are variants, not collectibles.

### Misc props removed (23 cards)
Fresh fish, Ancient ledger, Bulging taxbag, Poisoned cheese, Coenus (NPC), Corrupted shark,
the 7 historical Unfinished cocktail cards, Tarnished bracelet, Viking toy ship (plain Toy
ship stays), Healer icon, Big frog leg, Prop sword, Metal spade, Twigs, and the stuffed
snake chain (raw/odd/stuffed). Catalogue 8,759 → 8,671 across these entries.
**Reason:** requested — quest/minigame props and process items.

### Ironman armours removed (21 cards)
The account-mode armour sets — Ironman / Ultimate / Hardcore helm+platebody+platelegs,
Group and Hardcore group (+ bracers), and the unranked group pieces. Catalogue
8,780 → 8,759.
**Reason:** requested — mode badges, redundant as collectibles.

### Holiday-event sweep: 235 non-special seasonal cards removed
Removed every card tied to a holiday/seasonal event (per the wiki page's own
`Category:<year>_<holiday>_event` footer categories, requiring either `Discontinued_content`
or a matching `Content_released_in_<year>` tag so permanent content that merely appeared in
an event — Crystal halberd, Aggie, Duke Horacio — is spared). Covers event props, event food,
reward cosmetics (marionettes, baubles, gingerbread shields, pumpkins, candies, sweets,
destabilisers, handeggs, cookout chain, caged monkeys…), the base Snowball, and the two
surviving 2025 Halloween NPC copies (Death, Stray dog). **Giant easter egg — previously a
216-id unlock cluster — was removed too** (not in the specials register; its unlocked ids were
themselves holiday items). Only `'24-carat' sword` matched and was kept as a special.
**Reason:** requested — holiday-event content is not collectible unless curated as a special.

### One-off removals: props, junk and minigame supplies
Deleted on request: **No eggs**, **Gold paint**, **Gertrude's cat (NPC)** + the Gertrude's
Cat quest **Kitten**/**Crate** props, the 5 quest **certificates** (Dig Site levels 1–3,
Half certificate, Death Plateau), the 28 **coloured shape** tokens (ids 9597–9624), **Token
(Varlamore)**, all 4 **Bandages** cards, **Plague frog**, and the **Huge snowball** size
family + snowball dupes (10 cards; base Snowball later fell to the holiday sweep). Also swept
while touching the area: **Combat potion (Deadman starter pack)** (DMM class was already
excluded) and 3 **Sailing-beta** cards carrying `beta` item ids that collided with live ids
(Colossal salvage, Sunken locket, Spooky salvage — live "Old …" souvenir cards remain).
Catalogue 9,367 → 8,780 across today's entries.
**Reason:** requested — quest/minigame props, junk items and stale beta scrapes are not cards.

### All 23 ensouled heads removed
Sacrifice-fodder items, not collectibles.
**Reason:** requested.

### PvP Arena armours removed (32 cards)
The Emir's Arena reward armour line — Calamity chest/breeches (+ Superior/Elite), Saika's
hood/veil/shroud, Koriff's headband/cowl/coif, Maoma's med/full/great helm, Wristbands of
the arena — each of which also had a duplicate `(beta)` card sharing the same game id.
**Reason:** requested — arena-only supplied gear is not collectible.

### All 153 crate cards removed
Every item named "crate": the ~140 Sailing cargo "Crate of X" items, fish/supply/bounty
crates (the discontinued Bounty crate's 9 unlock ids become uncovered ⇒ always available),
Crate part, Crate with zanik, and the Gertrude's Cat "Crate" scenery NPC.
**Reason:** requested — transport containers are not collectibles.

### Pet cat line condensed to one card (6 removed)
**Pet cat** (1561) is now the single cat card, unlocking all 35 variant ids: kittens
(1555–1560), cat colours (1562–1566), overgrown (1567–1572), lazy (6549–6554), wily
(6555–6560), the hellcat growth line (7581–7585) and Fluffs' kitten (1554). Removed the
Kitten, Overgrown/Lazy/Wily cat, Hellcat and Fluffs' kitten cards.
**Reason:** requested — one card per pet line; colours/growth stages are variants.

### Potions consolidated to their max dose (142 cards re-pointed, 35 mixes folded)
Every dosed drinkable card — which had been scraped as its **(1)-dose** — was re-pointed to
its **highest dose**: name, art, file id and `source.gameIds` now use the (4)-dose item (or
(2) for barbarian mixes while they lasted), with every lower dose id in `unlocksItems`, so
owning e.g. Prayer potion(4) unlocks (3)/(2)/(1). Dose ids come from each wiki page's
version-switch data. Also covered the dosed vials Olive oil, Sacred oil, Serum 207/208, the
keg ales (Greenmans ale, Mind bomb; Ale of the gods' unlock list gained the keg dose ids),
and Armadyl brew (after the beta-id collision above was cleared). `cardId` slugs keep their
historical `-1` suffix so player collections survive the reseed. The **35 barbarian +
butterfly mix cards were then deleted** with their two dose ids folded into the base card's
unlocks — classic mixes onto their base potion (Super str. mix → Super strength), butterfly
mixes onto their butterfly item card (Ruby harvest mix → Ruby harvest).
**Reason:** requested — a potion should be one card, the (4) dose, unlocking all doses;
mixes are variants of the base potion.

### Max cape variants and all max hoods removed (31 cards)
The 16 variant max capes (Fire/Infernal, god + imbued god, Accumulator/Assembler/Masori,
Ardougne, Mythical, Dizana's, 2 animation-item dupes) and all 15 max hoods (base + variants).
**Max cape** (13280) remains the sole master and already unlocks all 16 variant cape ids;
the hood ids are now uncovered ⇒ never lockable.
**Reason:** requested — variants of the max cape are not separate collectibles.

### Clue-provider containers removed (15 cards)
Clue geode / Clue nest / Clue bottle, beginner→elite. All had empty unlock lists.
**Reason:** requested — items that merely deliver clue scrolls from activities (mining,
woodcutting, fishing) are transient containers, not collectibles.

## 2026-07-30

### Removed 98 NPCs no longer in the game (discontinued content)
Prompted by spotting **MerryMax2000** (a fake-player NPC from the discontinued 2021 Christmas
event). Swept every NPC card whose wiki page carries a removal date (`retired`) or the
**Discontinued content** category: the fake-player NPCs (MerryMax2000, Unicorn1337Kilr,
CharlieChimes06, Ebenezer1843, …), the 2013 Goblin Invasion cast, the 30 Bloodthirsty
(Deadman Apocalypse) monsters, Realm of Memories copies, every 2021–2025 seasonal-event NPC
copy (Santa cast, birthday-event copies of Hans/Duke/WOM/etc.), Leagues echo foes, and old
removed NPCs (Rowdy Guard, Witch (monster), Painted Goblin, Head mourner…). **Kept with
care:** Juna (flagged only by a cache-id drift — she is live), Prince Ali (the `retired` flag
belongs to the disguise page; the card is the real quest character), and **Cook** — the
condense pass had wrongly kept the 2022-event copy as the family master, so the real
**Lumbridge Cook was restored from git** and given the family unlock ids; the event copy was
removed. Note: deleting the event Sheep master leaves live sheep ids uncovered (uncovered =
never lockable, so sheep remain always available). Tiers re-derived; `big_unlocks.md`
regenerated (543 masters). Catalogue 9,464 → 9,367.
**Reason:** requested — NPCs that no longer exist in the game are not collectible.

### Gilded cosmetics without unlock lines moved to Onyx, non-special
The three gilded pieces with no counterpart line to unlock — **Gilded spade, Gilded smile
flag, Gilded staff of collection** — left the specials register and now sit on **Onyx (6)**
via non-special TIER_PINS. The other 19 gilded pieces remain Zenyte specials with their
metal/ranged unlock lines (specials register now 25 cards).
**Reason:** requested.

### Removed Cabin Boy Jenkins
Deleted the `Cabin Boy Jenkins` card (825/826) and stripped its ids from Elvarg's Dragon
Slayer I unlock set (38 → 36) and from the Elvarg section of `special_item_unlocks.md` —
same treatment as Sawmill operator. Catalogue 9,465 → 9,464.
**Reason:** requested.

### Specials curation: Merlin combine, Ale of the gods wired, pets & Coins repositioned
Five curation changes in one pass:
- **Merlin → Diamond (5), special, with the game's second combine recipe.** The three knight
  cards (Renegade knight, Sir Gawain, Sir Lancelot) were **restored** (new integer ids
  10167–10169, natural tiers, not special): pulling Merlin unlocks all three
  (`unlocksItems`), and owning all three **combines into Merlin** (`combine.craftedFrom` on
  Merlin, `combinesInto` on each knight — the Rake pattern).
- **Ale of the gods → Onyx (6), special**, and its promised unlock is now wired: **every
  alcoholic beverage in the game** — 95 drink cards / 111 game ids (beers, ales + mature/keg
  forms, wines incl. sunfire/Zamorak, ciders, meads, stouts, spirits, grog, gnome cocktails +
  premades, the sailing bottle range). Excluded non-drinks: Ale yeast, empties, unfermented
  wine, brew potions, cargo crates.
- **All 94 pets moved up to Zenyte but are NOT special** — removed from the specials register
  into a dedicated non-special pin list (`scripts/pet-zenyte.json`).
- **Coins → Ruby (4), non-special currency master**: the base Coins card absorbed the three
  Coins location-variants plus **Tokkul** and **Trading sticks** (cards removed, ids
  unlocked: 617, 6306, 6529, 6964, 8890).
- **Ned verified single-card** (the duplicate was already folded during the condense passes)
  and now unlocks the folded Ned's id (818).
Derive script gains `SPECIAL_TIER_OVERRIDES` entries (Merlin 5, Ale 6) and non-special
`TIER_PINS` (pets 7, Coins 4). Catalogue 9,467 → 9,465 (+3 knights, −5 currency). Reseeded.
**Reason:** requested — per-card special placement, the Merlin combine mechanic, the Ale
mega-unlock, pets as top-tier non-specials, and one currency card.

### Added a `special` flag; Bryophyta & Obor moved to Onyx
Cards now carry a **`special` boolean** (schema + `Card.special` DB column, default false).
`derive-tiers` sets it from the `special_item_unlocks.md` register — all 122 register cards
are `special: true` — and the Zenyte pin gained **per-card tier overrides**: **Bryophyta**
and **Obor** sit at **Onyx (6)** while keeping `special: true`; the other 120 specials stay
Zenyte. Non-register cards omit the flag (schema default false). Migration
`20260730170000_card_special_flag`; reseeded.
**Reason:** requested — decouple "is a special" from "is top tier" so specials can be placed
on any tier.

### Special cards always rank Zenyte (122 pinned)
`derive-tiers` now reads **`special_item_unlocks.md`** and pins every cardId referenced in it
to tier 7 after the data-driven banding — that doc is the curated register of specials, so
editing it is how a card becomes (or stops being) one. Pinned today: the NPC specials (Elvarg,
Bryophyta, Obor, Merlin), '24-carat' sword, Ale of the gods (cardId added to its heading), the
22 gilded pieces and the pets (106 cards lifted; 16 were already Zenyte). Reseeded.
**Reason:** requested — special cards are the game mode's trophies and belong on the top tier.

### Condensed every remaining same-name NPC family (176 families, 232 removed)
Every NPC name is now exactly **one card**. Condensed all 176 remaining duplicate families —
the folded cards' game ids and unlock lists merged into each family's kept card (un-suffixed
slug preferred), then the duplicates deleted. Covers: the same-creature spawn families (Ghost
×6, Soldier ×6, Monk/Pirate/Black demon ×5, Snake/Troll/Cyclops/Mourner/Bartender ×4, …), all
the **Nightmare Zone / PvM Arena boss re-fights** (Kalphite Queen, KBD, TzTok-Jad, the GWD
four, Dagannoth kings, Callisto/Venenatis, RFD bosses, and every other `(Nightmare Zone)`
duplicate — the same being, one card), event-version characters (Wise Old Man, Duke Horacio,
Sir Amik, Gertrude, …), the odd display-name strays (Kovac → Hill Giant, Roger → Crocodile,
Haze → Ghost, Jim → Imp, the three "Mysterious ghost"s, the "Whirlpool"-named kraken trio),
and — per explicit decision — the distinct-character names too (**Bob ×5**, Mary, Charlie,
David, Larry, Sarah). The NpcID completion pass then ran over the new masters (**+738 ids** —
Ghost/Soldier/Monk/Pirate etc. now cover their full RuneLite id families). Tiers re-derived;
`big_unlocks.md` regenerated (525 masters, 3,287 unlocks); reseeded. Catalogue 9,699 → 9,467.
**Reason:** requested — finish the one-card-per-NPC-name rule everywhere, including the
name-sharing distinct characters.

### Condensed the 20 multi-card master families to one card each (60 removed)
Finished what Man/Guard started: every NPC master family that still had per-location spawn
cards is now a **single card**. Kept the base-slug card and folded the rest — their own game
ids and unlock lists merged into the master's `unlocksItems`, the duplicate cards deleted:
Skeleton (16 → 1, incl. the "Skeleton guard"/Wrath Altar strays), Zombie (11 → 1), Goblin
(9 → 1, incl. the unlock-less historical/2014-invasion/Realm-of-Memories spawns), Bandit
(5 → 1), Skeleton Hellhound (4 → 1), Dagannoth/Scorpion/Cow/Chicken/Red dragon (3 → 1 each),
and Hellhound, Wolf, Black/Blue dragon, Minotaur, Gryphon, Dagannoth Prime, Basilisk, Shade —
plus **Elvarg**, which absorbs its Nightmare Zone duplicate (36 → 38 unlocks). Same-name NPCs
that are *different characters or unrelated spawns without a master* (Soldier, Ghost, Black
demon, Bob, …) were left alone pending review. Tiers re-derived; `big_unlocks.md` regenerated
(383 split rows → 351 single-card rows, 2,087 unique unlocks); reseeded. Catalogue
9,759 → 9,699.
**Reason:** one card per creature family — the per-location spawn cards were duplicates of
the same collectible, kept only as unlock-id carriers, which the master's list now covers.

### Completed every NPC master's variant id list against RuneLite NpcID (+2,777 ids)
Applied the Man fix to **all 76 NPC master cards** (every npc card with `unlocksItems`,
Elvarg's quest roster excepted — that is a cast of distinct names, not a name family):
cross-checked each master's family against **RuneLite's legacy `NpcID` constants**
(runelite-api 1.12.33, the same authority used for Man) by display-name pattern
(`NAME`/`NAME_<id>`), plus the known cross-name families (Guard → the eight folded municipal
guards; Bryophyta → Moss giant; Obor → Hill giant; Merlin → his knights; Man → Woman, already
done). Every id RuneLite knows for the family and the card didn't cover was added — **2,777
ids across 76 cards**, e.g. Guard 194 → 338, Goblin 17 → ~160, Zombie 50 → ~140, Skeleton
13 → ~81, Bandit 1 → 27, Chicken 1 → 14, Obor 7 → 19. Nothing was removed; own `gameIds`
stay excluded per card (multi-spawn siblings now differ slightly, so `big_unlocks.md` rows
split: 351 → 383 masters, 5,001 total unlocks). Tiers re-derived; reseeded.
**Why:** same hole as Man — the item/NPC lock treats an id no card covers as never lockable,
so every missing spawn variant broke the game mode's core rule.

### Man cluster: completed the variant id list (23 → 60 unlocks)
The **Man** master card (`3014__Man.json`) was missing 37 of the game's Man/Woman NPC ids —
players could interact with unlisted variants (e.g. the Lumbridge men 3107/3108/3109 and women
3112/3113) without owning the card. Cross-checked the full set against RuneLite's `NpcID`
constants (70 ids named Man/Woman) and added every id not already covered to `unlocksItems`:
3107-3109, 3112-3113, 4268-4272, 6776, 6815, 6988-6989, 6991-6992, 7281, 7919-7922,
8858-8864, 9657-9658, 10672-10674, 10945, 11032, 13679, 13872. One card changed; the card now
covers all 70 ids (plus two legacy ids kept for safety). `big_unlocks.md` regenerated for the row.
**Why:** the item/NPC lock treats an id no card covers as never lockable, so every missing
variant was a hole in the game mode's core rule.


### Clustered all Man / Woman NPCs onto one Man card
Collapsed the 9 generic citizen cards (4 "Man" + 5 "Woman" spawn/variant cards — West/East
Ardougne, level-4, historical, Wanted!, and the Avan-file "Man") into the canonical **Man**
(`3014__Man.json`, wiki Man page): its `unlocksItems` now lists the other **23 game ids** and
the 8 other cards were removed. Tiers re-derived (Man ranks Emerald via the unlock-master
bonus); `big_unlocks.md` regenerated (351 masters). Catalogue 9,767 → 9,759.
**Reason:** requested — one card for the generic townsperson family, unlocking all Man and
Woman spawns ("unlocks downwards", same as Guard).

### Removed 147 flatpacks and skillcapes
Deleted the **101 remaining `(flatpack)` cards** (the tradeable POH furniture packs per the
wiki Flatpack page — chairs/tables/beds/wardrobes/lecterns/globes/telescopes/etc.; the built
furniture was already removed in the `Check materials` sweep) and the **46 skillcapes** (all
23 Capes of Accomplishment + their 23 hoods). **Kept:** the real drinkable ales/Chef's delight
(plain and POH-served variants — drinks, not flatpacks), the Halloween `Rocking chair`, and
the non-skill capes (Max cape master, Quest point / Achievement diary / Music capes). One
skillcape id stripped from a master's `unlocksItems`. Tiers re-derived; `big_unlocks.md`
regenerated. Catalogue 9,914 → 9,767. **JSON-only — the database is deliberately not
reseeded yet.**
**Reason:** requested — flatpacks are POH furniture in item form, and skillcapes are
account-progress markers rather than collectibles.

### Removed 252 Leagues / Deadman Mode reward cards
Deleted every card that is a **Leagues Reward Shop** or **Deadman Reward Store** reward
(datasource: the wiki pages for both stores), plus league/DMM-exclusive drops: all 86
**sigils**, the 7 `(deadman)` weapon/cape variants, Armageddon + Annihilation reward sets,
the league relic-hunter outfit masters (`… (t1)` hats/tops/trousers/boots for Twisted /
Trailblazer / Shattered / Trailblazer Reloaded / Raging Echoes / Demonic Pacts), banners,
canes, trophies, teleport/POH scrolls, ornament kits (incl. the `(o)`/`(or)` league ornament
variants of tridents, Iban's staff, Soulreaper axe), Echo gear (virtus/ahrim's/venator kits,
crystal, boots, pearl, league tools), Blazing blowpipe, Dinh's blazing bulwark (+rotten),
Oathplate/Radiant slayer helmets, Impish scroll/whistle, and the leagues-beta Soulreaper axe.
**Kept** the real content that shares branding: Twisted bow/buckler/ancestral set + colour kit,
the CoX Twisted potions, Demonic gorilla / sigil (quest item) / tallow / Brutus, Twisted
Banshee, Shattered gingerbread, the Rogue Trader `blackjack(o)` offensive variants, the real
Soulreaper axe — and **Venator bow** (its only card is the rotten-skin file but it unlocks the
real bow id 27612; removing it would orphan the weapon). Stripped the 3 league slayer-helmet
recolour ids (24370/33338/33340) from the Slayer helmet master's `unlocksItems`. Tiers
re-derived (`bun run derive-tiers`); `big_unlocks.md` regenerated (374 → 350 masters).
Catalogue 10,166 → 9,914.
**Reason:** requested — seasonal game-mode rewards (Leagues, DMM) are not part of the core
collectible catalogue.

### Populated `tier` with derived gem tiers (1–7) on all 10,166 cards
Repurposed the integer `tier` field (previously an unused "special-item tier" placeholder — every
card was 1) as the card's **gem tier**: 1 Opal, 2 Sapphire, 3 Emerald, 4 Ruby, 5 Diamond, 6 Onyx,
7 Zenyte. Tiers are derived from **this catalogue's own data** by the new
`scripts/derive-tiers.mjs` (model in `src/lib/tcg/tier.ts`): items rank on market worth
(max of GE guide price and high-alch) **blended with equipment power** (sum of positive combat
bonuses — so untradeable prestige gear like the Fire cape ranks on what it does, not its missing
price tag); NPCs on combat presence (combat level + vitality spread) **plus Slayer gating**
(required level, 0–99) **and quest role**; members-only content nudges up; and every card gains
a bonus for the size of its `unlocksItems` family (cluster masters rank up — e.g. Elvarg with
its 36-card Dragon Slayer I unlock set lands Zenyte). Signal-less cards pin to Opal, and the
seven bands fall wherever the catalogue's log-signal distribution naturally clusters
(deterministic k-means — no fixed percentile table). Items with identical worth always share a
tier. Distribution: Opal 3,662 · Sapphire 812 · Emerald 1,503 · Ruby 1,783 · Diamond 1,073 ·
Onyx 891 · Zenyte 442 (~4.3%).
**Reason:** retire the inherited rarity vocabulary (names, thresholds, scoring — unusable for
legal reasons) in favour of this project's own gem ladder computed from its own catalogue. The `rarity` property is removed everywhere in the same change (it was
an unused, always-null column). Rerun the script after any catalogue change, then reseed.

### Reconciled card-db to the `api/cards` catalogue (ingest disconnect fixed)
`src/lib/tcg/card-db.ts` now builds from the `api/cards` JSON (this catalogue)
instead of the old bundled `data/cards.json`, so a card's in-memory `slug` is
its catalogue `cardId`. The ingest (`collection-sync`) therefore resolves card
names to the exact `cardId`s stored in the DB, and `PlayerCard` rows satisfy
their FK — this fixes the "ingest disconnect" noted below. Card values/levels
now map from the catalogue (`market.guidePrice`/`highAlchemy`, NPC
`combatRating`); NPCs get a synthetic "Monster" category so the rarity model
still scores them by level. `ingest.test.ts` seeds the two cards it uploads so it
no longer depends on a full-catalogue seed. The committed test suite is green
(168 pass / 0 fail). (Data-only doc note; the change itself is in `api/src`.)

### Added integer `id` to every card — now the DB primary key
Gave every card a sequential integer **`id`** (1…N over the sorted catalogue), added it to the
schema (`other/card-schema/card-schema.json`, required) and to all 10,166 card JSONs. In the
database this integer is now the **`Card` primary key** (`id Int @id`, replacing the cuid),
seeded explicitly; the string **`cardId`** slug (`itm_…`/`npc_…`) is kept as the unique
business key. Migration `20260730130000_card_integer_primary_key` retypes `Card.id` and the 8
FK columns to `INTEGER` (exchange/trade/forum/profiles), and re-points **`PlayerCard.cardId`**
to reference `Card.cardId` (the slug) since the ingest stores the slug, not the id. The
exchange service/routes now carry the internal id as `number`. `tsc` clean; all suites pass
except the pre-existing ingest set (below).

> **Known issue (pre-existing, not caused by this change):** the ingest path resolves card
> names against a **separate in-memory catalogue** (`src/lib/tcg/card-db.ts`, built from
> `data/cards.json` — an older bundled card list) whose slug is `slugify(name)` (e.g.
> `abyssal-whip`), which does **not** match this catalogue's `cardId` (`itm_abyssal-whip`).
> So `PlayerCard.cardId` has no matching `Card` row and its FK fails — `ingest.test.ts` shows
> 33 failures, red under both the old cuid `id` and the new integer `id`. Reconciling the two
> catalogues (point ingest at `api/cards`) is a separate task, tracked here so these failures
> are not mistaken for a regression of the id change.

### Junk-item pass: cluster reagents/states onto primaries, remove burnt/beta/partial-dose (176)
Acted on the junk-item review:
- **Ground / crushed / bonemeal reagents → primary, removed (36):** each `Ground X`/`Crushed X`
  and `X bonemeal` folded into its base (`Dragon bonemeal`→`Dragon bones`, `Crushed superior
  dragon bones`→`Superior dragon bones`, `Ground charcoal`→`Charcoal`) via `unlocksItems`, then
  the reagent card removed. 11 with no base card left in place (`Crushed gem`, `Ground guam`,
  ninja/skeleton/dagannoth-king bonemeals, …).
- **Broken / damaged / degraded / inactive states → primary, removed (18):** `Damaged book`→
  `Book`, `Torva … (damaged)`→`Torva …`, `Broken dragon hasta`→`Dragon hasta`, etc. 42 with no
  base card left in place (`Broken axe`/`Broken pickaxe` — no plain "Axe"/"Pickaxe" card, the
  `(inactive)` weapons, `Dizana's quiver (broken)`, …).
- **Poisoned-weapon states (p) → base, removed (2).** (4 `(p)`-suffixed non-weapons left alone.)
- **Partial-dose potions (1)/(2)/(3) → highest-dose sibling, removed (41):** only where a `(4)`/
  higher/full sibling exists. **192 lone-dose cards kept** — most potions have only a single
  `(1)` card in the catalogue (it *is* the potion; removing would delete it).
- **Removed 7 beta/test/unused cards** and **72 `Burnt …` food cards** (raw food **kept** per
  request; `Burnt bones`/`Burnt jogre bones`/`Burnt page` kept — not food / group targets).
`big_unlocks.md` regenerated (308 → 374 masters). Item cards 8,157 → 7,981; catalogue 10,342 →
10,166. No unlock ids lost (every clustered id survives on its primary).
**Reason:** requested — fold process/state variants (reagents, damaged/broken, poison, partial
doses) into the real item and drop cut/beta content and burnt food.

### Pruned each quest to ≤3 key exclusive NPCs (removed 578)
Slimmed the quest rosters: for every quest, kept at most **3 key quest-exclusive characters**
(ranked major → supporting, then by prominence) and removed the other single-quest NPCs.
**Protected — never removed:** NPCs shared across ≥2 quests, slayer monsters, boss-category
NPCs, and cluster masters (335 NPCs — the "shared or important to other activities" set). So a
quest keeps its 2-3 exclusive keys **plus** its bosses/shared/slayer cast. Examples: Dragon
Slayer I keeps Cabin Boy Jenkins / Guildmaster / Klarense (Elvarg, the Generals, Duke Horacio
stay); Monkey Madness I keeps Karam / Lumo / Bunkdo (Jungle Demon, Kruk, Awowogei stay).
Quest-NPC audit regenerated; `big_unlocks.md` unchanged (no masters removed — unlock ids that
pointed at removed spawns are retained, same as clustering). NPC cards 2,763 → 2,185; catalogue
10,920 → 10,342.
**Reason:** requested — reduce each quest to a few iconic characters, dropping the long tail of
quest-specific filler NPCs while preserving anyone reused elsewhere. Heuristic cut (the "which
3" is by significance + prominence); reversible from git if specific picks need adjusting.

### Removed 9 raid/quest puzzle-mechanic "NPCs"
Deleted the engine-NPCs that are interaction/puzzle mechanics rather than characters (no
combat, no race, puzzle-verb actions): `Abyssal axon` (hit/reset), `Blood serpent` (wrangle),
`Cerebral pathbreaker` & `Cerebral pathfinder` (move/reset-room), `Big Monolith` (push/reset),
`Catalyst` (observe), `Animated egg` (capture), `Crust of ice` (melt), `Furnace grate` (clear).
Living creatures (`Frog`, `Butterfly`, `Light leech`) remain. Quest-NPC audit regenerated.
NPC cards 2,772 → 2,763; catalogue 10,929 → 10,920.
**Reason:** requested follow-up — these are Tombs-of-Amascut / quest puzzle objects, not
collectible characters.

### Removed 41 item-pickup "NPCs" (holiday/event & quest ground items)
Deleted engine-NPC cards that are items lying on the ground (a pickup rendered as an NPC), not
characters — no combat, no race, and only pickup actions (`Take`/`Pick-up`/`Pick`/`Roll`): the
partyhats (all colours), h'ween masks, `Santa hat`, `Reindeer hat`, `Wintumber tree`, `Bunny
ears`, `Easter egg`/`Easter ring`, `Christmas cracker`, `Pumpkin`, `Jack lantern mask`,
`Chicken suit`, `Skeleton outfit`, `Rubber chicken`, `Tiny snowball`, `Yo-yo`, `Zombie head`,
`Scythe`, `Hats & scarves`, `Marionette set`, plus quest ground-items (`Sea slug`, `Thistle`,
`Half full wine jug`, `Innocent-looking key`, `Disk of Returning`, `War ship`, `Broav`).
**Kept** the raid/quest puzzle-mechanic objects (`Abyssal axon`, `Blood serpent`, `Cerebral
pathbreaker`/`pathfinder`, `Big Monolith`, `Catalyst`, `Crust of ice`, `Furnace grate`,
`Animated egg`) and living creatures (`Frog`, `Butterfly`, `Light leech`). Quest-NPC audit
regenerated. NPC cards 2,813 → 2,772; catalogue 10,970 → 10,929.
**Reason:** requested follow-up — holiday/event and quest item pickups are items rendered as
NPCs, not real collectible characters.

### Removed 34 scenery / object "NPCs" (Fishing spot & similar)
Deleted engine-NPC cards that are interactive scenery/objects/effects, not characters or
monsters — identified by having **no combat, no race**, and only object-style menu actions
(`Fish`/`Bait`/`Net`/`Harpoon`/`Push`/`None`) or being named inanimate objects: **8 Fishing
spot** variants, **Portal** ×2, `Boat`, `Boulder` (the push-obstacle; the Troll NPC "Boulder"
kept), the four Barbarian-Assault `Barrier`s, `Obelisk`, `Plough`, `Chair`, `Bench`, `Doll`,
`Gary's hat`, `Bones`, `Starflower`, `Flower`, `Fire Wave`, `Tsunami`, `Shadow Rift`,
`Looming shadow`, `Awakened Altar`, and the `Shadow`/`Blood`/`Ice`/`Healing` totems. Living
creatures with no combat data (`Frog`, `Butterfly`, `Light leech`) and any card with a race,
combat level, `Talk-to`/`Attack` action, or `unlocksItems` were **kept**. Quest-NPC audit
regenerated. NPC cards 2,847 → 2,813; catalogue 11,004 → 10,970.
**Reason:** requested — fishing spots and similar are game-engine NPCs but not real
collectible characters/monsters. (A separate class of holiday-pickup NPCs with a `take` action —
partyhats, Santa hat, Easter egg, etc. — was left for a follow-up decision.)

### Removed remaining minor quest NPCs + targeted cleanups; markdown made minor-free
Finished the "minor" quest-NPC removal and applied a few targeted edits from a review pass:
- **Removed the 9 remaining minor-only NPC cards** (the namesakes previously spared): `Enakhra`,
  `Dr Fenkenstrain`, `Fairy Queen`, `Eadgar`, `Safalaan Hallow`, and the four coloured `Sheep`.
- **Removed by request:** `Sawmill operator` (3101 — also dropped from Elvarg's DSI unlock set,
  37 → 36, and from `special_item_unlocks.md`), `Sir Vyvin` (4736), `Squire` (4737, The Knight's
  Sword), `Witch` (4778, Black Knights' Fortress).
- **Merged the Prince Ali duplicates** into one card (`4283`, "Prince Ali (disguised)"): the two
  ids from the other card (11579, 11580) added to its `source.gameIds`, the copy deleted.
- **Merlin made an NPC special** (`npc_merlin`, 3529/4059) unlocking the Camelot knights —
  `Renegade knight` (3517), `Sir Gawain` (4348/4356), `Sir Lancelot` (4344/4354); those three
  cards removed (folded into Merlin).
- **Quest-NPC audit regenerated minor-free** — it now lists only boss/major/supporting roles;
  incidental minor associations by NPCs that matter elsewhere are no longer shown (121 minor
  pairs dropped).
NPC cards 2,864 → 2,847; catalogue 11,021 → 11,004. `big_unlocks.md` regenerated (307 → 308
masters, Merlin added).
**Reason:** requested — purge the low-significance (minor) quest NPCs from both the catalogue
and the grouped markdown, plus one-off dedup/removals from a manual review.

### Bryophyta & Obor made boss specials (unlock moss / hill giants)
Set `unlocksItems` on **Bryophyta** (`npc_bryophyta`, 8195 — the Moss Giant boss) to the **7
moss-giant ids** (`Moss giant` + `Moss Giant (Iorwerth Dungeon)`) and on **Obor** (`npc_obor`,
7416 — the Hill Giant boss) to the **7 hill-giant ids** (`Hill Giant` + Realm of Memories +
Kovac). The giant cards are **kept** — the bosses only *unlock* them, matching the Elvarg
pattern. Added both to `special_item_unlocks.md`; `big_unlocks.md` regenerated (305 → 307
masters). Catalogue unchanged (11,021).
**Reason:** requested — make each giant boss the "special" that unlocks its monster family.

### Elvarg made a Dragon Slayer I "quest special" (unlocks the DSI NPC roster)
Set `unlocksItems` on **Elvarg** (`npc_elvarg`, 817 — the base Dragon Slayer I dragon, not the
NMZ variant) to **37 game ids across the 18 other DSI NPCs**: Guildmaster, Oracle, Wormbrain,
Melzar the Mad, Duke Horacio, Klarense, Ned (×2), Cabin Boy Jenkins, Sawmill operator, Oziach,
Generals Bentnoze & Wartface, and the Melzar's Maze monsters (Ghost, Skeleton, Zombie, Zombie
rat, Lesser demon). Membership taken from the wiki `Dragon Slayer I` category; both Elvarg
cards' own ids excluded (a master doesn't unlock itself). **No NPC cards were removed** — Elvarg
only *unlocks* them (most appear in other quests). Added to `special_item_unlocks.md` as the
first NPC special; `big_unlocks.md` regenerated (304 → 305 masters). Catalogue unchanged
(11,021).
**Reason:** requested — make the quest's boss the single "special" card that unlocks everyone
required for Dragon Slayer I, the "unlocks downwards" pattern applied to a quest NPC set.

### Folded the generic city guards into the base Guard too
Extended the Guard cluster to the 10 generic human city/town/settlement/gate guards:
**City guard, Jail guard, Tower guard, Market Guard (Draynor + general), Fortress Guard,
Border Guard (Lumbridge/Al Kharid + Varlamore), Villa Guard, Shantay Guard** — their 25 game
ids added to the base **Guard** master (now unlocks **194**) and the 10 cards removed. Still
kept separate as distinct NPCs: race/faction/animal/boss guards (Gnome/Goblin/Ogre/Monkey/
Golem/Cave-goblin guards, Khazard/Tyras/Armadylean/Bandosian/Honour/Black/H.A.M. guards, Guard
Bandit, Bedabin Nomad/Pirate/Enclave/Green/Rowdy guards, Guard dog, Head Menaphite Guard,
Head Guard, KGP Guard). `big_unlocks.md` and the quest-NPC audit regenerated. NPC cards 2,874 →
2,864; catalogue 11,031 → 11,021.
**Reason:** these are functionally the same generic municipal guard as the base card; fold
them like the plain "Guard" spawns.

### Clustered all 35 "Guard" NPCs onto the base (Varrock) Guard
Collapsed every NPC card named exactly **Guard** (35 cards, 173 game ids — the generic
city/quest/palace guards from Hosidius, Ratcatchers, Port Sarim jail, Burthorpe, Prifddinas,
Shayzien, Varlamore, Aldarin, the Deadman/beta/2014-Goblin-Invasion variants, etc.) into the
canonical **Guard** (`3254__Guard.json`, ids 3254/11922–11924 — the F2P Varrock/Falador/
Ardougne guard). Its `unlocksItems` now lists the **169** other guard ids and the **34** other
cards were removed. Qualified guards that are distinct NPCs were **left alone** (Guard dog,
Monkey Guard, Tyras guard, Khazard/Ogre/Honour/Golem guards, GWD Armadylean/Bandosian guards,
City/Jail/Tower/Market/Fortress guards, Head Menaphite Guard, etc.). `big_unlocks.md`
regenerated (303 → 304 masters); quest-NPC audit regenerated (the quest-specific guard spawns
folded away). NPC cards 2,908 → 2,874; catalogue 11,065 → 11,031.
**Reason:** the generic Guard appears as dozens of near-identical per-location spawns; one
card unlocking the whole family ("unlocks downwards") suffices, like the other multi-spawn
masters.

### Removed 52 low-significance quest NPCs (minor, non-combat)
Deleted quest NPCs whose only role across every quest they appear in is **minor** — i.e.
present solely via the wiki's quest *category*, with the quest never named in their infobox
`Quest` field — **and** who have **no combat** (pure background/cameo NPCs: `Hans`,
`Nurse Sarah`, `Father Aereck`, `Rind the gardener`, `Drunken Dwarf`, `Doris`, etc.). Scoped
deliberately narrow after review — **all combat NPCs were kept**, which protects the quest
bosses/monsters the significance heuristic under-classified as "supporting" (`Lowerniel
Drakan`, `Balance Elemental`, `Arrav`, `Hespori`, …). Additional guards spared **30 real
slayer monsters** (assignable — `Black demon`, `Greater demon`, `Demonic gorilla`, …), **3
cluster masters** (`Zombie`/`Skeleton`/`Basilisk`), and **9 quest namesakes** whose infobox
happened to omit the quest (`Enakhra`, `Dr Fenkenstrain`, `Fairy Queen`, `Eadgar`, the four
coloured `Sheep`, `Safalaan Hallow`). No `unlocksItems` masters were removed → `big_unlocks.md`
unchanged. NPC cards 2,960 → 2,908; catalogue 11,117 → 11,065.
**Reason:** background/cameo quest NPCs with no combat presence are not meaningful
collectibles; the quest-NPC significance grading isolates them as the
"minor" tier. Combat NPCs were retained because that tier mislabels real quest bosses.

### Removed 1,491 quest / process props (three high-confidence tiers)
Deleted the three highest-confidence tiers of the quest/process-prop audit — inert items that are
**untradeable, unequippable, unlock nothing, and have only `Destroy`/`Read`/`Inspect`/`Open`/
`Check`/`Search`/`None` actions** (no `Wear`/`Wield`/`Eat`/`Drink`/`Rub`/`Teleport`): **905
destroy-only props** (fractional jugs/buckets, quest/holiday/minigame tokens), **479 readable
lore** items (notes, letters, books, journals), and **107 `None`-only placeholders**. The
interaction guardrail excluded real look-alikes (e.g. `Dragon bones`, construction planks,
`Zenyte`/`Crystal shard` — tradeable/buryable), which were **not** removed. The 124 "medium"
tiers (droppable inert junk, incl. things players value like Bird nest / Clue bottles / Bellator
vestige) were **left as review candidates**. No `unlocksItems` masters were affected (all removed
cards had empty unlocks), so `big_unlocks.md` is unchanged. Catalogue 12,608 → 11,117.
**Reason:** quest/process props are scenery/step items, not collectibles, and were never meant
to be part of the card set. Signal is interaction-based because `questLinked` is unpopulated
(5 items).

---

Four-part sweep tightening the catalogue toward one card per real collectible: the wiki scrape
pulls in far more real-but-obscure items than the card set needs, so cosmetic/charge variants,
GE set bundles, POH furniture, and scraper noise are trimmed or folded. Catalogue **13,648 → 12,608** (1,040 cards removed). `big_unlocks.md` regenerated
(64 → 303 grouped masters; 713 → 1,110 game ids unlocked — the +397 are the clustered variant
ids). No unlock ids were lost (verified: every clustered id survives in a surviving card).

### Clustered variant / ornamented / charged suffixes onto their base (406 removed)
Folded trimmed/gilded/ornamented/imbued/heraldic/charged/tiered variants into their base item,
applying the catalogue's "one card per real item" rule:
- **302 variants with a real base card** — `(t) (g) (or) (i) (cr) (nz) (h1–h5) (f)` and tier
  words (`basic/attuned/perfected`) etc. — had their game id(s) added to the base's
  `unlocksItems` ("unlocks downwards"), then the variant card was deleted. Examples:
  `Iron full helm (t)` → **Iron full helm**, `Enchanted lyre(i)` → **Enchanted lyre**.
- **104 orphan tier/heraldic variants** (no plain base in the catalogue) were **collapsed onto
  their lowest member** (t1 / h1 / tier 1 / basic), which now unlocks the rest: e.g.
  `Rune helm (h1)` unlocks h2–h5; `Raging echoes robeskirt (t1)` unlocks t2/t3;
  `Twisted (t1)` family, `Corrupted (basic)` family, `Bounty hunter hat (tier 1)`, etc.
- **47 lone charge-state / non-ladder variants left alone** (`(uncharged)/(inactive)/(inert)/
  (broken)` singletons with no base and no sibling, and the Black wizard robe/hat `(g)/(t)`
  pair) — nothing to fold them into, so removing them would orphan the only card for that item.
**Reason:** these variants are all the same collectible; the scrape kept each as a distinct
card. Clustering preserves the game ids under one collectible.

### Removed 135 "…set" GE bundles
Deleted every item whose name is a `…set` armour/equipment/potion/page bundle, including the
trimmed/variant-suffixed forms (`…set (lg)/(sk)/(f)`): all dragonhide sets, Barrows/god/moon/
relic-hunter armour sets, trimmed & gold-trimmed metal sets, god-book page sets, potion sets,
`Dwarf cannon set`, `Partyhat set`, `Halloween mask set`, etc. Left the **Marionette set** NPC.
**Reason:** these are Grand Exchange convenience bundles (placeholders that unpack into their
component items), not distinct collectibles — a "set" is not a real item.

### Removed 46 scraper-noise cards
Deleted the **42-card `Dni23 …` family** (`Dni23 torso frilly`, `Dni23 legs shortskirt`, …) —
character-customisation model dummies that leaked from the wiki into the item scrape — plus the
four stray fragments **M sigil**, **Grimy sito foil**, **Resper-holos poison**, **Kuhu essence**.
**Reason:** these are not real in-game items; they are scrape artifacts.

### Removed 453 POH / construction furniture and quest props
Deleted the **446 items carrying a `Check materials` interaction** — the POH construction /
flatpack objects (houses, altars, jewellery boxes, displays, arenas, hidey holes, mounted
decorations, `Crafting table 1–4`, the `(interface item)/(flatpack)` build dupes, etc.) — plus
**7 named secondary/quest props** (`Bookcase`, `Broken glass` ×2, `Order form`,
`Decapitated head` ×2, `Coffin nails`). **Kept `Cup of tea`** (a real item mis-flagged by the
scrape). Real collectibles that share a name with a POH decoration were untouched — they carry
their own interactions (real *Asgarnian ale* has `Drink`, *Amulet of glory* has `Wear/Rub`,
the *Rune dragon* is an NPC, real shields are `Bronze kiteshield` etc.).
**Reason:** POH furniture, construction/scenery, and quest props are buildable/process objects,
not collectible cards; they entered the catalogue only through the full wiki scrape.

## 2026-07-29

### Dedup cosmetic/reward same-name item families
Kept one card per name for obvious cosmetic/reward duplicate families — 37 names, **299 cards
removed**: heraldic helms & metal kiteshields, Chompy bird hat, Banner, Decorative armour/
helm/shield/sword/boots, Graceful recolours, Crystal helm/body/legs/crown, Bow of faerdhinen
(c) / Blade of saeldor (c), marionettes, Villager/Pirate/Elven/Tribal clothing. Distinct
same-name items (Key, quest items, tools, Cup of tea, Collection log, etc.) were **left alone**.
Catalogue 13,947 → 13,648.
**Reason:** these are pure cosmetic/reward variants of one item — one card per name suffices.

### Bulk cleanup: caskets, NPC boss variants, lamp clustering
Three trims from the removal-candidates review:
- **Removed 21 clue caskets / reward tokens** (Reward casket easy→master, Casket/Heavy/
  Ancient/Rusty casket, Reward token, Placeholder …reward) — loot containers/placeholders.
- **Removed 56 NPC variants** whose name ends `(…)` or file carries `(Deadman)`/`(PvM Arena)`/
  `(Echo)` — Deadman-mode boss dupes, Colosseum Echoes, instanced/quest-location NPCs.
- **Clustered all XP/quest lamps into the standard `Antique lamp`** (4447): it now unlocks 48
  other lamp ids and the 50 other lamp cards were removed. Kept the 3 light-source lamps
  (Oil lamp, Empty oil lamp, Spooky wall lamp).
Catalogue 14,074 → 13,947.
**Reason:** loot containers and duplicate boss instances aren't distinct collectibles; XP
lamps collapse under one "exp lamp" card.
**No-ops found (data only has base states):** dose potions exist only as `(1)` (no `(2)(3)(4)`
to cluster); no Barrows degrade-state cards exist; the 7 `(p)` items have no non-`(p)` base.

### Removed "_toy" items
Deleted the 5 items whose name ends in "toy" (`*_toy.json`): Mouse toy, Tiger toy, Lion toy,
Snow leopard toy, Amur leopard toy (big-cat follower toys). Items named "toy X" (Toy cat/
soldier/ship/doll/mouse, toy horseys, toy boxes) are kept — different pattern. Catalogue
14,079 → 14,074.
**Reason:** the big-cat pet toys are minor follower-summon items, not distinct collectibles.

### Restored '24-carat' sword as a special item (unlocks metal swords)
Re-added **'24-carat' sword** (`itm_24-carat-sword`, 24539) — previously clustered into the
Giant easter egg — as its own special item that unlocks all metal swords from rune down to
bronze: `[1277, 1279, 1281, 1283, 1285, 1287, 1289]` (bronze/iron/steel/black/mithril/adamant/
rune). Removed 24539 from the Giant easter egg cluster (217 → 216). Added to
`special_item_unlocks.md`. Catalogue 14,078 → 14,079.
**Reason:** the '24-carat' sword is a joke sword that "upgrades" the metal-sword line — better
as its own collectible unlocking the sword tiers than folded into the generic event cluster.

### Removed bulk "pack" items
Deleted every item whose name ends in "pack" (45 cards) — bulk store packs (rune/feather/
bait/seed/ore/starter/potion packs, etc.). *Backpacks* (Explorer, Ruined) are kept — they
are equipment, not bulk packs (no word boundary before "pack"). Catalogue 14,123 → 14,078.
**Reason:** bulk quantity packs are vendor convenience bundles, not distinct collectibles.

### Clustered all Servery items into Servery meat pie
Consolidated the full Servery set (ids 13397-13418) into one card: **Servery meat pie**
(`itm_servery-meat-pie`, 13403) now unlocks all 20 other Servery item ids, and the 20
individual cards were removed (both dirs). Catalogue 14,143 → 14,123.
**Reason:** the Servery items are intermediate Tithe-Farm/servery cooking steps — collapse
the whole chain into a single collectible.

### Removed pyromancer-covered warm-clothing cards
Deleted the 71 warm-clothing items that a pyromancer piece already
unlocks — the Head / Body / Legs / Feet items now represented by Pyromancer hood / garb / robe
/ boots. **Kept:** the 4 pyromancer master cards, and the 26 orphan-slot warm items in slots
with no pyromancer master (Neck 7, Cape 3, Hands 12, Weapon 2, Shield 1, Ring 1) so their game
ids aren't orphaned. Catalogue 14,214 → 14,143.
**Reason:** collapse the warm-clothing items that already have a master (the pyromancer set)
into those cards, without dropping items that nothing would unlock.

### Clustered event rewards into the Giant easter egg card
Consolidated all *Event rewards* items (wiki `Category:Event rewards`, 269 item cards) into a
single card: **Giant easter egg** (`itm_giant-easter-egg`, 23446) now has `unlocksItems` of
all 217 non-warm event game ids, and the **217 individual clustered cards were removed** (both
dirs). **Warm-clothing event items are kept** (51 — Santa/Bunny/Antisanta/Festive/etc.), since
they cluster under the pyromancer / warm-clothing grouping instead. Removed by event: Christmas
37, Easter 23, Halloween 62, Birthday & anniversary 35, Pride 4, Other 57. Catalogue 14,431 → 14,214.
**Reason:** collapse the large, cosmetic-only holiday/event reward set into one collectible,
without double-counting items already grouped as warm clothing.

### Pyromancer pieces unlock their slot's warm clothing
Each pyromancer set piece now unlocks all other warm-clothing items in its equipment slot
(per the amended warm-clothing survey): **Pyromancer hood** → 25 Head items, **Pyromancer
garb** → 24 Body, **Pyromancer robe** → 13 Legs, **Pyromancer boots** → 14 Feet. Each list
excludes the pyromancer piece itself.
**Reason:** make each pyromancer piece the "master" warm-clothing collectible for its slot.

### Grouped all max cape variants under the standard Max cape
Set `unlocksItems` on **Max cape** (`itm_max-cape`, 13280) to all 16 max-cape variant game
IDs — Fire / Infernal / Accumulator / Ardougne / Assembler / Masori assembler / Mythical /
Dizana's, the god capes (Saradomin/Zamorak/Guthix) and their Imbued forms (incl. the
duplicate broken/locked Fire & Infernal ids): `[13329, 13331, 13333, 13335, 13337, 20760,
21186, 21284, 21285, 21776, 21780, 21784, 21898, 24855, 27363, 28902]`.
**Reason:** the standard Max cape is the base; every other max cape is a cosmetic/combined
variant, so one card unlocks the whole family ("unlocks downwards").

### Removed switch-index artifact game IDs (data cleanup)
Confirmed against the wiki HTML (e.g. Giant Rock Crab's real IDs are 2261/5940, not the
leaked 1/2) that low game IDs appearing in **≥3 distinct NPC cards** are scraper
index-leak artifacts — here IDs **1–10**. A real game ID maps to exactly one NPC, so an ID
shared across dozens of cards cannot be real. Removed them from **158** NPC `source.gameIds`
(656 entries) and **70** `unlocksItems` arrays (282 entries), across both card dirs.
**8 cards whose *only* IDs were artifacts were deleted** (removing the bad IDs would leave
zero game IDs; the live wiki confirmed no clean unique IDs exist — its own `|id` param lists
the same colliding low numbers, e.g. Aberrant spectre `|id = 2,3,4,5,6,7`). Removed:
**Aberrant spectre**, **Molanisk**, **Death spawn**, and 5 "2014 April Fools" joke NPCs
(Zaros, Party Pete, Troll, Saba, Lizard man). Catalogue 14,439 → 14,431.
**Reason:** the index-leak IDs were polluting `source.gameIds` and the slayer unlock lists;
keeping only real IDs makes the catalogue and the unlock relationships trustworthy.

### Wired all 53 slayer variant families' unlocks (per reviewed doc)
Applied `unlocksItems` to the base card of every family in the user-reviewed, amended slayer
variant survey — 53 families. Source of truth = each family's curated `unlocksItems` line
(some hand-edited beyond the auto-detected variants, e.g.
Zombie's 12720–14117 range, the brutal/bloodthirsty dragon ids, Elder chaos druid 6607).
Base card targeted by **name + `slayer.group`** so unrelated same-named NPCs (35 town
"Guards", the "Dagannoth" Construction dummy / *Horror from the Deep* quest boss) are not
touched; quest-boss variants stay excluded (HTML-validated). 88 base cards carry unlocks
(multi-spawn bases such as Skeleton/Zombie write the family list to each of their spawn
cards sharing that name+group).
**Reason:** collapse each slayer monster's variants under its original so one card grants
the whole family. NOTE: several curated lists include low ids 1/2/3 that originate from
multi-spawn `source.gameIds` artifacts — flagged for a later cleanup pass.

### Slayer variant unlocks: Jelly unlocks all jelly variants (+ family survey)
Set `unlocksItems` on the base **Jelly** (`npc_jelly`, 437) to every jelly variant's game
IDs `[7277, 7399, 7400, 12570, 12571, 13799, 15501]` (Warped/Vitreous/Chilled/Vitreous
Warped/Vitreous Chilled/Bloodthirsty variants) — the original unlocks its variants
"downwards", same mechanism as item masters. Added a survey
of all slayer-monster variant families (grouped by `npcProfile.slayer.group`, base =
member whose name the others contain), 57 families with the proposed `unlocksItems` per
base, for review before wiring beyond Jelly. **Validated against the raw wiki HTML page
categories:** variants tagged `Quest monsters` are quest bosses/encounters and are excluded
from the unlock lists (5 caught — Ice Troll King, Evil Chicken, Skeleton Hellhound, Dire
Wolf Alpha, Basilisk Youngling); non-quest `Bosses` (legit slayer bosses) are kept and
marked; a boss/quest base is flagged (1 — Amoxliatl). Jelly itself is clean.
**Reason:** collapse slayer-monster variants under their original creature so one card
grants the whole family, instead of a separate collectible per elemental/enhanced form —
without absorbing one-off quest bosses that merely share a slayer category.

### Added `combine` structure (card-combination recipes) + rake example
Added an optional, sparse `combine` object to the schema, referenced by `cardId`:
`craftedFrom` (components that assemble into this card → master/result) and `combinesInto`
(masters this card is a component of → back-reference). A card may hold both (an
intermediate part). The master lists its direct components' game IDs in `unlocksItems`
("unlocks downwards"). Wired one real example: **Rake head + Rake handle → Rake**
(`itm_rake` craftedFrom `itm_rake-head` + `itm_rake-handle`; both components combinesInto
`itm_rake`; Rake unlocksItems = [5347, 5348]) — 3 cards.
**Reason:** model multi-part items that combine into one card, with the master unlocking
its parts. Structure chosen: card↔card edges keyed by cardId (stable, 1:1 per card) so the
recipe graph is independent of shared game IDs; recipes are curated, not wiki-derived.

### Removed 10 "(unused)" cards
Deleted every card whose name contains `(unused)` — all 10 were NPCs (Emissary Forebearer,
Gerrant, Wydin, Treznor, Dryad, Knight of Ardougne, Basilisk, Cockatrice, Turoth, Twig);
no items carried the marker.
**Reason:** wiki `(unused)` entries are cut/placeholder content not present in the live
game, so they don't belong in the collectible catalogue.

### Removed 2,741 non-combat NPC cards (kept quest characters)
Deleted NPC cards that have **no combat data** and **no quest link**. An NPC is kept if it
has any of `combatRating` / `vitality` / `peakDamage` / populated `combatLevels` /
`aggressiveBonuses` / `attackTempo` / `assaultStyles` (combat), **or** a real `questLine`
(the wiki sentinel `"No"` does not count). Kept 3,034 of 5,775 NPCs (1,733 combat +
1,301 quest-only); NPC count dropped from 5,775 → 3,034.
**Reason:** filler NPCs (shopkeepers, ambient/townsfolk with no stats and no quest role)
aren't meaningful collectibles; keep monsters and story/quest characters.

### Removed 163 Last Man Standing cards
Deleted every card matching `*_(Last_Man_Standing).json` — 161 items + 2 NPCs.
**Reason:** the LMS minigame variants duplicate their normal counterparts and add no
distinct collectible value; they only inflate the catalogue.

### Slayer helmet unlocks all helmet variants
Set `unlocksItems` on `itm_slayer-helmet` (game id 11864) to all 30 slayer-helmet
variant game ids — base + every colour (Black, Green, Red, Purple, Turquoise, Hydra,
Twisted, Tztok, Vampyric, Tzkal, Araxyte, Hooded, Oathplate, Radiant), each in regular
and imbued `(i)` form.
**Reason:** the base helmet card should grant the whole helmet family rather than each
variant being its own collectible card.

### Removed 15 imbued "slayer helmet (i)" cards
Deleted every card matching `*slayer_helmet_(i).json` (base + all colours/boss variants).
**Reason:** imbued helmets are stat/cosmetic duplicates of the base helmets; consolidated
under the base Slayer helmet card's `unlocksItems` instead of separate cards.

### Added `unlocksItems` field to all cards
Added `unlocksItems` (array of integer game ids, default `[]`) to the schema and to every
card, placed after `tier`.
**Reason:** model which in-game item(s) a card unlocks, keyed by OSRS game id.

### Added `tier` field to all cards
Added `tier` (integer, minimum 1, default 1) to the schema and to every card.
**Reason:** introduce a tier system for special items (tier 1 = lowest/baseline; higher
tiers reserved for rarer specials).

### Removed 675 "Clue scroll (…)" step cards
Deleted every card matching `*__Clue_scroll_(*` (all beginner/easy/medium/hard/elite/
master steps plus special/SotE/map variants). Kept the 6 `Scroll box (…)` cards.
**Reason:** hundreds of near-identical per-step clue scroll cards are redundant; the
per-tier scroll boxes already represent clue content in the catalogue.
