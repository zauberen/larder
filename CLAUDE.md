# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Larder is a self-hosted household recipe app in Clojure. One server holds one shared recipe list, pantry, grocery list and meal plan; signed-in users manage them, visitors read published recipes (and, per a site setting, check off grocery items), and Claude reaches all four over MCP. It copies the stack, shell and styles of [Block Shipper](https://github.com/zauberen/block-shipper) (files are copied, not shared).

It is built in phases (see the spec): **1 Shell** ✅ · **2 Recipes** ✅ · **3 Ingredients and units** ✅ · **4 Web import** ✅ · 5 Pantry · 6 Grocery · 7 Pantry status · 8 Made it · 9 Meal plan · 10 MCP with API keys · 11 MCP with OAuth · 12 Tandoor import (waits for a real export). Update this file as each phase lands.

Decisions made so far: the app is named Larder (`com.larder`, `~/.larder`); Phase 3 seeds a common set of ingredients with densities via migration.

Every source file (Clojure, our JS/CSS; not `resources/public/js/vendor/`) starts with the AGPLv3 license header (`;; Larder - a household recipe, pantry and grocery list manager.`); add it to new files.

## Commands

```bash
# Run the application
clj -M:dev -m com.main

# Run all tests (kaocha, configured in tests.edn)
clj -M:test

# Run a single test namespace
clj -M:test --focus com.larder.web-test

# Check for outdated dependencies
clj -M:chk
```

To try the app without touching your real `~/.larder` and `~/larder`, point the JVM's home elsewhere: `clj -J-Duser.home=/some/tmp/dir -M:dev -m com.main` (paths starting with `~` expand from `user.home`).

## Architecture

**Entry point:** `src/com/main.clj` configures slf4j to log to `<data-root>/logs/YYYYMMDD-larder.log`, then loads and calls `com.larder/start-server`. Only `com.larder.settings` is required up front: slf4j reads its configuration when the first logger is created, and loading Jetty or `clojure.tools.logging` creates one. `start-server` pins the restart-only settings, decides whether setup is required, runs `on-startup` (create the data folder, migrate the DB) and starts Jetty.

**Nothing may touch the database at namespace load time.** Before setup there is no database (or the chosen one is elsewhere), and tests redefine the settings only inside their fixtures. E.g. the not-found route renders per request (`(route/not-found (fn [_] ...))`) because the 404 page reads the site name.

**Onboarding** (`pages/onboarding.clj`): when there is no `~/.larder`, no database file or no users, `start-server` only starts Jetty and sets `setup-required`; every request then goes to `setup-routes` (`/setup`, `POST /api/setup`, everything else redirects to `/setup`; static files still load). Setup asks for the admin account, site name, port, data folder and database path (plus advanced: upload piece size, debug), pins the chosen settings so that database is used at once, creates the data folder, writes the settings file, creates the admin, stores the site name in `global_settings`, runs the deferred startup and logs the admin in. Pages rendered before setup use `gs/default-site-name`, never the database.

**Routing** (`src/com/larder.clj`): Compojure routes with Ring middleware (keyword-params, params, static resources from `resources/public/`, cookies) plus `middleware.clj`: non-GET requests whose `Origin` doesn't match `Host`/`X-Forwarded-Host` get a 403, except `origin-exempt-paths` (`/mcp`, `/oauth/token`, `/oauth/register`, which authenticate by bearer token or client credentials); bodies over 1 MB (by Content-Length) get a 413 (upload pieces may be up to the piece size); security headers are added. Access checks live in `com.larder.auth`: `requires-user` and `requires-admin`. Handlers receive `(req user)`. Admin-only settings tabs return 404 to users; admin API endpoints return 403.

Routes so far: `/` public dashboard · `/recipes/:id` published recipe · `/media/:file` recipe images · `/login` `/logout` `/setup` · `/app` recipe list (`?q=`, `?status=`) · `/app/recipes/new` Add (choice of import or blank; `?blank=1[&source_url=]` is the editor) · `/app/recipes/:id` detail · `/app/recipes/:id/edit` · `/app/meal-plan` · `/app/pantry` · `/app/grocery` · `/app/settings/:tab` (`account`; `users` and `site` for admins) · `/api/...` htmx endpoints.

**Session management** (`api/session.clj`): manual sessions, not Ring's. On login a UUID sid is stored in the `sessions` table and sent as the `larder-session-id` cookie (HttpOnly, SameSite=Strict, `Secure` over https); sessions expire after `db/session-ttl-seconds` (30 days). The session only names the user; role and enabled flag are re-read from the DB on every request (`auth/current-user` → `users/by-username`, which only returns enabled users), so disabling or demoting someone applies at once. Removing a user deletes their sessions. Unauthenticated htmx requests get an `HX-Redirect` to `/login`. Failed logins are rate limited per address (`ratelimit.clj`: 10 per 5 minutes, then 429; keyed on `:remote-addr`, since `X-Forwarded-For` can be forged — behind a reverse proxy all clients share the proxy's address).

**Roles** (`users.clj`): `admin` and `user`. Both can do everything with recipes, pantry, grocery list and meal plan; only admins manage users (add, change role, reset password, disable/enable, remove) and site settings. You can't change your own role or disable/remove yourself, and the last enabled admin can't be demoted, disabled or removed.

**Settings** (`settings.clj`): `~/.larder` (`config-path`; EDN, read with `clojure.edn`) merged over defaults. Re-read on each `get-settings`, except `restart-keys` (port, data folder, DB path), which are pinned at startup by `pin-restart-settings!`; `pending-restart` lists saved values not yet in effect. Keys: `:listen-port` (8085, so Block Shipper's 8081 stays free), `:data-root` (`~/larder`; logs in `logs/`, later recipe images), `:sqlite-db-path` (`~/larder/larder.db`), `:upload-chunk-mb` (1), `:debug` (enables the admin-only `/api/echo`). `pages/server_config.clj` renders them as a form, shared by onboarding and Settings → Site → Server configuration (`POST /api/site/config`). Site settings editable from the UI live in `global_settings` (`global_settings.clj`): `site_name`, `public_base_url` (used later for share links and OAuth metadata).

**Uploads** (`uploads.clj`): from Block Shipper. A form with `data-upload="/api/uploads"` and `data-upload-kind` (only `image` so far; `pages.recipes/upload-kinds` maps kind → size limit) has its `[data-upload-file]` sent by `main.js` (`bsUploadFile`) in pieces of `:upload-chunk-mb`: `POST /api/uploads` (filename, size, kind) returns `{id, chunkSize}`, `PUT /api/uploads/:id?offset=N` appends a piece (409 with the received count when the offset is off), then the form is submitted with the id in its `[data-upload-id]` field and the handler claims the file with `uploads/take!`. Disabling the submit button during the upload makes htmx forget which button was pressed, so main.js sends its name/value in a hidden `data-submitter` field; read button values with `u/single` in case one arrives twice. Uploads belong to the user who started them and are swept after an idle hour; one survives a form with validation errors.

**Recipes** (`recipes.clj`, `pages/recipes.clj`, `pages/recipe_view.clj`): `parse-form` validates the editor (title required; servings, times and rating optional; tags comma separated and de-duplicated ignoring case; ingredient lines and steps one per line, blank lines dropped) and returns `[recipe errors]`. Every save parses and matches the ingredient lines (see Ingredients). Status changes follow `pages.recipes/next-status`: new or draft → Save draft / Publish; published → Save / Revert to draft; any other button is refused. Saving responds with the detail view and `HX-Push-Url`; delete answers with `HX-Location` back to `/app`. `recipe_view.clj` holds the cards, stars, tags and detail body shared by the app and the public pages. Drafts don't exist for visitors (`recipes/published-by-id`, 404).

**Units** (`units.clj`): canonical units (g kg oz lb · ml l tsp tbsp cup "fl oz" pint quart gallon · count units each can bunch clove package piece slice stick head sprig jar bottle bag box pinch dash), `normalize` maps spellings ("Tablespoons", "fl. oz.", "cans") to them, `convert` converts within mass and within volume (US measures), between them only with a density in g/ml, count units only to themselves; nil when impossible. `format-qty` shows ½, ⅓ etc.

**Ingredients** (`ingredients.clj`): shared records (name, dimension mass|volume|count, density, default purchase quantity/unit, always-have) with aliases. `parse-line` splits a line into quantity (mixed numbers, ½-style fractions, decimals; a range takes its larger amount), unit (a bare number is "each"; units are only looked for after a number), name and note (text after the first comma, parenthesised text, a trailing "to taste"; "1 (14 oz) can" puts the size in the note); `original_text` is always kept. `match` compares `match-key`s (lower case, punctuation removed, every word singularised) against names and aliases — strictly, no fuzzy matching. Recipe saves store quantity, unit, note and ingredient_id per line. Unmatched lines are flagged in the detail view and listed in the editor's picker (`pages.recipes/picker-section`): Use an existing ingredient (the line's name becomes an alias) or Create one (dimension guessed from the line's unit; if named differently, the line's name becomes an alias); either way `rematch-unmatched!` rematches every recipe's unmatched lines. The migration seeds ~45 common ingredients with densities and aliases once (`migration/seed-ingredients!`, guarded by the `ingredients_seeded` global setting so deletions stick).

**Fetching** (`fetch.clj`): every server-side fetch of an address someone else supplies goes through `fetch/fetch`: http/https only, no credentials in the URL, every resolved address checked by `blocked-address?` (private, loopback, link-local, CGNAT, multicast, ULA, mapped/NAT64 IPv4, documentation ranges), redirects followed by hand (max 5) with each hop re-checked, body capped at 5 MB (also for downloaded images), 10 s overall deadline (run in a future). The check relies on the JVM's DNS cache so the connection reuses the checked addresses. Tests bind `fetch/*allow-private-addresses*` to fetch from a local `HttpServer`.

**Import** (`import/core.clj`, `import/web.clj`): `import-source` is a multimethod on the source's `:kind` returning a seq of `{:recipe <parse-form-shaped map> :image {:bytes} :warnings [...]}`; `core/save!` fits the recipe to the editor's limits (`fit`), stores the image through `images/store!` (a failed image is a warning) and creates it published, so lines go through the parser and matcher. `:web` fetches the page, reads schema.org Recipe from JSON-LD (anywhere in the JSON, incl. `@graph`; invalid blocks skipped) then microdata (props of nested items ignored), and maps name, description (HTML stripped), image (string/ImageObject/list, resolved against the page), recipeYield (first integer), prep/cook/total ISO 8601 durations (cook = total − prep when missing), recipeIngredient and recipeInstructions (text, HowToStep, HowToSection). Other sites' ratings and keywords are not imported. Without recipe data the Add page shows the error and offers the blank editor with the address as the source. Tandoor will be another `import-source` method with an upload step.

**Images** (`images.clj`): every image is decoded with ImageIO (JPEG, PNG, GIF, BMP; dimensions checked from the header, max 50 megapixels; file max 10 MB), flattened onto white, scaled to at most 2000 px on the longest side and re-encoded as JPEG into `<data-root>/media/<32 hex>.jpg`. `GET /media/:file` serves it as `image/jpeg` only when a recipe uses it: published recipes for everyone, drafts for signed-in users. Replacing or removing an image, or deleting the recipe, deletes the file. Phase 4's downloaded images go through `images/store!` too.

**UI rendering**: server-side Hiccup + htmx. `pages/layout.clj` is the app shell: header (site name, username, public dashboard, settings, log out) and sidebar (Add, then Recipes, Meal plan, Pantry, Grocery). Pages return `(layout/main section ...)` — SECTION is the sidebar key to highlight — and `layout/app-page` wraps it (htmx navigation requests get only `#main`). In-app links use `ui/nav-attrs` so only `#main` is swapped and the URL is pushed; `main.js` moves the sidebar highlight to `#main`'s `data-section` after each swap. `pages/public.clj` renders the public dashboard with header tabs (Recipes; Grocery list joins in Phase 6 when access is Open) beside Log in. `ui.clj` holds icons and form helpers (`field`, `select`, `alert`, `tabs`, `copy-field`). htmx is vendored in `resources/public/js/vendor/`; `main.js` has copy buttons, themed `hx-confirm`/`data-prompt` dialogs (`bsDialog`) and the sidebar highlight. Styles are Block Shipper's `theme.css`/`main.css`/`login.css` copied as is, with Larder additions at the end of `main.css`. Response helpers in `response.clj`: `h-respond` for Hiccup, `respond` for HTML strings, `respond-generic` for other content types.

**Passwords**: Argon2id via `buddy-hashers`, Base64-encoded before storage.

## DB Schema

All tables are single-tenant (no household or owner column). Times are epoch milliseconds. `migration.clj` holds idempotent statements that all run whenever `server-version` changes; add new ones at the end (`create table if not exists`, `alter table ... add column`, or a var of a function of the db-spec that checks before it writes, like `seed-ingredients!`). The SQLite connection enables `foreign_keys`.

`db_version`, `users` (role `admin`|`user`, `enabled`), `sessions` (`sid`, `session_obj`, `created_at`), `global_settings` (`key`, `value`).

0.2.0: `recipes` (title, description, servings, prep/cook minutes, steps as text with one step per line, source_url, status `draft`|`published`, image_path, rating 1-5 or null, created_by, created_at, modified_at), `recipe_ingredients` (recipe_id, position, ingredient_id/quantity/unit/note — null until Phase 3 — and original_text), `recipe_tags` (recipe_id, tag). Children cascade on recipe delete.

0.3.0: `ingredients` (name unique nocase, dimension, density, default_quantity, default_unit, always_have, created_at, modified_at), `ingredient_aliases` (ingredient_id, alias unique nocase), index on `recipe_ingredients.ingredient_id`; seed set.

## Testing

`tests/com/larder/test_util.clj`: `tu/with-temp-env` / `tu/temp-env-fixture` point the settings and database into a temp dir, create `admin`/`admin` and reset rate limits; `tu/request` builds ring requests (`:form`, `:sid`, `:headers`, `:remote-addr`). `web-test/login` creates a session for a username. `import_test.clj` has `call-with-server` (a local `HttpServer` with canned routes); importer fixtures are saved pages in `tests/fixtures/import/`. `recipes_test.clj` has `upload!` (sends bytes through the chunked upload API) and `png-bytes`. For browser checks, headless Chrome can be driven over the DevTools protocol from a small Node script (Node 22 has `WebSocket`); `DOM.setFileInputFiles` fills file inputs. `onboarding_test.clj` runs a fresh install against a real settings file in a temp dir.
