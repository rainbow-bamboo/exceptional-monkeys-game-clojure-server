(ns exceptional-monkeys-game-server.core
  (:require
    [clj-uuid :as uuid]
    [cheshire.core :as json]
    [immutant.web             :as web]
    [immutant.web.async       :as async]
    [immutant.web.middleware  :as web-middleware]
    [compojure.route          :as route]
    [environ.core             :refer (env)]
    [compojure.core           :refer (ANY GET defroutes)]
    [ring.util.response       :refer (response redirect content-type)])
  (:gen-class))

(def exceptionTypes ["IOException", "DivideByZeroException", "NullPointerException", "ArithmeticException", "FileNotFoundException", "IndexOutOfBoundsException",
                     "InterruptedException", "ClassNotFoundException", "NoSuchFieldException", "NoSuchMethodException", "RuntimeException"])

(def collectables (atom {}))
(def players (atom {}))

(defn broadcast-msg [connections msg] (doseq [con connections] (async/send! (key con) (json/generate-string msg {:pretty true}))))

(defn show-rand-ex []
  (future (loop []
            (let [max 800
                  min 60
                  new-val {:exception? true :show true :exceptionType (rand-nth exceptionTypes) :x (+ min (rand-int (- max min))) :y (+ min (rand-int (- max min)))}]
              (swap! collectables assoc (str (uuid/v1)) new-val)
              (broadcast-msg @players new-val))
            (Thread/sleep 5000)
            (recur))))

(defn overlap? [playerX playerY exX exY]
  (cond
    (or (> playerX (+ 130 exX)) (> exX (+ 100 playerX))) false
    (or (< (+ playerY 129) exY) (< (+ exY 200) playerY)) false
    :else true))

(defn collect [player]
  (let [pred (fn [[k v]] (and (:show v) (= (:exceptionType v) (:exceptionType player)) (overlap? (:x player) (:y player) (:x v) (:y v))))
        collected (first (filter pred @collectables))]
    (if (some? collected)
      (do
        (swap! collectables dissoc (key collected))
        (broadcast-msg @players (assoc (val collected) :show false))
        (assoc player :score (+ 1 (:score player))))
      player)))

(defn move [con move-x move-y]
  (let [player (@players con)
        x (+ move-x (:x player))
        y (+ move-y (:y player))]
    (if (or (< y 0) (< x 0) (>= x (:windowW player)) (>= y (:windowH player)))
      (broadcast-msg @players (assoc player :collision true))
      (do
        (->> (assoc player :x x :y y)
             (collect)
             (swap! players assoc con))
        (broadcast-msg @players (assoc player :x x :y y))))))

(defn handle-incoming-msg [con msg]
  (let [parsed-msg (json/parse-string msg keyword)
        {:keys [height width x y] } parsed-msg]
    (cond
      (and height width) (swap! players assoc con (assoc (@players con) :windowH height :windowW width))
      (and x y) (move con (Integer/parseInt x) (Integer/parseInt y)))))

(defn remove-player [con]
  (broadcast-msg @players (assoc (@players con) :show false))
  (swap! players dissoc con))

(defn add-new-player [con]
  (let [new-player {:player? true
                    :id (str (uuid/v1))
                    :x (rand-int 600)
                    :y (rand-int 300)
                    :score 0
                    :show true
                    :exceptionType (rand-nth exceptionTypes)
                    :color [(rand-int 256) (rand-int 256) (rand-int 256)]
                    :collision false
                    :windowH 0
                    :windowW 0}]
    (broadcast-msg (assoc {} con true) (assoc new-player :self? true))       ;send player to self
    (doseq [p (vals @players)] (broadcast-msg (assoc {} con true) p))               ;send to self existing players
    (broadcast-msg @players new-player)               ;send new player to all existing players
    (swap! players assoc con new-player)))                   ;insert new player to players

(def websocket-callbacks
  {:on-open    add-new-player
   :on-close   (fn [channel {:keys [code reason]}]
                 (remove-player channel)
                 (println "close code:" code "reason:" reason))
   :on-message handle-incoming-msg})

(defroutes routes
           (GET "/" {c :context} (redirect (str c "/index.html")))
           (route/resources "/"))

(defn -main [& {:as args}]
  (show-rand-ex)
  (web/run
    (-> routes
        (web-middleware/wrap-websocket websocket-callbacks))
    (merge {"host" (env :demo-web-host), "port" 8080} args)))