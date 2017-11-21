(ns gameoff.behavior
  (:require [gameoff.signals :as s]
            [gameoff.input :as input]
            [gameoff.vector :as v]))

(defn- fsm-null [entity delta-t world])

(defn ^:export movement-fsm [command-signal]
  (letfn [(enter-standing []
            {:state :standing,
             :transition standing
             :update fsm-null})
          (standing [state command]
            (condp = command
              :forward (enter-moving-forward)
              :backward (enter-moving-backward)
              state))
          (standing-update [entity delta-t world]
            entity)
          
          (enter-moving-backward []
            {:state :moving-backward
             :transition moving-backward
             :update backward-update})
          (moving-backward [state command]
            (condp = command
              :forward (enter-moving-forward)
              :stop (enter-standing)
              state))
          (backward-update [entity delta-t world]
            entity)
          
          (enter-moving-forward []
            {:state :moving-forward
             :transition moving-forward
             :update forward-update})
          (moving-forward [state command]
            (condp = command
              :down (enter-moving-backward)
              :stop (enter-standing)
              state))
          (forward-update [entity delta-t world]
            entity)]

    (s/foldp (fn [state command]
               ((:transition state) state command))
             {:state :standing, :transition standing}
             command-signal)))

(defn ^:export player-movement
  "Given a keymap and entity, add input-driven movement component to entity, and returns updated entity."
  [entity keymap]
  (let [input-signal (s/map (fn [event]
                              (if-let [command (keymap (:key event))]
                                (if (= :down (:press event))
                                  command
                                  :stop)))
                            input/keyboard)]
    ;; check if exists?
    (-> entity
        (assoc :input keymap)
        (assoc :movement (movement-fsm input-signal)))))

(defn- patrol
  [left right signal]
  (fn [entity dt world]
    (when (some? (:position entity))
      (let [pos (:position entity)]
        (when (< (:x pos) (:x left))
          (s/propagate signal {:key :right, :press :down}))
        (when (> (:x pos) (:x right))
          (s/propagate signal {:key :left, :press :down}))))
    entity))

(defn ^:export add-patrol
  "Simple AI. Cause them to walk back and forth"
  [entity left right]
  (let [command-signal (s/signal nil "patrol-ai")
        bah (-> entity
                (assoc :ai (patrol left right command-signal))
                (assoc :movement (movement-fsm command-signal))
                (assoc :bah command-signal))]
    (s/propagate command-signal {:key :left, :press :down})
    bah))

(defn ^:export behavioral? [entity]
  (some? (get entity :behaviors)))

(defn ^:export add-behavior [entity behavior]
  (update entity :behavior (fn [behaviors]
                             (if (contains? entity :behavior)
                               (comp behavior behaviors)
                               behavior))))

(defn ^:export propagate
  "Propagate AI behavior over time"
  ([entity delta-t world]
   (if (behavioral? entity)
     ((get entity :behavior) entity delta-t world)
     entity))
  ([delta-t]
   (fn [xform]
     (fn
       ([] (xform))
       ([world] (xform world))
       ([world entity]
        (let [[id automata] (first entity)]
          (xform world {id (propagate automata delta-t world)})))))))

(defn- follow*
  [target offset]
  (fn [entity dt world]
    (let [followee (get world target)]
      (if (and (some? (:position entity))
               (some? (:position followee)))
        ;;complicated behavior goes here
        (assoc entity :position (v/add (:position followee)
                                       offset))
        entity))))

(defn ^:export follow
  [entity target offset]
  ;; add position if it isn' there
  ;; add in behavior component
  (add-behavior entity (follow* target offset)))