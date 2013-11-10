(ns echocave.core
  (:require [cljs.core.async :as async
             :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
            [clojure.browser.repl :as repl]
            [crate.core :as crate]
            [jayq.core :refer [$ append inner on] :as jq]
            [echocave.net :as net :refer [GET jsonp-chan]]
            [echocave.utils :as utils :refer [log board-width board-height ship-height ship-width]]
            [echocave.background :as bg :refer [ground-chan update-ground]]
            )
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]]))

; (repl/connect "http://localhost:9000/repl")

(def raf
  (or (.-requestAnimationFrame js/window)
      (.-webkitRequestAnimationFrame js/window)
      (.-mozRequestAnimationFrame js/window)
      (.-oRequestAnimationFrame js/window)
      (.-msRequestAnimationFrame js/window)
      (fn [callback] (js/setTimeout callback 17))))


(defn click-chan [selector msg-name]
  (let [rc (chan)
        handler (fn [e] (jq/prevent e) (put! rc msg-name))]
    (on ($ "body") :click selector {} handler)
    (on ($ "body") "touchend" selector {} handler)
    rc))

;; from torus-pong

;; current key which is being held down
(def current-key-down (atom nil))

;; returns a key from an event
(defn key-event->command
  [e]
  (let [code (.-keyCode e)]
    (case code
      38 :up
      40 :down
      87 :up
      83 :down
      nil)))

;; given a chan, every 25ms if a key is down, sends :player/up or
;; :player/down on the channel
(defn bind-key-observer
  [command-chan]
  (go (while true
        (<! (timeout 10)) Make the ship move as fast as the user can
        (case @current-key-down
          :up   (>! command-chan [:player/up])
          :down (>! command-chan [:player/down])
          :not-matched)))
  (.addEventListener js/window "keydown"
                     (fn [e]
                       ;(.log js/console e)
                       (reset! current-key-down (key-event->command e))))
  (.addEventListener js/window "keyup"
                     (fn [e]
                       (reset! current-key-down nil))))


(def mainpage
  [:div
   [:div#header
    [:h1 "Play the game!"]
    [:a.new-game {:href "#"} "New Game"]]
   [:canvas#main-board {:width utils/board-width :height utils/board-height}]])

(defn board-context
  []
  (.getContext (first ($ :#main-board)) "2d"))

(defn ^:export clear-board
  []
  (aset (first ($ :#main-board)) "width" (.-width (first ($ :#main-board)))))

(def ship (atom nil))

(defn load-ship
  []
  (let [img (js/Image.)]
    (aset img "src" "resources/imgs/ship.png")
    (aset img "onload" (fn []
                         (log "Loaded ship")
                         (reset! ship img)
                         (.drawImage (board-context) img 0 0 utils/ship-width utils/ship-height)))))

(def board
  [:div.board])
(defn replace-board
  [html-data]
  (inner ($ :#main-board)
         (crate/html html-data)))

(append ($ :#root) (crate/html mainpage))

(load-ship)
;; (let [ids (net/artist-radio-songs "Noah and the Whale")]
;;   (go
;;    (while true
;;      (log "Got out: " (<! (net/fetch-song-analysis ids))))))

;; Initial game state
;; TODO real data, initialize randomly for now
(def ^:export initial-game-state
  {:ground (vec (take utils/board-width
                      (repeatedly #(- utils/board-height (rand-int (* 0.40 utils/board-height))))))
   :shipx 0
   :shipy 0})

;; Direction is :player/up] or p:player/down]
(defn move-ship
  [game-state direction]
  (condp = direction
    [:player/up]
    (let [newy (- (:shipy game-state) 2)]
      (if (< newy 0)
        game-state
        (assoc game-state :shipy newy)))
    [:player/down]
    (let [newx (+ (:shipy game-state) 2)]
      (if (> (+ utils/ship-height newx) utils/board-height)
        game-state
        (assoc game-state :shipy newx)))))

(defn check-collisions
  [game-state game-chan]
  ;; If the ship overlaps with the ground, game over
  (let [ground-colls (filter (fn [x] ;; Check y pos at each x pos of the ship
                               (>= (+ (:shipy game-state) utils/ship-height)
                                   (nth (:ground game-state) x)))
                             (range (:shipx game-state)
                                    (+ (:shipx game-state) utils/ship-width)))]
    (when-not (empty? ground-colls)
      (log "GAME OVER" ground-colls)
      (put! game-chan :gameover))))

(defn update-game-state
  [game-state comm-chan game-chan]
  (go
   (let [s (atom game-state)]
     ;; Move ship if key is down
     (let [[v c] (alts! [comm-chan (timeout 0)])]
       (when (= c comm-chan) ; Got a key down
         (swap! s move-ship v)))
     ;; Shift the ground to the left
     (swap! s assoc :ground (<! (bg/update-ground (:ground game-state))))
     ;; Check for collisions
     (check-collisions game-state game-chan)
     @s)))

(defn render-board
  [game-state]
  ;; Clear board
  (clear-board)
  ;; Draw path for ground, through all the :ground datapoints
  (let [ctx (board-context)
        ground (:ground game-state)]
    (.beginPath ctx)
    (.moveTo ctx 0 (- utils/board-height (first ground)))
    (doall (map-indexed (fn [idx height]
                          (.lineTo ctx idx height))
                        (rest ground)))
    (.stroke ctx)
    ;; Draw the ship in its place
    (.drawImage ctx @ship (:shipx game-state) (:shipy game-state) 30 30)))

;; Main game loop
;;
;; * Use rAF to get called every 17ms
;; *
(defn mainloop
  [game-state comm-chan game-chan]
  (go
   ;; Update game state
   (let [game-state (<! (update-game-state game-state comm-chan game-chan))
         [v c] (alts! [game-chan (timeout 0)])]
     ;; Render updated board
     (render-board game-state)
    (when-not (and (= c game-chan)
                   (= v :gameover))
      (raf #(mainloop game-state comm-chan game-chan))))))

(let [comm-chan (chan)]
  (mainloop initial-game-state comm-chan (chan (sliding-buffer 10)))
  (bind-key-observer comm-chan))


