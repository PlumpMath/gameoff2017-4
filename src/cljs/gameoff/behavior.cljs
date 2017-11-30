(ns gameoff.behavior
  (:require [gameoff.signals :as s]
            [gameoff.input :as input]
            [gameoff.physics :as physics]
            [gameoff.render.threejs.core :as render]
            [gameoff.quaternion :as q]
            [clojure.core.matrix :as m]))

(defn ^:export behavioral? [entity]
  (some? (or (get entity :behavior)
             (get entity :states))))

(defn ^:export add-behavior [entity behavior]
  (update entity :behavior (fn [behaviors]
                             (if (contains? entity :behavior)
                               (comp behavior behaviors)
                               behavior))))

(defn fsm-null [entity delta-t world]
  entity)

(defn step-state [entity state delta-t world]
  (let [current-state (get-in entity [:current-states (:name state)])]
    ((get-in state [:states current-state :step] fsm-null) entity delta-t world)))

(defn transition-state [entity state delta-t world]
  (let [current-state (get-in entity [:current-states (:name state)])
        transition-list (get-in state [:states current-state :transitions])
        transition
        (first (filter (fn [{predicate :pred}]
                         (if (and (some? predicate)
                                  (fn? predicate))
                           (predicate entity delta-t world)
                           true)) 
                       transition-list))]
    (if (some? transition)
      (let [new-state (:transition transition)
            exit-handler (get-in state [:states current-state :exit] fsm-null)
            enter-handler (get-in state [:states new-state :enter] fsm-null)]
        (-> entity
            (exit-handler delta-t world)
            (enter-handler delta-t world)
            (assoc-in [:current-states (:name state)] new-state)))
      entity)))

(defn step-states [entity delta-t world]
  (reduce (fn [entity state]
            (-> entity
                (step-state state delta-t world)
                (transition-state state delta-t world)))
          entity
          (:states entity)))

(defn run-behaviors [entity delta-t world]
  (let [behavior (get entity :behavior)]
    (if (fn? behavior)
      (behavior entity delta-t world)
      entity)))

(defn ^:export propagate
  "Propagate AI behavior over time"
  [entity delta-t world]
  (if (behavioral? entity)
    (-> entity
        (step-states delta-t world)
        (run-behaviors delta-t world))
    entity))

(defn ^:export step [world delta-t]
  (reduce-kv (fn [world id entity]
               (update world id propagate delta-t world))
             world world))


(defn command-match
  ([command-to-match]
   (fn [entity delta-t world]
     (when-let [command (s/value (:commands entity))]
       (= command-to-match command))))
  ([command-a command-b]
   (fn [entity delta-t world]
     (when-let [command (s/value (:commands entity))]
       (or (= command-a command)
           (= command-b command)))))
  ([command-a command-b & more]
   (let [commands (conj more command-a command-b)]
     (fn [entity delta-t world]
       (when-let [command (s/value (:commands entity))]
         (some some? (filter #(= command %) commands)))))))

(defn enter-walking [weight time-scale]
  (fn [entity delta-t world]
    ;; no more seperating things, just make this work
    (let [current-scene (get-in world [:scene :current-scene])
          scenes @(get-in world [:backend :scenes])
          mixer (get-in scenes [current-scene :mixer])
          walk-animation (.clipAction mixer (get-in scenes [:animations :Fox_Walk :root]))]
      (.setEffectiveWeight walk-animation weight)
      (.setEffectiveTimeScale walk-animation time-scale)
      (.play walk-animation)
      entity)))

(defn step-walking [speed]
  (fn [entity & more]
    (if (physics/physical? entity)
      (let [rotation (get entity :rotation [0 0 0 1])
            heading (take 3
                          (q/qmul rotation
                                  (conj (get entity :heading [0 1 0]) 0)
                                  (q/inverse rotation)))]
        (assoc-in entity [:body :velocities :walking]
                  (m/mmul speed heading)))
      entity)))

(defn exit-walking [entity delta-t world]
  ;;when both are standing
  (let [current-scene (get-in world [:scene :current-scene])
        scenes @(get-in world [:backend :scenes])
        mixer (get-in scenes [current-scene :mixer])
        walk-animation (.clipAction mixer (get-in scenes [:animations :Fox_Walk :root]))]
    (.setEffectiveWeight walk-animation 0)
    (.stop walk-animation))
  (update-in entity [:body :velocities] dissoc :walking))

(def walking
  {:name :walking
   :states {:standing {:transitions [{:pred (command-match :forward)
                                      :transition :walking-forward}
                                     {:pred (command-match :backward)
                                      :transition :walking-backward}]}
            :walking-forward {:transitions [{:pred (command-match :forward/stop :backward/stop)
                                             :transition :standing}
                                            {:pred (command-match :backward)
                                             :transition :walking-backward}]
                              :enter (enter-walking 1.0 1.2)
                              :step (step-walking 0.008)
                              :exit exit-walking}
            :walking-backward {:transitions [{:pred (command-match :forward/stop :backward/stop)
                                              :transition :standing}
                                             {:pred (command-match :forward)
                                              :transition :walking-forward}]
                               :enter (enter-walking 1.0 -0.8)
                               :step (step-walking -0.005)
                               :exit exit-walking}}})

(defn step-strafing [speed]
  (fn [entity & more]
    (if (physics/physical? entity)
      (let [rotation (get entity :rotation [0 0 0 1])
            heading (get entity :heading [0 1 0])
            up (get entity :up [0 0 1])
            direction (take 3
                            (q/qmul rotation
                                    (conj (m/cross up heading) 0)
                                    (q/inverse rotation)))]
        (assoc-in entity [:body :velocities :strafing]
                  (m/mmul speed direction)))
      entity)))

(defn exit-strafing [entity & more]
  (update-in entity [:body :velocities] dissoc :strafing))


(def strafing
  {:name :strafing
   :states {:standing {:transitions [{:pred (command-match :left)
                                      :transition :strafing-left}
                                     {:pred (command-match :right)
                                      :transition :strafing-right}]}
            :strafing-left {:transitions [{:pred (command-match :left/stop :right/stop)
                                           :transition :standing}
                                          {:pred (command-match :right)
                                           :transition :strafing-right}]
                            :step (step-strafing 0.005)
                            :exit exit-strafing}
            :strafing-right {:transitions [{:pred (command-match :left/stop :right/stop)
                                            :transition :standing}
                                           {:pred (command-match :left)
                                            :transition :strafing-left}]
                             :step (step-strafing -0.005)
                             :exit exit-strafing}}})

(defn step-turning [angular-speed]
  (fn [entity delta-t world]
    (if (physics/physical? entity)
      (let [up (get entity :up [0 0 1])
            rotation (q/axis-angle->q up (* delta-t angular-speed))]
        (update entity :rotation q/qmul rotation))
      entity)))

(def turning
  {:name :turning
   :states {:standing {:transitions [{:pred (command-match :turn-left)
                                      :transition :turning-left}
                                     {:pred (command-match :turn-right)
                                      :transition :turning-right}]}
            :turning-left {:transitions [{:pred (command-match :turn-left/stop :turn-right/stop)
                                          :transition :standing}
                                         {:pred (command-match :turn-right)
                                          :transition :turning-right}]
                           :step (step-turning 0.002)}
            :turning-right {:transitions [{:pred (command-match :turn-left/stop :turn-right/stop)
                                           :transition :standing}
                                          {:pred (command-match :turn-left)
                                           :transition :turning-left}]
                            :step (step-turning -0.002)}}})

(defn ^:export moveable [entity]
  (-> entity
      (update :states conj walking)
      (update :states conj strafing)
      (update :states conj turning)
      (assoc-in [:current-states (:name walking)] :standing)
      (assoc-in [:current-states (:name strafing)] :standing)
      (assoc-in [:current-states (:name turning)] :standing)))

(defn update-fsm [entity delta-t world])

(defn ^:export player-movement
  "Given a keymap and entity, add input-driven movement component to entity, and returns updated entity."
  [entity keymap]
  (let [input-signal (s/map (fn [event]
                              (if-let [command (keymap (:key event))]
                                (if (= :down (:press event))
                                  command
                                  (keyword command :stop))))
                            input/keyboard)]
    ;; check if exists?
    (-> entity
        (assoc :input keymap)
        (assoc :commands input-signal))))

(defn ^:export handle-world-commands [entity delta-t world]
  (let [command (s/value (get entity :commands))
        result (condp = command
                 :bounding-boxes-toggle (render/show-bounding-boxes entity)
                 nil)]
    (if (some? result)
      (do
        (s/propagate (get result :commands) :handled)
        result)
      entity)))

(defn ^:export add-world-commands
  "Given a keymap and entity, add input-driven movement component to entity, and returns updated entity."
  [world keymap]
  (let [input-signal (s/map (fn [event]
                              (if-let [command (keymap (:key event))]
                                (if (= :down (:press event))
                                  command
                                  (keyword command :stop))))
                            input/keyboard)]
    (-> world
        (assoc :input keymap)
        (assoc :commands input-signal)
        (add-behavior handle-world-commands))))

(defn- follow*
  [target offset]
  (fn [entity dt world]
    (let [followee (get world target)]
      (if (and (some? (:position entity))
               (some? (:position followee)))
        ;;complicated behavior goes here
        (assoc entity :position (m/add (:position followee)
                                       offset))
        entity))))

(defn ^:export follow
  [entity target offset]
  ;; add position if it isn' there
  ;; add in behavior component
  (add-behavior entity (follow* target offset)))
