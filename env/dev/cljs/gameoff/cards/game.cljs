(ns gameoff.cards.game
  (:require [reagent.core :as reagent :refer [atom]]
            [gameoff.core :as core]
            [gameoff.render.core :as render]
            [gameoff.signals :as signals]
            [gameoff.behavior :as behavior]
            [gameoff.physics :as physics]
            [devcards.core :as dc])
  (:require-macros
   [devcards.core
    :as dc
    :refer [defcard defcard-doc defcard-rg deftest]]))

(defonce game-state
  (atom {:include "gltf/scene.gltf"
         :scene {:current-scene :Scene
                 :camera :PlayerCamera}
         :Cube (-> {:position [7.26, 2.25, 15.76]
                    :collidable true})
         :Fox (-> {:position [0.0 2.5 0]
                   :rotation [0.7071068286895752
                              0.0
                              0.0
                              0.7071068286895752]
                   :heading [0 1 0]
                   :up [0 0 -1]
                   :renders {}
                   :collidable true}
                  (behavior/player-movement
                   {"w" :forward
                    "s" :backward
                    "a" :left
                    "d" :right
                    "q" :turn-left
                    "e" :turn-right})
                  (behavior/moveable)
                  (physics/body 1.0 0.005))}))

(defcard-rg load-scene
  (fn [game-state _] [core/reagent-renderer game-state])
  game-state
  {:inspect-data true})
