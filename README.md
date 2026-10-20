# JustChat - real-time 1:1 and group chat (MVP)

A real-time 1:1 chat application. The delivery protocol(`routing, delivery status, offline queuing, reconnect replay`) is custom-built on a raw WebSocket handler.

## Key Features

- **Account management** - register / login with username + BCrypt-hashed password, session-cookie auth (`AuthController`)
- **User search** - `GET /api/users?q=` looks up other registered users by username (`UserController`)
- **Chat interface** - React chat window per selected user (`ChatWindow.tsx`), with optimistic send + server reconciliation
- **Chat history** - REST-fetched thread (`GET /api/messages/thread/{otherUserId}`) seeds each conversation when it's opened
- **Delivery status and read receipts** - per-message `✓ Sent` / `✓✓ Delivered` / `✓✓ Read` on 1:1 messages (`MessageStatus`), pushed live over the socket. A message counts as read only while the recipient has that conversation open in a visible, focused window
- **Offline delivery + backlog replay** - messages persist even if the recipient is offline, undelivered messages replay on reconnect, terminated by a `BACKLOG_DONE` frame
- **Unread message indicator** - per-user badge in the user list counting messages received while that thread wasn't open (`useChatSocket`), clearing when the thread is selected
- **Group chat** - a People | Groups sidebar. Anyone can create a group; only its creator adds members, by username (`GroupController`, `GroupService`). Members see the group's full history, messages fan out live to every member's open sessions, and adding someone posts an "alice added bob" line. Only members can read or post - checked on both REST and the WebSocket. Group messages have no delivered/read status

## Architecture & Tech Stack

- **Frontend** - React 18 + TypeScript, built with Vite.
- **Backend** - Spring Boot 3.5 on Java 25. Real-time delivery runs over a raw `TextWebSocketHandler` (`ChatWebSocketHandler`), registered via `@EnableWebSocket`. Auth is Spring Security with
session-cookie login (not JWT). Persistence is Spring Data JPA.
- **Database** - PostgreSQL, two tables (`users`, `messages`). 
Conversation thread is derived from the `(senderId, recipientId)` pair.
`ddl-auto: update` is used instead of migrations (Flyway/Liquibase).
- **Infra** - nginx in front in Docker, reverse-proxying `/api` and `/ws` to the backend 
so both stay same-origin with the frontend (cookie auth without CORS).

## Prerequisites & Setup

Docker (and Docker Compose) to run the full stack. To run pieces individually: Java 25 and
a Postgres instance for the backend (there's no `mvnw` checked in - see Usage Guide below), and Node.js for the frontend.

```
git clone <repo-url>
cd justchat
docker compose up --build
```

Open `http://localhost:5173` in two browser sessions (e.g. one incognito), register two
different users, search for each other, and message back and forth.

## Usage Guide

|Task                                                                                                    | Description                                                                          |
|-----------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| `docker compose up --build`                                                                              | Runs Postgres + backend + frontend behind nginx - the whole app                       |
| `docker compose up postgres`                                                                             | Starts only Postgres, for running the backend/frontend locally against it             |
| `./mvnw spring-boot:run` (from `backend/`)                                                                | Runs the backend against a Postgres on `localhost:5432`                               |
| `./mvnw test` (from `backend/`)                                                                          | Runs backend unit tests                                                               |
| `./mvnw test -Dtest=ClassName` (from `backend/`)                                                          | Runs a single backend test class                                                      |
| `./mvnw package` (from `backend/`)                                                                       | Builds the backend jar                                                                |
| `npm install && npm run dev` (from `frontend/`)                                                           | Vite dev server on `:5173`, proxies `/api` and `/ws` to `localhost:8080`               |
| `npm run build` (from `frontend/`)                                                                       | Type-checks (`tsc -b`) and builds the frontend                                        |
| `npm run preview` (from `frontend/`)                                                                     | Serves the production build locally                                                   |

## Project Rules & Conventions

- Backend packages are organized by technical concern (`controller`, `service`,
  `repository`, `model`, `websocket`, `config`, `dto`).
- There's no lint script and no frontend test setup - time went into the messaging path
  instead (see Testing decisions below).

## MVP

| Feature                    | Description                                                                     |
|----------------------------|----------------------------------------------------------------------------------|
| Chat message communication | User -> user real-time messaging across different browser sessions               |
| Chat history                | Full thread between two users loads from Postgres on selecting them             |
| Account management          | Register/login with username + password, session-cookie auth                    |
| Chat interface              | Chat window per selected user, with user search/list to start a conversation     |
| Delivery status             | Per-message sent/delivered ticks, updated live when the recipient is connected  |

## Assumptions

The following scope decisions define the current boundaries of the product and is worth revisiting as requirements evolve:
1. Chats are 1:1 or groups. Groups can't be renamed, deleted or left, and members can't be removed; only the creator adds members.
2. Users register themselves (username + password) and can search for any other registered user.
3. Messages are not editable or deletable, every sent message is persisted permanently.
4. The user is not able to see the typing status of the other user.
5. Timestamps are stored in UTC and rendered using the browser's local time/locale.
6. User A can send a message to User B even if User B is offline - it's persisted immediately and delivered/replayed once B reconnects.
7. The platform is used in a webpage only.
8. Delivery is at-least-once, not exactly-once - if a push succeeds on the wire but the client crashes before rendering/acking it, there's no mechanism here to detect that gap.
9. Single backend instance only - `ConnectionRegistry` is an in-memory map inside one JVM, and message ordering relies on that too.
10. Unread counts (people and groups) are session-only, tracked purely client-side in `useChatSocket` - they reset on page refresh/re-login.
11. Group messages are not queued for offline members over the socket. A member who was offline sees everything when they open the group (REST history), and the open group's history is re-fetched after a reconnect.

## Testing decisions

1. Backend tests are plain Mockito unit tests - no Spring context, no test DB/Testcontainers. `MessageServiceTest`/`AuthServiceTest`/etc. mock the repository layer directly.
2. There's no frontend test setup and no lint script in this MVP - time went into the messaging path instead.

## Technical Decisions

| Decision                                              | Reason                                                                                                                                                                                                                     |
|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Language - Java 25                                     | Long-term-support JVM language with a mature ecosystem and tooling.                                          |
| Framework - Spring Boot 3.5                             | Batteries-included (web, security, JPA, WebSocket) with one place to wire config (`@Configuration` classes). |
| One socket per browser tab, not per conversation        | The client opens a single `/ws` connection per tab. `App.tsx` just filters `useChatSocket`'s per-user message map to render whichever conversation is selected. Switching conversations is a UI filter, not a reconnect.  |
| Frontend - React 18 + TypeScript, no UI/state library   | No Redux - state fits in a couple of hooks (`useChatSocket`, plus `currentUser`/`selectedUser` in `App.tsx`).       |
| Database - PostgreSQL + Spring Data JPA                 | Messages are relational (`sender_id`/`recipient_id` FKs) and thread queries are ordinary SQL via `MessageRepository`.|
| Package structure - layered by technical concern        | Top-level packages by role (`controller`, `service`, `repository`, `model`, `websocket`, `config`, `dto`). |
| Authentication - session cookie                | `AuthController` calls `AuthenticationManager` directly (not Spring's `formLogin` filter) with username + BCrypt-hashed password, cookie-friendly single-page app. |
| CSRF - disabled                                         | Same-origin cookie API, no third-party form posts - see the comment in `SecurityConfig` for when this decision would need revisiting.|
| Connection registry - in-memory `ConcurrentHashMap` + `CopyOnWriteArraySet` | Maps `userId -> Set<WebSocketSession>`, so one user can have multiple tabs/devices open, all receiving pushes. No explicit locking, since handler callbacks fire concurrently per-session on the servlet container's own threads. |
| Send flow - persist before push                         | A message is written to Postgres (status `SENT`) before any socket push, so the DB is always the source of truth even if the live push never happens (recipient offline, or a push fails).                              |
| Reconnect/backlog replay reuses the offline-delivery code path | The server doesn't distinguish "recipient was offline when sent" from "client reconnected after a drop" - both are just "messages addressed to me that never reached `DELIVERED`."                                       |
| Message ordering - single auto-increment PK              | `Message.id` is relied on as the conversation ordering key; this only holds because inserts are serialized through one backend instance.              |
| Dev/prod request routing - reverse proxy                 | `vite.config.ts` (dev) and `frontend/nginx.conf` (Docker) both forward `/api` and `/ws` to the backend, keeping them same-origin with the frontend so cookie auth works without CORS.                                     |

## Future considerations

| Feature                              | Description                                                                                                     |
|----------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Group management                      | Rename/delete groups, leave a group, remove members, more than one admin                                         |
| Typing indicators                     | Show typing status when the other user is actively composing a message                                          |
| Message pagination                    | Load conversation history in pages instead of the full thread on open                                            |
| Message editing/deletion              | Let a user edit or delete a message they sent                                                                    |
| Persistent unread counts               | Derive badges from server-side read state so they survive a page reload instead of resetting each session         |
