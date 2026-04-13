# Message transport — three steps (in order)

When the user sends a message to a **known peer** (stable `appId`), the app picks **one** path. Check in this order; **stop at the first step that applies**.

## 1. Wi‑Fi / internet (server)

**When:** The device has validated internet (e.g. `NetworkUtils.hasInternet`).

**What:** Talk to the **destination `appId`** through your **HTTP API** (e.g. `ServerClient.postMessage`). No BLE scan is required for this step.

**Session:** The chat is a logical “server route” to that `appId` (e.g. `WE_ARE_INTERNET` in `MessagingConnectionState`), not a direct BLE link.

---

## 2. BLE direct (same peer, in radio range)

**When:** Step 1 does **not** apply (offline), **and** a targeted BLE scan finds a peripheral whose **advertised service data** matches the **destination `appId`**.

**What:** Open a normal BLE chat: central writes / peripheral notifies on your messaging characteristics, handshake carries ids, etc.

**Session:** `WE_ARE_CENTRAL` or `WE_ARE_PERIPHERAL` with that peer.

---

## 3. Gateway / relay (offline, peer not on BLE)

**When:** Step 1 does **not** apply, **and** step 2 fails (timeout: target `appId` never seen on BLE).

**What:** Still open a **logical session to the same destination `appId`** (again e.g. `WE_ARE_INTERNET`), but **sends** must **not** assume HTTP works. Use **BLE relay**: scan for **any** nearby app peer, connect briefly, write a **relay packet** (`TYPE_RELAY`) so a neighbor with internet can **forward to the server** (e.g. `RelayManager.flood` → neighbor `onReceived` → `ServerClient.postMessage`).

**Important:** The gateway’s BLE `appId` **does not** have to match the chat destination; only the **payload inside the relay** carries the real `destinationAppId`.

---

## Rules of thumb for code changes

- **`BleMessaging` (or equivalent)** must handle the **server / relay** role: if online → POST; if offline in that role → **flood**, not “do nothing.”
- **After a failed BLE peer scan**, offline devices still need **`setConnectedAsInternet(destAppId, …)`** (or equivalent) so step 3 can run; showing only a toast and leaving **no session** breaks relay.
- **Do not cancel** an in‑progress “find peer” scan from `onPause` unless you intentionally abandon the flow; otherwise **`onNotFound` may never run** and step 3 never activates.
- **Relay** writes must use the same GATT **write characteristic** the peripheral expects; **notify** stays on the measurement / notify characteristic. Mixing UUIDs breaks both chat and gateway.

---

## Reverse direction (server → offline device)

A device **with internet** polls for pending relay deliveries, scans BLE for the **recipient `appId`**, delivers a relay packet, then **confirms** to the server so messages are not stuck. That is the mirror of step 3, not a fourth “user send” step.
