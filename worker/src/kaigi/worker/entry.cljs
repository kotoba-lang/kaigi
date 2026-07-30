(ns kaigi.worker.entry
  "Worker entry: the `KaigiRoom` Durable Object class and the default fetch
  handler, both defined in ClojureScript.

  ## Why the class is a `deftype`

  A Durable Object binding needs a real exported class whose prototype carries
  `fetch`, `webSocketMessage`, `webSocketClose` and `webSocketError` under
  exactly those names, and whose constructor takes `(ctx, env)` positionally.
  ClojureScript's `deftype` emits precisely that: positional constructor
  fields and `Object` protocol methods written to the prototype un-munged. So
  the class needs no JavaScript shim — which matters here because CLAUDE.md
  forbids new hand-written `.mjs`, and a shim is where the logic would start
  leaking back into JS.

  The methods hold no logic of their own; they delegate to
  `kaigi.worker.room`, which is where the decisions are and where the state
  registry lives.

  ## Build

      npx shadow-cljs release worker

  `release`, never `compile` — a `compile` build exits 0 and emits every
  expected export name while importing a machine-local `/tmp/cljs-runtime`
  path that cannot load standalone at all. cloud-itonami's `:edge-api`
  comment records that this passed review more than once. Verify with
  `nbb verify-bundle.cljs`, which actually `import()`s the artifact."
  (:require [clojure.string :as str]
            [kaigi.worker.room :as room]))

(deftype KaigiRoom [ctx env]
  Object
  (fetch [_ request]
    (room/on-fetch ctx env request))
  (webSocketMessage [_ ws message]
    (room/on-message ctx env ws message))
  (webSocketClose [_ ws _code _reason _was-clean]
    (room/on-close ctx env ws))
  (webSocketError [_ ws _error]
    (room/on-close ctx env ws)))

(def ^:private meeting-id-pattern #"^[A-Za-z0-9_.:-]{1,128}$")

(defn- json
  [status body]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status
                     :headers #js {"content-type" "application/json; charset=utf-8"}}))

(defn- route-to-room
  "Forward a request to the DO instance for `meeting-id`.

  `idFromName` rather than a random id: the meeting id in the URL has to map
  to the same object for every participant, which is the entire point of the
  routing."
  [env meeting-id request]
  (let [ns' (aget env "KAIGI_ROOMS")
        id  (.idFromName ns' meeting-id)]
    (.fetch (.get ns' id) request)))

(defn on-fetch
  "Route table.

    GET /api/kaigi/ws?meeting=<id>   WebSocket upgrade into the room
    GET /api/kaigi/health            liveness plus which transport is active

  The health response reports `transport` because 'is the SFU configured'
  is the one operational question whose wrong answer is invisible: a
  deployment missing `REALTIME_APP_ID` still works, in mesh, until the fifth
  participant joins and the uplinks give out.

  Always returns a Promise, even for the routes that could answer
  synchronously. The Workers runtime accepts either, but a function that
  returns a bare Response on one path and a Promise on another cannot be
  driven uniformly by a caller — including the bundle verifier, which is the
  first caller that tripped over it."
  [request env _ctx]
  (let [url  (js/URL. (.-url request))
        path (.-pathname url)]
    (js/Promise.resolve
     (cond
       (= path "/api/kaigi/health")
       (json 200 {:ok true
                  :transport (if (and (aget env "REALTIME_APP_ID")
                                      (aget env "REALTIME_APP_TOKEN"))
                               "sfu" "mesh")})

       (= path "/api/kaigi/ws")
       (let [meeting (or (.get (.-searchParams url) "meeting") "")]
         (if (and (seq meeting) (re-matches meeting-id-pattern meeting))
           (route-to-room env meeting request)
           (json 400 {:error "meeting id must match [A-Za-z0-9_.:-]{1,128}"})))

       :else
       (json 404 {:error (str "no route for " path)})))))

(def handler
  "The Worker's default export."
  #js {:fetch (fn [request env ctx] (on-fetch request env ctx))})

;; Referenced so the compiler cannot elide the namespace under :advanced.
(defn- ^:export keep-alive [] (str/join "" ["kaigi"]))
