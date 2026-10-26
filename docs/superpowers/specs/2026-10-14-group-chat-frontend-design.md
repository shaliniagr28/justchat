# Group chat (frontend + supporting backend) — design

Date: 2026-10-14
Status: draft for approval (builder review done)

## Intent

JustChat has a partial group-chat backend (built during a live interview exercise): you can
create and list groups over REST and send a message to a group over the WebSocket, but there
is no way to add members, no group history, no membership checks, and no UI at all. Group
messages currently show up inside the 1:1 chat with whoever sent them. This work makes group
chat usable end to end from the browser.

## What was decided (by the product owner)

- **Layout:** the sidebar gets two tabs, **People** and **Groups** (mockup option B). The
  Groups tab has a "+ New group" action above the list of groups the user belongs to.
- **Creating:** "+ New group" asks only for a name, creates the group, and opens it (empty).
- **Adding members:** by username, from an "Add member" box in the open group's header, at
  creation time or any time later. **Only the group's creator sees and can use it.** Added
  people don't accept or join — they're in.
- **History:** opening a group shows its **entire** history, including messages sent before
  the viewer was added and anything they missed while offline.

## Proposed and approved as a whole ("Rest looks good")

- The group header shows the group name and its member list.
- Messages from others show the **sender's username** above the bubble, in a colour that's
  stable per sender. Your own messages look like your 1:1 messages (right side, blue).
- The Groups tab gets red unread badges like the People list: per group and on the tab
  itself. Session-only, like today's badges (reset on reload).
- Adding a member posts a small centred line in the group, e.g. "alice added bob", visible
  to everyone and kept in history.
- Someone added while online sees the group appear in their Groups tab without reloading
  and starts receiving its messages live.
- Adding a username that doesn't exist, or someone already in the group, shows a short
  error under the box.
- Group messages have **no** Sent/Delivered/Read status.
- No removing members, leaving, renaming, or deleting groups.
- Only members can post to a group or read its history (the backend doesn't enforce this
  today and must).

## Goals / non-goals / anti-goals

Goals
- Create a group, add members (creator only), chat live, see full history — all in the UI.
- Group messages never appear in, or affect, 1:1 conversations (badges, read receipts).

Non-goals
- Delivery/read status for group messages; per-member read tracking.
- Removing/leaving/renaming/deleting groups; admin roles beyond "creator".
- Server-side unread counts or replay of missed group messages over the socket (history
  over REST covers "missed while offline").
- Group search/discovery in the UI (you only see groups you're in).

Anti-goals
- A non-member able to read or post to a group (via UI, REST, or a hand-crafted WS frame).
- A non-creator able to add members, even by calling the API directly.
- Breaking any existing 1:1 behaviour, including read receipts
  (`docs/superpowers/specs/2026-10-13-read-receipts-design.md`).

## Constraints

- The graded rule from CLAUDE.md: no library may implement real-time messaging for the app.
  All live behaviour goes through the existing hand-built `ChatWebSocketHandler` /
  `ConnectionRegistry` and `WsEnvelope` frames. No STOMP, no socket libraries.
- Frontend: React + TypeScript, no UI or state library, inline styles matching the existing
  components; state stays in hooks (`useChatSocket`) and `App.tsx`.
- Schema via `ddl-auto: update`; anything `update` can't do on an existing database goes in
  `backend/src/main/resources/schema.sql` (see that file).
- Backend tests in the existing style: plain Mockito, MockMvc `standaloneSetup`, no Spring
  context. No frontend test setup exists.
- Single backend instance (same as the rest of the real-time layer).

## Current state (what exists)

- `Group` (id, groupName), `GroupMembers` (groupId, userId). `GroupService.createGroup`
  adds the creator as the first member. `GET /api/groups` lists the caller's groups;
  `GET /api/groups?q=` searches **all** groups by name.
- `Message` has `groupId`; group messages have `recipientId = null` (fixed in 2581ce2), so
  1:1 queries never see them.
- WS `SEND { groupId, content, clientMsgId }` persists and fans out a `MESSAGE` (with
  `groupId`) to every other member's live sessions, echoing to the sender with
  `clientMsgId`. No membership check, no content check.
- Frontend: no group code. `useChatSocket` files every incoming `MESSAGE` under the other
  party of a 1:1 pair, which is why group messages currently land in 1:1 chats.

## How it works

### Backend

Data
- `Group` gains `createdBy` (user id). Existing rows (none in the local DB today) may have it
  null; treat null as "nobody can add members".
- The "alice added bob" line is stored as a message in the group so it appears in history in
  order. It needs to be distinguishable from a chat message — e.g. a `system` flag (or a
  `kind`) on `Message`, exposed on `MessageDto` and the `MESSAGE` frame. Exact shape is the
  builder's call.

REST (all under the existing authenticated `/api`, JSON errors as `{ "error": "..." }`)
- `POST /api/groups { groupName }` — unchanged behaviour; response includes `createdBy` and
  `members`.
- `GET /api/groups` — the caller's groups (unchanged). Each item includes `createdBy`.
- `GET /api/groups/{id}` — group detail: `id, groupName, createdBy, members: [{id, username}]`.
  403 if the caller isn't a member.
- `POST /api/groups/{id}/members { username }` — creator only (403 otherwise). 404 if the
  username doesn't exist, 409 if already a member. On success: adds the member, persists the
  "X added Y" system message, and triggers the live notifications below. Returns the updated
  group detail.
- `GET /api/groups/{id}/messages` — full group history ordered by message id, 403 for
  non-members.
- `MessageDto` gains `groupId` (and the system flag).

WebSocket
- `SEND` with `groupId`: reject with `ERROR` if the sender isn't a member or content is
  blank, before persisting.
- New server → client frame `GROUP_ADDED { groupId, ... }` sent to the newly added member's
  live sessions so their Groups tab updates without a reload. Document it in the
  `WsEnvelope` class doc.
- The system message is pushed as a normal `MESSAGE` (with `groupId`) to all members'
  live sessions, including the new member and the creator.
- The REST add-member call needs to reach the WS layer for these pushes (e.g. a small
  component that owns "push this envelope to these users" reused by the handler). How it's
  factored is the builder's call; it must stay hand-built on `ConnectionRegistry`.

### Frontend

- `App.tsx` selection becomes either a user or a group (e.g. a tagged union), so the right
  pane renders a 1:1 `ChatWindow` or a group chat view.
- Sidebar: a tab bar (People | Groups) above the list. People = today's `UserList`
  unchanged. Groups = "+ New group" plus the caller's groups, with unread badges. The Groups
  tab label shows a total unread badge.
- "+ New group": inline name input (no modal library) → `POST /api/groups` → select it.
- Group view: header with name, member usernames, and — for the creator only — an
  "Add member by username" input + Add button with an inline error line. Body: messages
  with sender name above others' bubbles (stable colour per sender id), system messages as
  a centred grey line, own messages on the right. Composer sends `SEND { groupId, ... }`.
- `useChatSocket`: group messages (`groupId` set) go into a separate per-group store
  (`Map<groupId, ChatMessage[]>`), never the 1:1 map; optimistic send + `clientMsgId`
  reconciliation works the same way as 1:1. Unread counts per group, bumped when a group
  message from someone else arrives for a group that isn't open. `GROUP_ADDED` triggers a
  refresh of the groups list. A system "added" message for the open group refreshes its
  member list.
- The read-receipt effect must only consider 1:1 messages (it already filters by
  `senderId === selectedUserId` within the 1:1 map; keeping groups in a separate map keeps
  it that way).
- Opening a group loads `GET /api/groups/{id}` and `GET /api/groups/{id}/messages`.
- **Reconnect:** when the socket reconnects without a page reload, re-fetch the history of
  the open group (and the group list), because there is no socket replay for groups and a
  brief network drop would otherwise leave a silent gap. Loading history must **merge** into
  what's in memory (by message id, keeping still-pending optimistic sends) rather than
  replace it, so live messages arriving during the fetch aren't lost.
- **Tab badges:** both tab labels show a total unread badge: People sums the 1:1 counts,
  Groups sums the group counts. Otherwise a 1:1 message arriving while the Groups tab is
  showing would go unnoticed, which would break existing 1:1 behaviour.

## Builder's calls (not discussed — check these)

- Inline "+ New group" name input inside the Groups tab rather than a dialog.
- Stable sender colour derived from user id from a small fixed palette.
- `GET /api/groups?q=` (search across all groups) is left as-is but unused by the UI.
- The system message's `senderId` is the creator; its text is rendered client-side from
  structured data or stored as text — builder decides.
- No pagination of group history (same as 1:1 history today).
- Re-fetch open-group history and the group list on socket reconnect, merging rather than
  replacing (raised by the builder review).
- Total unread badge on the People tab as well as Groups (raised by the builder review).

## Testing

- Backend unit tests (existing style) for: membership enforcement on WS group send; creator-
  only add; 404/409 on add; history and detail endpoints 403 for non-members; system message
  persisted and pushed; `GROUP_ADDED` sent to the new member's sessions.
- End-to-end check against a real backend + Postgres with WebSocket clients (as done for
  read receipts): create, add, live message to members, non-member rejected, history.
- Manual browser walkthrough with three users (creator, member, non-member).
