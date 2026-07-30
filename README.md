# kaigi 会議

Online meetings (Zoom / Google Meet 相当) for this workspace: a portable CLJC
**meeting** model, a media-topology planner, a Cloudflare Realtime SFU binding,
and a Durable Object signaling room.

Sibling of the other workspace surfaces — `kotoba-lang/kaisha` (chat),
`calendar` (events), `mail` / `contacts` (correspondence), `slides` (decks).
Like those, the core stays pure data and pure functions; the host renders it,
persists it, and moves the bytes.

> **What this is not.** `kotoba-lang/gijiroku` is the *record* side — it reads
> Zoom / Meet / Teams recording APIs and drafts minutes. `kaigi` is the
> *hosting* side. They are complementary: gijiroku consumes other vendors'
> meetings, kaigi holds our own.

## Status

| Layer | State | Evidence |
|---|---|---|
| `kaigi.model` / `validate` / `plan` / `sfu` / `signal` | **implemented** | 52 tests / 184 assertions green on **both** JVM and nbb |
| DO signaling room (`kaigi.worker.*`) | **implemented, deployed** | 22/22 end-to-end checks against the live Worker, 4 consecutive clean runs |
| Cloudflare Realtime SFU path | **implemented, not exercised** | no Realtime app is provisioned for this account — see below |
| Browser UI + real media (`getUserMedia`, `RTCPeerConnection`) | **not built** | — |

So: **signaling works end to end against a real Durable Object; no media has
flowed yet.** A call needs the browser half, which does not exist in this repo.

## What it composes rather than reimplements

- `kotoba-lang/webrtc` — `kotoba.webrtc.room` holds participants, published
  tracks and subscriptions; `kotoba.webrtc.session` is the offer/answer
  reducer; `kotoba.webrtc` is ICE/SDP as data.
- `kotoba-lang/org-w3-webrtc-signaling` — `kotoba.rt.core/route-message`
  decides who receives a relayed frame.

`kaigi` adds the layer a *meeting* has and a *room* does not.

## The model

```clojure
(require '[kaigi.model :as k])

(def m
  (-> (k/meeting "m-1" "jun" {:title "週次" :lobby :approval-required})
      (k/start)
      (k/request-admission (k/participant "rin" {:name "Rin"}))))

(k/waiting-ids m)                      ;; => #{"rin"}  — the host's lobby queue
(k/admitted? m "rin")                  ;; => false
(-> m (k/admit "jun" "rin") (k/join "rin") (k/admitted? "rin"))  ;; => true
```

Four invariants are worth stating because they are the ones that are easy to
get wrong and expensive to get wrong:

**Admission is a path, not a boolean.** `:requested -> :admitted | :denied`,
and onward to `:removed`. A removed participant is kept (not deleted) so a
re-join is distinguishable from a first knock — and re-knocking lands at
`:requested` even under an `:open` lobby, because otherwise ejecting someone
accomplishes nothing.

**Recording consent is unanimous and explicit.** `recording-allowed?` is true
only when *every* admitted participant has consented. Silence is not consent, a
majority is not consent, and an empty meeting is not consent. Revoking mid-call
stops an in-progress recording. This is `gijiroku`'s charter rule G3, enforced
here rather than in the host, because the host is the layer under pressure to
skip it. Only an `:asset-ref` is ever stored — never media (G4).

**Screen share is exclusive by construction.** `start-share` clears the
previous sharer in the same update, so no state exists in which two
participants are sharing and the grid has to guess.

**Mute is asymmetric.** A moderator may mute someone; only its owner may
unmute. Opening a live microphone in someone else's room is not a moderation
action.

## Media topology

`kaigi.plan` decides who subscribes to whom, and returns *diffs* — re-pulling
tracks a participant already has renegotiates their PeerConnection on every
roster change, which everyone sees as a video freeze.

```clojure
(plan/plan-for meeting sessions "jun" subscribed config)
;; :sfu  => {:kaigi.plan/pull [{:session-id … :track-name …}] :kaigi.plan/close ["3"]}
;; :mesh => {:kaigi.plan/peers [{:kaigi.plan/peer-id "rin" :kaigi.plan/role :offerer}]}
```

`transport` picks `:sfu` when an app id and token are configured and `:mesh`
otherwise — a function of configuration, not of headcount, so a deployment
does not switch topology mid-meeting.

In mesh mode `mesh-initiator` gives a total order over participant ids so
**exactly one side offers**. Both peers computing it independently arrive at
the same answer with no extra message, which is the fix for signaling glare —
both sides offering, neither able to answer, failing in a way that looks like a
network problem.

Above `mesh-participant-ceiling` (4) the plan carries a `:kaigi.plan/warning`
**as data**, so the UI can say the meeting outgrew the transport. A limit the
operator only learns from server logs is a limit participants experience as
"the app is broken".

## SFU binding

`kaigi.sfu` shapes requests and parses responses; it holds no HTTP client and
no secret. Field names are transcribed from Cloudflare's published OpenAPI
document (`realtime-api-2024-05-21.yaml`, `servers: https://rtc.live.cloudflare.com/v1`),
and the tests assert those literal shapes — a misspelled `trackName` would
otherwise round-trip through the constructor and back unnoticed.

Two details that are load-bearing:

- **The app secret is not in a request descriptor.** `authorize` is the only
  function that attaches it, so descriptors can be logged and asserted on.
- **A per-track `errorCode` fails the whole response.** The SFU reports partial
  failures inside a 200; a caller that checks only the HTTP status records a
  subscription that was refused, and the participant sees a black tile with no
  error anywhere.

## Signaling room

`worker/` is a Cloudflare Worker whose `KaigiRoom` Durable Object serializes
one meeting. A meeting needs exactly one writer, and a DO is globally unique
and single-threaded per id — so "one writer" is a property of where the code
runs, not something the code implements with leases or fencing tokens. Live
room state lives in the DO; anything cross-meeting belongs in D1, because each
DO's storage is private and would split a cross-meeting query plane into one
island per room.

The class is a ClojureScript `deftype`, not a JavaScript shim: `deftype` emits
a positional constructor and writes `Object` protocol methods to the prototype
un-munged, which is exactly what the DO runtime calls by name.

The wire format is EDN. The model uses sets (`consents`, `tracks`) and keywords
(admission, roles); JSON round-trips neither, so every frame would need a
translation layer whose only job is undoing the format.

```
GET /api/kaigi/ws?meeting=<id>   WebSocket into the room
GET /api/kaigi/health            liveness + which transport is active
```

`health` reports the transport because "is the SFU configured" is the one
operational question whose wrong answer is invisible: a deployment missing
`REALTIME_APP_ID` works fine, in mesh, until the fifth participant joins.

## Build and test

```bash
# pure core, both runtimes
clojure -M:test
nbb --classpath "src:test:../webrtc/src:../org-w3-webrtc-signaling/src" run-tests.cljs

# worker
cd worker
npm install
node ../../../../scripts/resource-guard.mjs run build -- npx shadow-cljs release worker
nbb verify-bundle.cljs                      # actually import()s the artifact
npx wrangler dev --port 8799 --local &
nbb e2e.cljs ws://127.0.0.1:8799            # two real sockets against a real DO
npx wrangler deploy
```

`release`, never `compile`: a `compile` build exits 0, emits every expected
export name, and passes `node --check`, while importing a machine-local
`/tmp/cljs-runtime` path that cannot load standalone. `verify-bundle.cljs`
refuses to run against an artifact older than the newest source — added after a
failed build left the previous bundle in place and the verifier reported six
green checks against code that did not compile.

## What is missing, precisely

1. **The browser half.** No UI, no `getUserMedia`, no `RTCPeerConnection`. The
   signaling plane is proven; no audio or video has crossed it. This is the gap
   between "signaling works" and "you can have a meeting".
2. **The SFU path is untested against the real service.** No Cloudflare
   Realtime app exists for this account — the wrangler OAuth session carries no
   `calls`/`realtime` scope, and minting an app token needs the dashboard.
   Provisioning is one owner action and no code change:

   ```bash
   wrangler secret put REALTIME_APP_ID   --name kaigi
   wrangler secret put REALTIME_APP_TOKEN --name kaigi
   ```

   Until then `transport` reads `:mesh` and small meetings work.
3. **No TURN.** `TURN_URL` / `TURN_USERNAME` / `TURN_CREDENTIAL` are unset, so
   participants behind symmetric NAT will fail to connect over the mesh.
   `kotoba-lang/org-ietf-turn` has a real UDP relay listener and is the
   intended source.
4. **No recording pipeline.** The model tracks consent and an asset ref; nothing
   captures or stores media. The handoff to `gijiroku` is unbuilt.
5. **Not wired to `kaisha` or `calendar`.** `:kaigi/channel` exists so a meeting
   can name the chat channel it was called from; nothing reads it yet.
