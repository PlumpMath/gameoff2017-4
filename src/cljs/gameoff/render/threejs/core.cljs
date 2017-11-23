(ns gameoff.render.threejs.core
  (:require [gameoff.render.core :as render]
            [gameoff.render.threejs.sprite :as sprite]
            [gameoff.render.threejs.texture :as texture]
            [cljsjs.three]
            [cljsjs.three-examples.loaders.GLTFLoader]
            [cljsjs.three-examples.loaders.MTLLoader]
            [cljsjs.three-examples.loaders.OBJLoader2]))

(defn ^:export create-program [gl vertex-source fragment-source]
  (let [program (.createProgram gl)
        vertex-shader (.createShader gl (.-VERTEX_SHADER gl))
        fragment-shader (.createShader gl (.-FRAGMENT_SHADER gl))]
    (.shaderSource gl vertex-shader vertex-source)
    (.shaderSource gl fragment-shader fragment-source)
    (.compileShader gl vertex-shader)
    (.compileShader gl fragment-shader)
    (.attachShader gl program vertex-shader)
    (.attachShader gl program fragment-shader)
    (.linkProgram gl program)
    program))

(defn ^:export load-gltf
  [backend path]
  (let [scenes (:scenes backend)
        gltf-loader (js/THREE.GLTFLoader.)]
    (.load gltf-loader path
           (fn [gltf]
             (doall (map (fn [scene]
                           (println (aget scene "name") (aget scene "type"))
                           (doall (map (fn [child]
                                         (println (aget child "name") (aget child "type")))
                                       (aget scene "children"))))
                         (aget gltf "scenes")))
             (let [cameras
                   (into {}
                         (map (fn [child]
                                {(keyword (str (aget child "name")))
                                 {:root child}})
                              (aget gltf "cameras")))
                   animations
                   (into {}
                         (map (fn [child]
                                {(keyword (str (aget child "name")))
                                 {:root child}})
                              (aget gltf "animations")))
                   loaded-scenes
                   (into {}
                         (map (fn [scene]
                                (.add scene (js/THREE.AmbientLight. 0xffffff))
                                {(keyword (str (aget scene "name")))
                                 {:root scene
                                  :children
                                  (into {}
                                        (map (fn [child]
                                               {(keyword (str (aget child "name")))
                                                {:root child}})
                                             (aget scene "children")))}})
                              (aget gltf "scenes")))]
               (let [cam (aget (get-in loaded-scenes [:Scene :children :Camera :root])
                               "children" 0 "children" 0)]
                 (println (aget cam "name") (aget cam "type") (count (aget cam "children"))))
               (println loaded-scenes)
               (swap! scenes (fn [scenes-map]
                               (let [current-animations (:animations scenes-map)
                                     current-cameras (:cameras scenes-map)]
                                 (-> scenes-map
                                     (assoc :animations (into animations current-animations))
                                     (assoc :cameras (into cameras current-cameras))
                                     (into loaded-scenes) ;for now, just overwrite, later try to do smart merge
                                     ))))))
           (fn [b] "Progress event")
           (fn [c] (println "Failed to load " path)))
    backend))

(def obj-loader (js/THREE.OBJLoader2.))
(def mtl-loader (js/THREE.MTLLoader.))

(defn- create-object
  [backend scene parent desc id]
  (println "Creating object " desc)
  (when-let [parent-root (get-in @(:scenes backend)
                                 [scene :children parent :root])]
    (if (= :obj (:type desc))
      (when (string? (:material desc))
        (let [pivot (js/THREE.Object3D.)]
          (swap! (:scenes backend) assoc-in [scene :children parent :children id]
                 {:root pivot :children {}})
          (.add parent-root pivot)
          (.setPath mtl-loader (:path desc))
          (.setCrossOrigin mtl-loader "anonymous")
          (.load mtl-loader (:material desc)
                 (fn [materials]
                   (.setSceneGraphBaseNode obj-loader pivot)
                   (.preload materials)
                   (.setMaterials obj-loader (aget materials "materials"))
                   (.setPath obj-loader (:path desc))
                   (.load obj-loader
                          (:geom desc)
                          (fn [event]
                            event))))))
      (let [geometry (js/THREE.BoxGeometry. 2 2 2)
            material (js/THREE.MeshStandardMaterial. (js-obj "color" 0x0bbbbb "wireframe" false))
            mesh (js/THREE.Mesh. geometry material)]
        (aset mesh "name" (name id))
        (.add parent-root mesh)
        (swap! (:scenes backend) assoc-in [scene :children parent :children id]
               {:root mesh :children {}})))))

(defn- update-object
  [entity mesh]
  (comment (when (or (= "Fox" (aget mesh "name"))
                     (= "Camera" (aget mesh "name")))
             (println (aget mesh "position" "x")
                      (aget mesh "position" "y")
                      (aget mesh "position" "z"))))
  (when (some? (:position entity))
    (set! (.-x (.-position mesh))
          (get-in entity [:position :x]))
    (set! (.-y (.-position mesh))
          (get-in entity [:position :y]))
    (set! (.-z (.-position mesh))
          (get-in entity [:position :z])))
  (when (some? (:rotation entity))
    (set! (.-x (.-rotation mesh))
          (get-in entity [:rotation :x]))
    (set! (.-y (.-rotation mesh))
          (get-in entity [:rotation :y]))
    (set! (.-z (.-rotation mesh))
          (get-in entity [:rotation :z])))
  (.updateMatrix mesh))

(defrecord ^:export ThreeJSBackend [renderer scenes]
  render/IRenderBackend
  (render [this entities camera]
    (comment (let [cam (:object (first (:renders (get entities camera))))
                   entities (doall (render/prepare-scene this (render/animate entities 0.0)))
                   ]
               (.render renderer scene cam)
               entities)))
  (renderx [this camera scene delta-t]
    (if-not (some? (get @scenes scene))
      (map identity)
      (fn [xform]
        (fn
          ([] (xform))
          ([result]
           ;; update all mixers
           (.render renderer
                    (get-in @scenes [scene :root])
                    (get-in @scenes [:cameras camera :root]))
           (xform result))
          ([result input]
           (let [[id entity] (first input)]
             (when (render/renderable? entity)
               (when-not (contains? (get-in @scenes [scene :children]) id)
                 (let [node (js/THREE.Object3D.)]
                   (aset node "name" (name id))
                   (.add (get-in @scenes [scene :root]) node)
                   (swap! scenes assoc-in [scene :children id]
                          {:root node
                           :children {}})))
               (when-let [obj (get-in @scenes [scene :children id :root])]
                 ;; update animationclips/animationactions based on state
                 (update-object entity obj))
               (when-not (empty? (:renders entity))
                 (loop [[render-id render-desc] (first (:renders entity))
                        the-rest (rest (:renders entity))]
                   (let [children (get-in @scenes [scene :children id :children])]
                     (if (contains? children render-id)
                       (when-let [obj (get-in children [render-id :root])]
                         ;; update animationclips/animationactions based on state
                         (update-object render-desc obj))
                       (create-object this scene id render-desc render-id)))
                   (when-not (empty? the-rest)
                     (recur (first the-rest) (rest the-rest)))))))    
           (xform result input)))))))

(defn- create-renderer
  ([]
   (doto (js/THREE.WebGLRender. {:antialias true})
     (.setPixelRatio js/window.devicePixelRatio)
     (.setSize 500 500)))
  ([element]
   (doto (js/THREE.WebGLRenderer. #js {:canvas element :antialias true})
     (.setPixelRatio js/window.devicePixelRatio)
     (.setSize 500 500))))

(defn ^:export init-renderer
  [canvas]  
  (->ThreeJSBackend (create-renderer canvas) (atom {})))

(defn ^:export setup-scene
  [backend state]
  (if (some? (:include state))
    (load-gltf backend (:include state))
    (let [scene    (js/THREE.Scene.)
          camera (js/THREE.PerspectiveCamera.
                    75 1 0.1 1000)
          light (js/THREE.AmbientLight. 0xffffff)
          light2 (js/THREE.PointLight. 0xffffff 2 0)]
      (set! (.-background scene) (js/THREE.Color. 0x6c6c6c))
      (.add scene light)
      (.set (.-position light2) 200 200 700)
      (.add scene light2)
      (aset camera "name" "camera")
      (aset camera "position" "z" 10)
      (.add scene camera)
      (swap! (:scenes backend)
             (fn [scenes-map]
               (-> scenes-map
                   (assoc :default
                          {:root scene
                           :children
                           {:sun {:root light}
                            :camera {:root camera}}})
                   (assoc :cameras
                          {:camera {:root camera}}))))
      backend)))

(defn ^:export js-renderer
  ([state] (js-renderer state js/document.body))
  ([state parent]
   (let [r (create-renderer)]
     (.appendChild parent (.-domElement r)))))

(comment
  (defn ^:export load-texture! [loader [key uri] rest-uris resources start-func]
    (.load loader uri
           (fn [js-texture]
             (set! (.-magFilter js-texture) js/THREE.NearestFilter)
             (set! (.-needsUpdate js-texture) true)
             (let [accum (assoc resources key
                                (texture/->ThreeJSTexture
                                 js-texture
                                 (.-width (.-image js-texture))
                                 (.-height (.-image js-texture))))]
               (if (empty? rest-uris)
                 (start-func (create-threejs-backend!) accum)
                 (load-texture! loader (first rest-uris) (rest rest-uris) accum start-func)))))))

(comment
  (defn ^:export load-resources! [start-func]
    (let [loader (js/THREE.TextureLoader.)
          textures {:placeholder "assets/images/placeholder.png"
                    :deer "assets/images/deer.png"
                    :background "assets/images/test-background.png"
                    :forest-0 "assets/images/forest-0.png"
                    :forest-1 "assets/images/forest-1.png"
                    :forest-2 "assets/images/forest-2.png"
                    :forest-3 "assets/images/forest-3.png"}]
      (load-texture! loader (first textures) (rest textures) {} start-func))))
