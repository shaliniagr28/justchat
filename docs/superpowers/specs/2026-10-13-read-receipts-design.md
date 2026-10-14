# Read receipts (1:1 chat) — design

Date: 2026-10-13
Status: implemented (uncommitted at time of writing); this document was written after the
build, from the design agreed in conversation.

## Intent

The sender of a 1:1 message should be able to tell not just that a message reached the
other person's device ("delivered"), but that the other person has actually looked at it
("read"). The app already tracked `SENT → DELIVERED`; this adds `READ`.

## What was decided (by the product owner)

- A message shows **delivered** once the server has pushed it to the recipient, and stays
  delivered until the recipient reads it.
- A message counts as **read** only when the recipient has **that conversation open in an
  active window** — the browser tab is visible and the window has focus. A background tab,
  a minimized window, or a different conversation being open all leave it at delivered.

## What was proposed and approved as a whole

These were played back as guesses and approved together ("that looks right, go ahead"):

- Status is shown to the **sender**, under their own outgoing messages. The recipient's
  screen doesn't change.
- When the recipient opens a conversation (or returns to a window already showing it),
  **every unread message from the other person in that conversation** becomes read at once,
  including ones received while offline. Scroll position is not tracked.
- With several tabs open, reading in **any one** counts.
- **Read is final** — a message never goes back to delivered.
- Read status is **persisted** like delivered, so reloading shows the correct state.
- If the sender is **offline** when their message is read, they don't get a live update;
  they see the correct status the next time they load the conversation. This matches the
  existing, documented gap for `DELIVERED` status pings (see README "Known limitations").

## Goals / non-goals

Goals
- Per-message `SENT` / `DELIVERED` / `READ` visible to the sender, live when both are online.
- Correct status after reload, from the database.

Non-goals
- Read receipts for group chat (in-progress group work is separate).
- Replaying missed status pings to a sender who was offline.
- Per-message "seen" based on scroll/viewport position.
- Letting users turn read receipts off.
- Showing *when* a message was read in the UI (the timestamp is stored, not displayed).

Anti-goals
- A message marked read that the recipient never had on screen (e.g. because it arrived
  while the read signal was in flight).
- Status visibly going backwards (read → delivered).

## How it works

### Protocol

One new client → server frame on the existing `WsEnvelope` shape:

```
READ { type: "READ", senderId, id }
```

Meaning: "I have read everything `senderId` sent me, up to and including message `id`."
`id` is the newest message from that sender the client is actually showing, so a message
that arrives between the client deciding and the server processing is not swept up.

The server answers with the existing `STATUS_UPDATE { id, status: "READ" }` frame, one per
message, to each of the sender's live sessions. Nothing is sent back to the reader.

### Backend

- `MessageStatus` gains `READ`. `Message` gains a nullable `readAt` and `markRead()`, which
  is a no-op once read and also stamps `deliveredAt` if it was still null (read implies
  delivered). `markDelivered()` already only acts on `SENT`, so it can't downgrade `READ`.
- `MessageRepository.findByRecipientIdAndSenderIdAndStatusInAndIdLessThanEqual` finds the
  reader's unread messages from one sender up to the given id.
- `MessageService.markThreadRead(readerId, senderId, upToId)` marks and saves them and
  returns the changed rows (empty → no write).
- `ChatWebSocketHandler.handleRead` validates `senderId` and `id` (otherwise `ERROR`),
  calls the service with the **session's own user id as the reader** — so a client can only
  mark messages addressed to itself — then reuses `notifySenderOfStatus` per message.
- Metric `justchat.messages.read` counts messages flipped to `READ`.

### Schema

Hibernate generated a `messages_status_check` constraint allowing only `SENT`/`DELIVERED`
when the table was first created, and `ddl-auto: update` never revisits it — so existing
databases would reject `READ`. `backend/src/main/resources/schema.sql` rewrites that
constraint on startup (`spring.sql.init.mode: always`). It uses `ALTER TABLE IF EXISTS`, so
on a fresh database (where it runs before Hibernate creates the table) it does nothing and
Hibernate creates the table with all three values. Verified against the local Postgres in a
rolled-back transaction. A real migration tool would replace this.

### Frontend

- `useChatSocket` tracks whether the window is active (`visibilitychange`, `focus`,
  `blur`; active = visible **and** focused).
- An effect watches the selected conversation: when the window is active, the socket is
  open, and there are messages from the other person not yet `READ`, it sends one `READ`
  frame with the highest such id and marks those messages `READ` locally so it doesn't
  fire again. This covers opening a conversation, refocusing the window, reconnecting,
  REST history loading, and new messages arriving while the conversation is in view.
- `STATUS_UPDATE` handling ignores any status that isn't an advance (`SENT < DELIVERED <
  READ`), so out-of-order pings can't move a message backwards.
- `ChatWindow` labels the sender's own messages `✓ Sent`, `✓✓ Delivered`, `✓✓ Read`
  (read in light cyan), and `…` while still pending.

## Calls made by the builder (not discussed — check these)

- Status shown as tick + word, keeping the existing ✓/✓✓ ticks and adding labels.
- Read colour `#9fe8ff` on the blue outgoing bubble.
- One `STATUS_UPDATE` frame per message rather than a new batched frame, to reuse the
  existing frame and client handling.
- `readAt` stored but not shown.
- Schema fix via startup `schema.sql` rather than introducing Flyway/Liquibase.
- Added the `justchat.messages.read` metric; it is not on the Grafana dashboard.

## Known limitations

- Sender offline when a read happens → no live update; correct on next load (same as
  delivered today).
- "Active window" is the browser's notion of focus/visibility; a visible but unfocused
  window (e.g. on a second monitor) does not count as reading, by design.
- Single-instance only, like the rest of the real-time layer.
- **Group messages are kept out of all of this.** Group messages used to be stored with the
  group's id in `recipientId`, so a user whose id equalled a group's id would have received
  that group's messages as 1:1 messages (and could mark them read). Fixed alongside this
  work: `Message` now has a separate `groupId`, `recipientId` is null for group messages, and
  every 1:1 query matches on `recipientId`. `schema.sql` drops the old `NOT NULL` on
  `recipient_id` for existing databases. Group messages have no read receipts.

## Tests

- `MessageTest`: read stamps, read-from-sent, idempotency, no downgrade.
- `MessageServiceTest`: marks and saves only the matched rows; no save when nothing unread.
- `ChatWebSocketHandlerTest`: `READ` marks and pings an online sender per message; offline
  sender still marked without pings; missing `senderId`/`id` rejected.
- `ChatWebSocketHandlerTest` (group): group send is stored via `persistNewGroupMessage` and
  pushed to other members only; a failed push evicts that member's session (previously it
  looked up a null user id and threw) and still reaches the rest.
- Verified end-to-end against a real backend + Postgres with two WebSocket clients:
  delivered → read pings, persisted status and `readAt`, a user can't mark another user's
  inbox read, malformed `READ` rejected, group message stored with `group_id` and kept out
  of 1:1 history.
- No frontend tests (project has no frontend test setup); the browser UI itself has not
  been clicked through.
