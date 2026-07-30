(ns kaigi.plan
  "Who subscribes to whom — the media-topology decision, as pure functions.

  Given a meeting value, this namespace answers: which remote tracks should
  each participant be receiving right now, and what operations move them from
  what they have to what they should have. The host executes the returned
  operations; it does not decide them.

  ## Two transports, one decision function

  `transport` picks `:sfu` when a Cloudflare Realtime app is configured and
  `:mesh` when it is not. The choice is a function of configuration, not of
  participant count, so a deployment behaves the same on every join instead
  of switching topology mid-meeting and having to renegotiate everything.

  The mesh path exists because it needs no external credential and works
  today; it is honest about its ceiling (`mesh-viable?`). The SFU path is the
  one that scales, because each participant then uploads once regardless of
  how many people are watching.

  ## Diffs, not rebuilds

  `pull-diff` returns only what changed. Re-subscribing to tracks a
  participant already has would renegotiate the PeerConnection on every
  roster change, which is visible as a video freeze for everyone — so the
  plan is always expressed as `pull these, close those`."
  (:require [kaigi.model :as m]
            [kaigi.sfu :as sfu]
            [kotoba.webrtc.room :as room]))

(def mesh-participant-ceiling
  "Above this many participants a full mesh stops being reasonable: each
  participant uploads its own media once per peer, so upload scales with
  n-1. Four is where a typical consumer uplink is still comfortable at
  ordinary webcam bitrates.

  This is a stated limit, not an enforced one — `mesh-viable?` reports it and
  the host decides what to do. Silently degrading quality would be worse
  than saying the meeting outgrew the transport."
  4)

(defn transport
  "`:sfu` when `config` has an app id and token, `:mesh` otherwise."
  [config]
  (if (sfu/configured? config) :sfu :mesh))

(defn mesh-viable?
  "True when the meeting is small enough for a full mesh."
  [mtg]
  (<= (count (room/participant-ids (:kaigi/room mtg))) mesh-participant-ceiling))

;; ---------------------------------------------------------------------------
;; track identity
;; ---------------------------------------------------------------------------

(defn track-key
  "The canonical identity of a remote track: publisher SFU session plus track
  name. A vector, so it compares and hashes by value and can key a map of
  live subscriptions."
  [session-id track-name]
  [session-id track-name])

(defn- publishers
  "Every admitted, in-room participant other than `viewer-id` that has an SFU
  session id, as `[participant-id session-id]` pairs.

  Participants without a session id are skipped rather than errored: a
  participant who has knocked and been admitted but whose PeerConnection has
  not been created yet is a normal, transient state, and treating it as a
  fault would make every join a race."
  [mtg sessions viewer-id]
  (let [in-room (room/participant-ids (:kaigi/room mtg))]
    (for [pid   in-room
          :when (and (not= pid viewer-id)
                     (m/admitted? mtg pid))
          :let  [sid (get sessions pid)]
          :when (some? sid)]
      [pid sid])))

;; ---------------------------------------------------------------------------
;; SFU plan
;; ---------------------------------------------------------------------------

(defn desired-pulls
  "The set of `track-key`s `viewer-id` should be subscribed to.

  Every track published by every other admitted participant in the room. The
  publisher decides what exists — a participant who turns their camera off
  unpublishes, and it disappears from every viewer's desired set on the next
  diff. Encoding 'camera off' as 'still subscribed but ignore it' would keep
  paying for the stream and still show a black tile."
  [mtg sessions viewer-id]
  (into #{}
        (for [[pid sid] (publishers mtg sessions viewer-id)
              tname     (or (room/tracks-of (:kaigi/room mtg) pid) #{})]
          (track-key sid tname))))

(defn pull-diff
  "What to change for one viewer.

      subscribed  {track-key -> mid}   what the viewer currently receives
      desired     #{track-key}         what it should receive

  Returns

      {:kaigi.plan/pull  [{:session-id … :track-name …} …]
       :kaigi.plan/close [mid …]}

  `:pull` is shaped for `kaigi.sfu/pull-tracks-request` and `:close` for
  `kaigi.sfu/close-tracks-request`. Both are sorted so a plan is stable
  across calls — an unstable plan makes a diff of two plans unreadable and
  makes tests flap."
  [subscribed desired]
  (let [have (set (keys subscribed))
        add  (sort (remove have desired))
        drop-keys' (sort (remove desired have))]
    {:kaigi.plan/pull  (mapv (fn [[sid tname]] {:session-id sid :track-name tname}) add)
     :kaigi.plan/close (mapv subscribed drop-keys')}))

(defn sfu-plan
  "The full SFU plan for one viewer: the diff between what it has and what
  `desired-pulls` says it should have."
  [mtg sessions viewer-id subscribed]
  (pull-diff subscribed (desired-pulls mtg sessions viewer-id)))

(defn empty-plan?
  "True when a plan asks for nothing. Worth checking before a request: an
  empty `tracks/new` still costs a round trip and counts against the SFU's
  per-session API rate limit."
  [plan]
  (and (empty? (:kaigi.plan/pull plan))
       (empty? (:kaigi.plan/close plan))))

;; ---------------------------------------------------------------------------
;; mesh plan
;; ---------------------------------------------------------------------------

(defn mesh-initiator
  "Which of two participants sends the offer. The lexicographically smaller
  id, always.

  This is the fix for signaling glare: if both peers offer when they notice
  each other, both end up with a local offer and neither can answer, and the
  call fails in a way that looks like a network problem. A total order over
  ids means exactly one side offers, decided identically on both sides
  without any extra message."
  [a b]
  (if (neg? (compare (str a) (str b))) a b))

(defn mesh-plan
  "For `viewer-id`, the peer connections it should hold and its role in each.

      [{:kaigi.plan/peer-id \"…\" :kaigi.plan/role :offerer|:answerer} …]

  Sorted by peer id. Every other admitted in-room participant appears
  exactly once."
  [mtg viewer-id]
  (let [in-room (room/participant-ids (:kaigi/room mtg))]
    (->> in-room
         (filter (fn [pid] (and (not= pid viewer-id) (m/admitted? mtg pid))))
         sort
         (mapv (fn [pid]
                 {:kaigi.plan/peer-id pid
                  :kaigi.plan/role    (if (= viewer-id (mesh-initiator viewer-id pid))
                                        :offerer
                                        :answerer)})))))

;; ---------------------------------------------------------------------------
;; entry point
;; ---------------------------------------------------------------------------

(defn plan-for
  "The plan for one viewer under whichever transport `config` selects.

  Returns a map carrying `:kaigi.plan/transport` plus either the SFU diff
  keys or `:kaigi.plan/peers` for the mesh, and — in mesh mode — a
  `:kaigi.plan/warning` when the meeting has outgrown the ceiling. The
  warning is data rather than a log line so the UI can show it; a limit the
  operator only learns about from server logs is a limit the participants
  experience as 'the app is broken'."
  [mtg sessions viewer-id subscribed config]
  (case (transport config)
    :sfu (assoc (sfu-plan mtg sessions viewer-id subscribed)
                :kaigi.plan/transport :sfu)
    :mesh (cond-> {:kaigi.plan/transport :mesh
                   :kaigi.plan/peers (mesh-plan mtg viewer-id)}
            (not (mesh-viable? mtg))
            (assoc :kaigi.plan/warning
                   {:kaigi.plan/code :mesh/over-ceiling
                    :kaigi.plan/ceiling mesh-participant-ceiling
                    :kaigi.plan/count (count (room/participant-ids (:kaigi/room mtg)))}))))
