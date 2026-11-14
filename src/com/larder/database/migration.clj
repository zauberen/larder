;; Larder - a household recipe, pantry and grocery list manager.
;; Copyright (C) 2025-2026 zauberen
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published
;; by the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

(ns com.larder.database.migration
  "Idempotent schema migrations. Every entry in `migrations` must be safe to run again
  (create ... if not exists, alter table add column, which fails harmlessly with a
  duplicate column error, or a function of the db-spec that checks before it writes).
  They all run whenever `server-version` changes. Times are stored as epoch milliseconds."
  (:require [clojure.java.jdbc :as jdbc]))

(def server-version "0.9.0")
;; Reset whenever this namespace is reloaded; set once check-run-migrations has run.
(def migration-chk-ran (atom nil))

(def seed-ingredient-rows
  "[name dimension density aliases] for the common ingredients created once at setup.
  Densities are typical values in grams per millilitre (flour spooned, brown sugar packed)."
  [["flour" "mass" 0.53 ["all-purpose flour" "plain flour" "white flour"]]
   ["bread flour" "mass" 0.55 []]
   ["whole wheat flour" "mass" 0.51 ["wholemeal flour"]]
   ["sugar" "mass" 0.85 ["granulated sugar" "white sugar" "caster sugar"]]
   ["brown sugar" "mass" 0.93 ["light brown sugar" "dark brown sugar"]]
   ["powdered sugar" "mass" 0.56 ["icing sugar" "confectioners sugar"]]
   ["butter" "mass" 0.911 ["unsalted butter" "salted butter"]]
   ["salt" "mass" 1.2 ["table salt" "fine salt"]]
   ["kosher salt" "mass" 0.54 []]
   ["baking powder" "mass" 0.9 []]
   ["baking soda" "mass" 0.92 ["bicarbonate of soda"]]
   ["cocoa powder" "mass" 0.42 ["unsweetened cocoa powder" "cocoa"]]
   ["cornstarch" "mass" 0.54 ["corn starch" "cornflour"]]
   ["cornmeal" "mass" 0.65 []]
   ["rice" "mass" 0.85 ["white rice" "long grain rice"]]
   ["rolled oats" "mass" 0.41 ["oats" "old-fashioned oats"]]
   ["quinoa" "mass" 0.72 []]
   ["lentils" "mass" 0.85 ["dried lentils"]]
   ["breadcrumbs" "mass" 0.45 ["bread crumbs" "panko"]]
   ["chocolate chips" "mass" 0.72 []]
   ["honey" "mass" 1.42 []]
   ["peanut butter" "mass" 1.09 []]
   ["parmesan" "mass" 0.42 ["grated parmesan" "parmesan cheese"]]
   ["cheddar" "mass" 0.45 ["shredded cheddar" "cheddar cheese"]]
   ["cream cheese" "mass" 1.0 []]
   ["yogurt" "mass" 1.03 ["plain yogurt" "greek yogurt" "yoghurt"]]
   ["tomato paste" "mass" 1.1 []]
   ["milk" "volume" 1.03 ["whole milk"]]
   ["heavy cream" "volume" 0.99 ["whipping cream" "double cream"]]
   ["sour cream" "volume" 1.0 []]
   ["water" "volume" 1.0 []]
   ["olive oil" "volume" 0.91 ["extra virgin olive oil" "extra-virgin olive oil"]]
   ["vegetable oil" "volume" 0.92 ["canola oil" "neutral oil"]]
   ["maple syrup" "volume" 1.32 []]
   ["vinegar" "volume" 1.01 ["white vinegar"]]
   ["soy sauce" "volume" 1.15 []]
   ["lemon juice" "volume" 1.03 []]
   ["chicken broth" "volume" 1.0 ["chicken stock"]]
   ["vanilla extract" "volume" 0.88 ["vanilla"]]
   ["ketchup" "volume" 1.14 []]
   ["mayonnaise" "volume" 0.91 ["mayo"]]
   ["egg" "count" nil ["large egg"]]
   ["garlic" "count" nil ["garlic clove"]]
   ["onion" "count" nil ["yellow onion"]]
   ["lemon" "count" nil []]])

(defn seed-ingredients!
  "Create `seed-ingredient-rows` once. A flag in global_settings remembers that it ran,
  so ingredients deleted afterwards don't come back with the next migration."
  [db-spec]
  (when (empty? (jdbc/query db-spec ["select 1 from global_settings where key = 'ingredients_seeded'"]))
    (jdbc/with-db-transaction [tx db-spec]
      (let [t (System/currentTimeMillis)]
        (doseq [[name dimension density aliases] seed-ingredient-rows]
          (jdbc/execute! tx ["insert or ignore into ingredients (name, dimension, density, created_at, modified_at) values (?, ?, ?, ?, ?)"
                             name dimension density t t])
          (let [id (:id (first (jdbc/query tx ["select id from ingredients where name = ?" name])))]
            (doseq [a aliases]
              (jdbc/execute! tx ["insert or ignore into ingredient_aliases (ingredient_id, alias) values (?, ?)" id a]))))
        (jdbc/execute! tx ["insert or ignore into global_settings (key, value) values ('ingredients_seeded', '1')"])))))

(def migrations
  [(str "create table if not exists db_version ("
        "id integer primary key autoincrement,"
        "version_number text)")
   (str "insert into db_version (version_number) values ('" server-version "')")
   ;; Roles: admin (everything, plus users and site settings), user (everything else).
   (str "create table if not exists users ("
        "id integer primary key autoincrement,"
        "username text unique not null,"
        "password text not null,"
        "role text not null default 'user',"
        "enabled integer not null default 1,"
        "created_at integer not null,"
        "modified_at integer not null)")
   (str "create table if not exists sessions ("
        "sid text primary key,"
        "session_obj text not null,"
        "created_at integer not null)")
   (str "create table if not exists global_settings ("
        "key text primary key,"
        "value text)")
   ;; 0.2.0: recipes. Steps are plain text, one step per line. image_path is a file name
   ;; in <data-root>/media. rating is 1-5 or null and belongs to the recipe, not a user.
   (str "create table if not exists recipes ("
        "id integer primary key autoincrement,"
        "title text not null,"
        "description text,"
        "servings integer,"
        "prep_minutes integer,"
        "cook_minutes integer,"
        "steps text,"
        "source_url text,"
        "status text not null default 'draft',"
        "image_path text,"
        "rating integer,"
        "created_by integer references users (id) on delete set null,"
        "created_at integer not null,"
        "modified_at integer not null)")
   ;; ingredient_id, quantity, unit and note are filled in by the line parser and matcher
   ;; (phase 3); original_text is always kept.
   (str "create table if not exists recipe_ingredients ("
        "id integer primary key autoincrement,"
        "recipe_id integer not null references recipes (id) on delete cascade,"
        "position integer not null,"
        "ingredient_id integer,"
        "quantity real,"
        "unit text,"
        "note text,"
        "original_text text not null)")
   "create index if not exists recipe_ingredients_recipe on recipe_ingredients (recipe_id, position)"
   (str "create table if not exists recipe_tags ("
        "recipe_id integer not null references recipes (id) on delete cascade,"
        "tag text not null,"
        "primary key (recipe_id, tag))")
   ;; 0.3.0: ingredients. dimension is mass, volume or count; density is grams per
   ;; millilitre and lets mass and volume convert. Names and aliases are unique ignoring
   ;; case; the matcher also ignores plurals (see ingredients/match-key).
   (str "create table if not exists ingredients ("
        "id integer primary key autoincrement,"
        "name text not null unique collate nocase,"
        "dimension text not null default 'count',"
        "density real,"
        "default_quantity real,"
        "default_unit text,"
        "always_have integer not null default 0,"
        "created_at integer not null,"
        "modified_at integer not null)")
   (str "create table if not exists ingredient_aliases ("
        "id integer primary key autoincrement,"
        "ingredient_id integer not null references ingredients (id) on delete cascade,"
        "alias text not null unique collate nocase)")
   "create index if not exists recipe_ingredients_ingredient on recipe_ingredients (ingredient_id)"
   #'seed-ingredients!
   ;; 0.5.0: pantry. One row per purchase or manual entry, so the date added is kept;
   ;; stock for an ingredient is the sum of its rows. quantity is null when unknown.
   (str "create table if not exists pantry_items ("
        "id integer primary key autoincrement,"
        "ingredient_id integer not null references ingredients (id) on delete cascade,"
        "quantity real,"
        "unit text,"
        "added_at integer not null,"
        "source text not null default 'manual',"
        "added_by integer references users (id) on delete set null)")
   "create index if not exists pantry_items_ingredient on pantry_items (ingredient_id, added_at)"
   ;; 0.6.0: grocery list and stores. section is grocery (tracked into the pantry) or
   ;; misc. quantity/unit are the default amount to buy; purchased_* and pantry_item_id
   ;; are set when a grocery item is completed, so undo can remove that pantry row.
   (str "create table if not exists stores ("
        "id integer primary key autoincrement,"
        "name text not null unique collate nocase,"
        "notes text)")
   (str "create table if not exists grocery_items ("
        "id integer primary key autoincrement,"
        "section text not null,"
        "ingredient_id integer references ingredients (id) on delete cascade,"
        "text text,"
        "quantity real,"
        "unit text,"
        "store_id integer references stores (id) on delete set null,"
        "origin text not null default 'manual',"
        "added_by integer references users (id) on delete set null,"
        "added_at integer not null,"
        "done_at integer,"
        "purchased_quantity real,"
        "purchased_unit text,"
        "pantry_item_id integer)")
   "create index if not exists grocery_items_open on grocery_items (done_at, section)"
   ;; 0.8.0: cook log. recipe_id is kept as null when the recipe is deleted, so the
   ;; history stays; meal_plan_entry_id is set when cooked from the meal plan.
   (str "create table if not exists cook_log ("
        "id integer primary key autoincrement,"
        "recipe_id integer references recipes (id) on delete set null,"
        "meal_plan_entry_id integer,"
        "servings real,"
        "user_id integer references users (id) on delete set null,"
        "cooked_at integer not null)")
   ;; 0.9.0: meal plan. date is a local calendar date (YYYY-MM-DD); entries of a day are
   ;; ordered by position. cooked_at is set by Made it.
   (str "create table if not exists meal_plan_entries ("
        "id integer primary key autoincrement,"
        "date text not null,"
        "recipe_id integer not null references recipes (id) on delete cascade,"
        "servings real,"
        "position integer not null default 0,"
        "cooked_at integer)")
   "create index if not exists meal_plan_entries_date on meal_plan_entries (date, position)"])

(defn run-migrations
  "Initialize or update a database. No user is created: the first admin comes from onboarding."
  [db-spec]
  (doseq [migration migrations]
    (try
      (if (string? migration)
        (jdbc/execute! db-spec [migration])
        (migration db-spec))
      (catch Exception e
        ;; Ignore duplicate column errors from alter table statements
        (when-not (re-find #"(?i)duplicate column" (.getMessage e))
          (throw e))))))

(defn get-server-version-query
  "Returns a query to check the server version."
  []
  "select version_number from db_version where id = (select max(id) from db_version)")

(defn check-run-migrations
  "Run the migrations for DB-SPEC when the database is at another version."
  [db-spec]
  (if-some [_ @migration-chk-ran]
    true ; Already checked since this namespace was loaded.
    (let [ver-rs (try (jdbc/query db-spec (get-server-version-query))
                      ;; A database file without the db_version table (e.g. an empty file).
                      (catch java.sql.SQLException _ []))]
      (reset! migration-chk-ran true)
      (when (or (<= (count ver-rs) 0)
                (not= server-version (:version_number (nth ver-rs 0))))
        (run-migrations db-spec)))))
