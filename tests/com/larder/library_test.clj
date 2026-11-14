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

(ns com.larder.library-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder.database.db :as db]
            [com.larder.ingredients :as ingredients]
            [com.larder.library :as library]
            [com.larder.library.format :as fmt]
            [com.larder.library.git :as git]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.settings :as s]
            [com.larder.test-util :as tu]))

(use-fixtures :each tu/temp-env-fixture)

(def ^:private ident ["-c" "user.name=Test" "-c" "user.email=test@example.com"])

(defn- dir [] (io/file tu/*root* "library"))

(defn- recipe-file [name] (io/file (dir) "recipes" name))

(defn- subjects
  "Commit subjects of the repo in D, newest first."
  [d]
  (str/split-lines (git/out (io/file d) "log" "--format=%s")))

(defn- message
  [d]
  (git/out (io/file d) "log" "-1" "--format=%B"))

(defn- form
  [m]
  (first (recipes/parse-form (merge {:title "Pancakes" :ingredients "2 cups flour\n1 egg" :steps "Mix.\nCook."} m))))

(defn- reinit!
  []
  (library/stop!)
  (library/init!))

;;; Formats

(deftest recipe-format-test
  (let [id (recipes/create! (form {:description "Fluffy.\n\nSunday ones." :servings "4" :prep_minutes "10"
                                   :cook_minutes "15" :rating "5" :tags "breakfast, sweet"
                                   :source_url "https://example.com/p"})
                            "published" 1)
        r (recipes/by-id id)
        text (fmt/render-recipe r)
        [parsed errors] (fmt/parse-recipe text)]
    (is (str/starts-with? text "title: Pancakes\nid: "))
    (is (str/includes? text "\n[Ingredients]\n2 cups flour\n1 egg\n"))
    (is (empty? errors))
    (is (= (select-keys r [:id :title :description :servings :prep_minutes :cook_minutes :rating :status :steps :source_url :created_at :modified_at])
           (select-keys parsed [:id :title :description :servings :prep_minutes :cook_minutes :rating :status :steps :source_url :created_at :modified_at])))
    (is (= ["breakfast" "sweet"] (:tags parsed)))
    (is (= ["2 cups flour" "1 egg"] (:lines parsed)))
    (testing "errors"
      (is (some #(str/includes? % "Unknown field") (second (fmt/parse-recipe "title: X\nservngs: 4\n"))))
      (is (seq (second (fmt/parse-recipe "id: 3\n\n[Steps]\nMix.\n"))) "a title is required")
      (is (seq (second (fmt/parse-recipe "title: X\nstatus: maybe\n"))))
      (is (= 3 (fmt/header-id "title: X\nid: 3\nstatus: maybe\n"))))))

(deftest ingredients-format-test
  (let [text (fmt/render-ingredients [{:id 2 :name "sugar" :dimension "mass" :density 0.85 :always_have 0 :aliases ["white sugar"]}
                                      {:id 1 :name "Flour" :dimension "mass" :density 0.53 :default_quantity 1.0
                                       :default_unit "kg" :always_have 1 :aliases []}])
        [parsed errors] (fmt/parse-ingredients text)]
    (is (str/includes? text "\nFlour\n  id: 1\n  dimension: mass\n  density: 0.53\n  buy: 1 kg\n  always-have: yes\n\nsugar\n"))
    (is (empty? errors))
    (is (= [{:id 1 :name "Flour" :dimension "mass" :density 0.53 :default_quantity 1.0 :default_unit "kg" :always_have 1 :aliases []}
            {:id 2 :name "sugar" :dimension "mass" :density 0.85 :default_quantity nil :default_unit nil :always_have 0 :aliases ["white sugar"]}]
           parsed))
    (testing "a hand-written block without an id or dimension"
      (is (= [[{:id nil :name "leeks" :dimension "count" :density nil :default_quantity 2.0 :default_unit "each"
                :always_have 0 :aliases []}] []]
             (fmt/parse-ingredients "leeks\n  buy: 2 each\n"))))
    (testing "errors"
      (is (seq (second (fmt/parse-ingredients "a\n  id: 1\nb\n  id: 1\n"))))
      (is (seq (second (fmt/parse-ingredients "a\n  alias: b\nb\n"))))
      (is (seq (second (fmt/parse-ingredients "a\n  buy: lots\n"))))
      (is (seq (second (fmt/parse-ingredients "  id: 1\n")))))))

(deftest commit-message-test
  (is (= "add(recipe): Pancakes" (fmt/commit-message [{:kind :recipe :action :add :id 1 :name "Pancakes"}])))
  (is (= "add(recipe): Waffles"
         (fmt/commit-message [{:kind :recipe :action :add :id 1 :name "Pancakes"}
                              {:kind :recipe :action :edit :id 1 :name "Waffles"}]))
      "an add then an edit is an add with the latest name")
  (is (nil? (fmt/commit-message [{:kind :ingredient :action :add :id 1 :name "a"}
                                 {:kind :ingredient :action :del :id 1 :name "a"}])))
  (is (= "del(recipe): Pancakes"
         (fmt/commit-message [{:kind :recipe :action :edit :id 1 :name "Pancakes"}
                              {:kind :recipe :action :del :id 1 :name "Pancakes"}])))
  (is (= (str "add(ingredient): leek, kale; edit(recipe): Soup\n\n"
              "add(ingredient): leek\nadd(ingredient): kale\nedit(recipe): Soup")
         (fmt/commit-message [{:kind :ingredient :action :add :id 1 :name "leek"}
                              {:kind :recipe :action :edit :id 9 :name "Soup"}
                              {:kind :ingredient :action :add :id 2 :name "kale"}
                              {:kind :ingredient :action :edit :id 1 :name "leek"}])))
  (is (str/starts-with? (fmt/commit-message (for [i (range 30)] {:kind :ingredient :action :add :id i :name (str "ingredient number " i)}))
                        "add(ingredient): 30 ingredients\n\n")))

;;; Writing through and committing

(deftest export-and-commit-test
  (let [id (recipes/create! (form {}) "published" 1)]
    (library/init!)
    (testing "the first start exports the database and commits it"
      (is (.exists (io/file (dir) "ingredients.txt")))
      (is (str/includes? (slurp (io/file (dir) "ingredients.txt")) "\nflour\n"))
      (is (.exists (recipe-file "pancakes.txt")))
      (is (= ["init: export recipes and ingredients"] (subjects (dir)))))
    (testing "edits are written at once and committed by flush"
      (recipes/update! id (form {:title "Pancakes with syrup"}) "published")
      (is (not (.exists (recipe-file "pancakes.txt"))) "renamed with the title")
      (is (str/includes? (slurp (recipe-file "pancakes-with-syrup.txt")) "title: Pancakes with syrup"))
      (is (= 1 (:pending (library/status))))
      (is (library/flush!))
      (is (= "edit(recipe): Pancakes with syrup" (first (subjects (dir))))))
    (testing "new recipes"
      (recipes/create! (form {:title "Waffles"}) "draft" 1)
      (recipes/create! (form {:title "Waffles"}) "draft" 1)
      (is (.exists (recipe-file "waffles.txt")))
      (is (.exists (recipe-file "waffles-2.txt")) "a second recipe with the title gets a number")
      (library/flush!)
      (is (= "add(recipe): Waffles, Waffles" (first (subjects (dir))))))
    (testing "deleting"
      (recipes/delete! id)
      (is (not (.exists (recipe-file "pancakes-with-syrup.txt"))))
      (library/flush!)
      (is (= "del(recipe): Pancakes with syrup" (first (subjects (dir))))))
    (testing "a batch of ingredient changes is one commit"
      (let [before (count (subjects (dir)))
            leek (ingredients/create! {:name "leek" :dimension "count"})]
        (ingredients/create! {:name "kale" :dimension "mass"})
        (ingredients/add-alias! leek "leeks, trimmed")
        (let [flour (ingredients/match "flour")] ; the same aliases again: no change
          (ingredients/set-aliases! flour (ingredients/aliases flour)))
        (library/flush!)
        (is (= (inc before) (count (subjects (dir)))))
        (is (= (str "add(ingredient): leek, kale\n\nadd(ingredient): leek\nadd(ingredient): kale")
               (message (dir))))))
    (testing "merging deletes one and edits the other"
      (ingredients/merge! (ingredients/match "kale") (ingredients/match "leek"))
      (library/flush!)
      (is (str/starts-with? (message (dir)) "del(ingredient): kale; edit(ingredient): leek")))
    (testing "nothing pending, nothing committed"
      (let [before (subjects (dir))]
        (library/flush!)
        (is (= before (subjects (dir))))))))

(deftest debounce-test
  (with-redefs [s/get-settings (let [st (s/get-settings)] (constantly (assoc st :library-commit-delay-seconds 1)))]
    (library/init!)
    (ingredients/create! {:name "leek" :dimension "count"})
    (Thread/sleep 500)
    (ingredients/create! {:name "kale" :dimension "count"})
    (is (= 1 (count (subjects (dir)))) "nothing committed while changes keep coming")
    (Thread/sleep 2000)
    (is (= "add(ingredient): leek, kale" (first (subjects (dir)))))))

;;; Loading the files

(deftest sync-from-files-test
  (let [id (recipes/create! (form {}) "published" 1)
        flour (ingredients/match "flour")]
    (pantry/add! {:ingredient_id flour :quantity 500 :unit "g" :source "manual" :added_by 1})
    (library/init!)
    (testing "a hand edit to a recipe file is loaded"
      (spit (recipe-file "pancakes.txt")
            (str/replace (slurp (recipe-file "pancakes.txt")) "1 egg" "2 eggs\n1 cup milk"))
      (reinit!)
      (is (= ["2 cups flour" "2 eggs" "1 cup milk"] (map :original_text (:ingredients (recipes/by-id id)))))
      (is (every? :ingredient_id (:ingredients (recipes/by-id id))) "lines are matched again"))
    (testing "a new file without an id becomes a recipe and gets its id"
      (spit (recipe-file "toast.txt") "title: Toast\nstatus: published\n\n[Ingredients]\n2 slice bread\n")
      (reinit!)
      (let [toast (first (recipes/search {:q "toast"}))]
        (is toast)
        (is (str/includes? (slurp (recipe-file "toast.txt")) (str "id: " (:id toast))))))
    (testing "a file with errors keeps the recipe as it was"
      (spit (recipe-file "pancakes.txt") (str/replace (slurp (recipe-file "pancakes.txt")) "status: published" "status: perhaps"))
      (reinit!)
      (is (= "Pancakes" (:title (recipes/by-id id))))
      (is (str/includes? (get-in (library/status) [:error :text]) "pancakes.txt")))
    (testing "removing a file deletes the recipe"
      (io/delete-file (recipe-file "pancakes.txt"))
      (reinit!)
      (is (nil? (recipes/by-id id))))
    (testing "ingredients.txt edits: renames, new ingredients, deletions"
      (let [f (io/file (dir) "ingredients.txt")
            text (-> (slurp f)
                     (str/replace #"(?m)^sugar$" "cane sugar")
                     (str/replace #"\nlemon\n  id: \d+\n  dimension: count\n" "\n")
                     (str/replace "# Larder ingredients." "leek\n  buy: 3 each\n\n# Larder ingredients."))]
        (spit f text)
        (reinit!)
        (is (= "cane sugar" (:name (ingredients/by-id (ingredients/match "granulated sugar")))))
        (is (nil? (ingredients/match "lemon")))
        (is (= 3.0 (:default_quantity (ingredients/by-id (ingredients/match "leek")))))
        (is (re-find #"\nleek\n  id: \d+\n" (slurp f)) "the new ingredient's id is written back")
        (is (= 1 (count (pantry/rows-for flour))) "the pantry is untouched")))
    (testing "a broken ingredients.txt is not loaded"
      (spit (io/file (dir) "ingredients.txt") "  id: 1\n")
      (reinit!)
      (is (ingredients/match "flour"))
      (is (str/includes? (get-in (library/status) [:error :text]) "ingredients.txt")))))

;;; Remotes

(defn- with-remote
  "Run F with a bare remote and the library pointed at it."
  [f]
  (let [bare (io/file tu/*root* "remote.git")
        other (io/file tu/*root* "other")]
    (git/run (io/file tu/*root*) "init" "-q" "--bare" "-b" "main" (.getPath bare))
    (with-redefs [s/get-settings (let [st (s/get-settings)] (constantly (assoc st :library-remote (.getPath bare))))]
      (f bare other))))

(defn- clone!
  [bare other]
  (git/run (io/file tu/*root*) "clone" "-q" (.getPath bare) (.getPath other)))

(defn- commit-other!
  [other msg]
  (apply git/run other (concat ident ["commit" "-q" "-am" msg]))
  (git/run other "push" "-q" "origin" "HEAD:main"))

(deftest push-and-pull-test
  (with-remote
    (fn [bare other]
      (library/init!)
      (is (library/flush!))
      (is (= ["init: export recipes and ingredients"] (subjects bare)) "the export is pushed")
      (clone! bare other)
      (testing "a change on the remote is pulled before pushing, and loaded"
        (let [f (io/file other "ingredients.txt")]
          (spit f (str/replace (slurp f) "\negg\n" "\negg\n  alias: hen egg\n"))
          (commit-other! other "edit(ingredient): egg"))
        (ingredients/create! {:name "leek" :dimension "count"})
        (is (library/flush!))
        (is (= ["add(ingredient): leek" "edit(ingredient): egg" "init: export recipes and ingredients"] (subjects bare)))
        (is (= (ingredients/match "egg") (ingredients/match "hen egg")) "the pulled alias is in the database"))
      (testing "a conflicting change is not pushed and is reported"
        (git/run other "pull" "-q")
        (let [f (io/file other "ingredients.txt")]
          (spit f (str/replace (slurp f) "\nleek\n" "\nleeks\n"))
          (commit-other! other "edit(ingredient): leeks"))
        (ingredients/update! (ingredients/match "leek") {:name "baby leek" :dimension "count"})
        (is (not (library/flush!)))
        (is (str/includes? (get-in (library/status) [:error :text]) "rebase"))
        (is (= "edit(ingredient): baby leek" (first (subjects (dir)))) "the local commit is kept")
        (is (not (.exists (io/file (dir) ".git" "rebase-merge"))) "the rebase was undone")
        (is (= "edit(ingredient): leeks" (first (subjects bare))))))))

(deftest fresh-install-from-remote-test
  (with-remote
    (fn [bare other]
      ;; Another Larder's library on the remote.
      (clone! bare other)
      (spit (io/file other "ingredients.txt") "leek\n  id: 7\n  dimension: count\n")
      (.mkdirs (io/file other "recipes"))
      (spit (io/file other "recipes" "soup.txt") "title: Soup\nid: 12\nstatus: published\n\n[Ingredients]\n2 leeks\n")
      (git/run other "add" "-A")
      (commit-other! other "init")
      (library/init!)
      (is (= 7 (ingredients/match "leek")))
      (is (nil? (ingredients/match "flour")) "the library's ingredients replace the seeded ones")
      (is (= 7 (:ingredient_id (first (:ingredients (recipes/by-id 12)))))))))
