# Group Chat Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make group chat usable end to end in the browser — create a group, creator adds members by username, live group messaging, full history — on top of the existing hand-built WebSocket layer.

**Architecture:** Backend adds creator tracking, membership checks, add-member/detail/history REST endpoints, and a small `UserPusher` that both the WS handler and the REST controller use to push frames over `ConnectionRegistry`. Frontend keeps group messages in a separate per-group store in `useChatSocket`, and adds a People | Groups tabbed sidebar and a group chat view.

**Tech Stack:** Spring Boot 3.5 / Java 25 / JPA / Postgres; raw `TextWebSocketHandler`; React 18 + TypeScript (Vite), inline styles, no UI/state libraries.

**Spec:** `docs/superpowers/specs/2026-10-14-group-chat-frontend-design.md`

## Global Constraints

- No library may implement real-time messaging: all pushes go through `ConnectionRegistry` + `WsEnvelope` JSON frames. No STOMP, no socket libraries.
- Frontend: no UI library, no state library, inline styles matching existing components.
- Schema via `ddl-auto: update`; any new column on an existing table with rows must be nullable or carry a DB default.
- Backend tests: plain Mockito / MockMvc `standaloneSetup`, no Spring context. Run with `cd backend && ./mvnw test`.
- Errors from REST are JSON `{ "error": "..." }`.
- Only members can read or post to a group; only the creator can add members — enforced server-side.
- Group messages carry `groupId` and `recipientId = null`; they never enter 1:1 queries, the 1:1 frontend store, unread counts for people, or read receipts.
- Group messages have no Sent/Delivered/Read labels.

## Review Focus

1. A non-member sends a hand-crafted WS `SEND { groupId }` → `ERROR`, nothing persisted, nothing pushed. (Task 3)
2. Creator adds a username with surrounding spaces (`" bob "`) → trimmed and added; adds an existing member or themselves → 409 "already a member". (Task 2)
3. A new member's `GROUP_ADDED` and the system `MESSAGE` arrive in either order → the message is stored under its `groupId` even if that group isn't in the list yet, and the group shows with a badge once the list refreshes. (Task 4)
4. A live group message arrives while that group's history fetch is in flight → it's still there after the history lands (merge, not replace). (Task 4)
5. Sending to a group while the socket is closed → nothing is added optimistically (same as 1:1). (Task 4)

---

### Task 1: Data model and GroupService

**Files:**
- Modify: `backend/src/main/java/com/justchat/model/Group.java`, `model/Message.java`, `repository/GroupMemberRepository.java`, `repository/MessageRepository.java`, `service/GroupService.java`, `dto/MessageDto.java`, `dto/GroupResponseDto.java`, `.gitignore`
- Create: `backend/src/main/java/com/justchat/dto/GroupDetailDto.java`
- Test: create `backend/src/test/java/com/justchat/service/GroupServiceTest.java`; modify `model/MessageTest.java`

**Interfaces:**
- Produces:
  - `Group(String groupName, Long createdBy)`; `Long getCreatedBy()` (column nullable).
  - `Message.groupSystem(Long actorId, Long groupId, String text)` → message with `groupId`, `recipientId = null`, `isSystemMessage() == true`. Field `systemMessage` mapped `@Column(nullable = false, columnDefinition = "boolean not null default false")`.
  - `GroupMemberRepository.existsByGroupIdAndUserId(Long groupId, Long userId): boolean`
  - `MessageRepository.findByGroupIdOrderByIdAsc(Long groupId): List<Message>`
  - `GroupService.createGroup(String name, Long creatorId): Group` (now sets `createdBy`)
  - `GroupService.requireMember(Long groupId, Long userId): Group` — throws `java.util.NoSuchElementException("Group not found")` if no group, `org.springframework.security.access.AccessDeniedException("Not a member of this group")` if not a member.
  - `GroupService.isMember(Long groupId, Long userId): boolean`
  - `GroupService.memberIds(Long groupId): List<Long>`
  - `GroupService.members(Long groupId): List<User>`
  - `GroupService.history(Long groupId, Long userId): List<Message>` (calls `requireMember`)
  - `GroupService.addMember(Long groupId, Long actorId, String username): AddMemberResult` where `record AddMemberResult(Group group, User added, Message systemMessage)` is nested in `GroupService`. Throws `NoSuchElementException("Group not found")`, `AccessDeniedException("Only the group creator can add members")` (also when `createdBy` is null), `NoSuchElementException("No user named <trimmed>")`, `IllegalStateException("<username> is already a member")`. System message text: `"<actorUsername> added <addedUsername>"`.
  - `MessageDto` gains `Long groupId`, `boolean system`.
  - `GroupResponseDto` gains `Long createdBy`.
  - `GroupDetailDto(Group group, List<User> members)` with fields `id, groupName, createdBy, members: List<UserSummaryDto>`.
  - `GroupService` constructor becomes `(GroupRepository, GroupMemberRepository, UserRepository, MessageRepository)`.

- [ ] **Step 1: Write failing tests** in `GroupServiceTest` (Mockito, `@ExtendWith(MockitoExtension.class)`):
  - `createGroupRecordsTheCreatorAndAddsThemAsAMember` — saved group has `getCreatedBy() == 1L`; a `GroupMembers(groupId, 1L)` is saved.
  - `addMemberByCreatorAddsTheUserAndStoresASystemMessage` — group 7 created by 1 ("alice"), username `" bob "` resolves to user 2; asserts member (7,2) saved, returned `systemMessage.getContent() == "alice added bob"`, `isSystemMessage()`, `getGroupId() == 7L`, `getRecipientId() == null`.
  - `addMemberByNonCreatorIsForbidden` → `AccessDeniedException`, nothing saved.
  - `addMemberWithUnknownUsernameIsNotFound` → `NoSuchElementException` with message `"No user named zed"`.
  - `addMemberWhoIsAlreadyInTheGroupConflicts` → `IllegalStateException`, nothing saved (covers creator adding themselves).
  - `historyForANonMemberIsForbidden` → `AccessDeniedException`; `historyForAMemberReturnsMessagesInIdOrder` delegates to `findByGroupIdOrderByIdAsc(7L)`.
  - In `MessageTest`: `aGroupSystemMessageIsFlaggedAndHasNoRecipient`.
- [ ] **Step 2: Run** `cd backend && ./mvnw test -Dtest='GroupServiceTest,MessageTest'` — expected: compilation failure (missing methods).
- [ ] **Step 3: Implement** the interfaces above. `GroupController` must keep compiling (it only uses `createGroup`, `findByUserId`, `findByGroupName`).
- [ ] **Step 4: Run** `./mvnw test` — expected: all tests pass.
- [ ] **Step 5: Add `.superpowers/` to `.gitignore`. Commit** the spec, this plan, and Task 1 files: `git commit -m "Group chat: creator tracking, membership checks, add-member and history in GroupService"`.

### Task 2: UserPusher and group REST endpoints

**Files:**
- Create: `backend/src/main/java/com/justchat/websocket/UserPusher.java`
- Modify: `backend/src/main/java/com/justchat/dto/WsEnvelope.java`, `controller/GroupController.java`
- Test: create `backend/src/test/java/com/justchat/websocket/UserPusherTest.java`, `backend/src/test/java/com/justchat/controller/GroupControllerTest.java`

**Interfaces:**
- Consumes: Task 1's `GroupService` methods, `AddMemberResult`, `GroupDetailDto`.
- Produces:
  - `WsEnvelope.fromMessage(Message m): WsEnvelope` — type `MESSAGE`, fields `id, senderId, recipientId, groupId, content, status, sentAt` plus new `public boolean system`. Replaces `ChatWebSocketHandler.toMessageEnvelope` (Task 3 switches the handler over).
  - `WsEnvelope.groupAdded(Long groupId): WsEnvelope` — type `GROUP_ADDED`. Add `GROUP_ADDED { type, groupId }` to the class doc.
  - `@Component UserPusher(ConnectionRegistry, ObjectMapper, ChatMetrics)` with `void pushToUser(Long userId, WsEnvelope env)` and `void pushToUsers(Collection<Long> userIds, WsEnvelope env)`: serialize once, send to every session from `registry.getSessions(userId)`, on `IOException | SessionLimitExceededException` log, `metrics.pushFailed()`, `registry.unregister(userId, session)` and continue.
  - REST (`GroupController`, constructor adds `UserPusher`):
    - `POST /api/groups` → `GroupDetailDto` (creator as only member).
    - `GET /api/groups` → `List<GroupResponseDto>` (unchanged apart from `createdBy`).
    - `GET /api/groups/{id}` → `GroupDetailDto`.
    - `POST /api/groups/{id}/members` body `{ "username": "..." }` → `GroupDetailDto`; after success: `pusher.pushToUsers(memberIds, WsEnvelope.fromMessage(systemMessage))` then `pusher.pushToUser(addedId, WsEnvelope.groupAdded(groupId))`.
    - `GET /api/groups/{id}/messages` → `List<MessageDto>`.
    - Exception mapping (an `@ExceptionHandler` set in `GroupController`): `NoSuchElementException` → 404, `AccessDeniedException` → 403, `IllegalStateException` → 409, blank username → 400 `"username is required"`; body `{ "error": message }`.

- [ ] **Step 1: Write failing tests.**
  - `UserPusherTest`: `pushesToEverySessionOfEachUser`; `aFailingSessionIsEvictedAndTheOthersStillReceive` (asserts `registry.unregister(2L, dead)` and healthy session got one frame; `justchat.ws.push.failures` == 1.0 via `SimpleMeterRegistry`).
  - `GroupControllerTest` (standaloneSetup, auth "alice" id 1 as in `UserControllerTest`): `addMemberReturnsTheGroupAndPushesSystemMessageAndGroupAdded` (verifies `pushToUsers` with ids [1,2] and an envelope of type `MESSAGE` with `system == true`, then `pushToUser(2L, …)` of type `GROUP_ADDED`); `addMemberByNonCreatorIs403`; `addMemberUnknownUserIs404WithError` (`$.error == "No user named zed"`); `addMemberDuplicateIs409`; `blankUsernameIs400`; `detailForNonMemberIs403`; `historyReturnsMessagesWithGroupIdAndSystemFlag`.
- [ ] **Step 2: Run** `./mvnw test -Dtest='UserPusherTest,GroupControllerTest'` — expected: compilation failure.
- [ ] **Step 3: Implement** `UserPusher`, `WsEnvelope.fromMessage` / `groupAdded`, and the controller endpoints and exception handlers.
- [ ] **Step 4: Run** `./mvnw test` — expected: all pass.
- [ ] **Step 5: Commit** `git commit -m "Group chat: add-member, detail and history endpoints; UserPusher for server pushes"`.

### Task 3: Membership-checked group send over WebSocket

**Files:**
- Modify: `backend/src/main/java/com/justchat/websocket/ChatWebSocketHandler.java`
- Test: `backend/src/test/java/com/justchat/websocket/ChatWebSocketHandlerTest.java`

**Interfaces:**
- Consumes: `GroupService.isMember`, `GroupService.memberIds`, `UserPusher.pushToUsers`, `WsEnvelope.fromMessage`.
- Produces: handler constructor becomes `(ConnectionRegistry, MessageService, UserRepository, GroupService, UserPusher, ObjectMapper, ChatMetrics)` — `GroupMemberRepository` dependency removed. 1:1 paths keep their current behaviour; they may use `WsEnvelope.fromMessage` and `UserPusher` where it is a drop-in.

- [ ] **Step 1: Write failing tests** (update `setUp` for the new constructor; replace the two existing group tests' `groupMemberRepository` stubs with `groupService.memberIds(7L)` / `isMember` stubs):
  - `sendingToAGroupYouAreNotInIsRejectedWithoutPersisting` — `isMember(7L, 1L) == false` → sender gets one `ERROR` with `"Not a member of this group"`; `persistNewGroupMessage` never called; `pusher` never called.
  - `sendingBlankContentToAGroupIsRejected` → `ERROR`, nothing persisted.
  - `sendingToAGroupEchoesToSenderAndPushesToOtherMembersOnly` — `pushToUsers` called with `[2L, 3L]` (sender 1 excluded); echo carries `clientMsgId` and `groupId`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=ChatWebSocketHandlerTest` — expected: failures/compile errors.
- [ ] **Step 3: Implement** in `handleGroupSend`: validate content, check `isMember` before persisting, echo via `sendToSession`, fan out with `pusher.pushToUsers(otherMemberIds, WsEnvelope.fromMessage(saved))`.
- [ ] **Step 4: Run** `./mvnw test` — expected: all pass.
- [ ] **Step 5: Commit** `git commit -m "Group chat: reject non-members and blank content on group send"`.

### Task 4: Frontend data layer (types, API, socket hook)

**Files:**
- Modify: `frontend/src/types.ts`, `frontend/src/api/api.ts`, `frontend/src/hooks/useChatSocket.ts`

**Interfaces:**
- Consumes: Task 2's REST shapes and `GROUP_ADDED` frame.
- Produces:
  - Types: `Group { id: number; groupName: string; createdBy: number | null }`; `GroupDetail extends Group { members: User[] }`; `ChatMessage` gains `groupId?: number | null; system?: boolean`; `WsEnvelope.type` gains `'GROUP_ADDED'`, fields `groupId?`, `system?`.
  - `api.listGroups(): Promise<Group[]>`, `api.createGroup(groupName): Promise<GroupDetail>`, `api.groupDetail(id): Promise<GroupDetail>`, `api.addGroupMember(id, username): Promise<GroupDetail>`, `api.groupMessages(id): Promise<ChatMessage[]>`.
  - `useChatSocket(currentUser, selectedUserId, selectedGroupId: number | null = null)` additionally returns:
    - `groupConversations: Map<number, ChatMessage[]>`
    - `groupUnreadCounts: Map<number, number>` (bumped for a group message from someone else when that group isn't selected; cleared on select)
    - `sendGroupMessage(groupId: number, content: string): void` (optimistic + `clientMsgId`, no-op when socket not open)
    - `mergeGroupHistory(groupId: number, history: ChatMessage[]): void` — result = history, plus any in-memory messages whose id isn't in history (live arrivals and still-pending optimistic sends), sorted by id with pending ones last.
    - `groupsVersion: number` — incremented on each `GROUP_ADDED`.
    - `connectionEpoch: number` — incremented on every successful `onopen` (first connect = 1).
  - `MESSAGE` frames with `groupId != null` go only to `groupConversations` (keyed by `groupId`, regardless of whether the group is known); never to `conversations` or `unreadCounts`.

- [ ] **Step 1: Implement** the above. The read-receipt effect stays on `conversations` only.
- [ ] **Step 2: Run** `cd frontend && npm run build` — expected: `✓ built`, no type errors.
- [ ] **Step 3: Commit** `git commit -m "Group chat: frontend types, API and per-group socket state"`.

### Task 5: Frontend UI (tabs, group list, group chat view)

**Files:**
- Create: `frontend/src/components/Sidebar.tsx`, `frontend/src/components/GroupList.tsx`, `frontend/src/components/GroupChatWindow.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/components/UserList.tsx` (drop its own `height: 100vh`/fixed width so it fits inside `Sidebar`)

**Interfaces:**
- Consumes: Task 4's hook return values and `api` functions.
- Produces:
  - `App` selection state: `type Selection = { kind: 'user'; user: User } | { kind: 'group'; group: Group } | null`.
  - `Sidebar({ tab, onTab, peopleUnread, groupsUnread, children })` — tab bar "People" | "Groups", each label with a red total badge when > 0 (same style as `UserList` badges); width 220.
  - `GroupList({ groups, selectedGroupId, unreadCounts, onSelect, onCreated })` — "+ New group" toggles an inline name input (Enter/Create submits `api.createGroup`, Esc cancels, error shown inline); then the groups, `# name` with badges.
  - `GroupChatWindow({ currentUser, detail, messages, onSend, onDetailChange })` — header: `# groupName` and `· member, member…`; if `currentUser.id === detail.createdBy`, an "Add member by username" input + Add button calling `api.addGroupMember`, showing the server's `error` text in a red line below and clearing on success. Body: system messages as a centred grey 11px line; others' bubbles show the sender's username above the text in `SENDER_COLORS[senderId % SENDER_COLORS.length]` (`['#3366cc', '#a0522d', '#2e7d32', '#8e24aa', '#c62828', '#00838f']`); own bubbles as in `ChatWindow` with no status line. Composer like `ChatWindow`.
  - App effects: load `api.listGroups()` on login, on `groupsVersion` change, and on `connectionEpoch` change; when a group is selected or `connectionEpoch` changes, load `groupDetail` + `groupMessages` → `mergeGroupHistory`; when the open group's count of `system` messages increases, reload `groupDetail`.

- [ ] **Step 1: Implement** the components and `App.tsx` wiring.
- [ ] **Step 2: Run** `npm run build` — expected: `✓ built`.
- [ ] **Step 3: Commit** `git commit -m "Group chat: People/Groups tabs, group list and group chat view"`.

### Task 6: End-to-end verification and docs

**Files:**
- Modify: `README.md` (feature list / known limitations for groups), `backend/src/main/java/com/justchat/dto/WsEnvelope.java` doc if not already complete
- Scratch (not committed): an e2e script like the read-receipts one

- [ ] **Step 1: Run** `cd backend && ./mvnw test` and `cd frontend && npm run build` — expected: all green.
- [ ] **Step 2: E2E** against a backend on `--server.port=8090` with three users (creator A, member B, outsider C) over real WebSockets: A creates group; A adds `" B "` → 200 and B receives `GROUP_ADDED` + system `MESSAGE`; B adding C → 403; A adding B again → 409; A adding `zed` → 404; A sends → B receives with `groupId`, C receives nothing; C's hand-crafted `SEND {groupId}` → `ERROR` and no row; C `GET /api/groups/{id}/messages` → 403; B history contains the system line and A's message in id order; 1:1 A→B still goes Delivered → Read. Expected: all checks pass; delete created rows afterwards.
- [ ] **Step 3: Rebuild** `docker compose up --build -d backend frontend` and walk through in two browsers (creator and member): tabs, create, add, live chat, badges on both tabs, reload shows history.
- [ ] **Step 4: Commit** `git commit -m "Group chat: docs"`.
