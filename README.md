# Klerk REST

Exposes a Klerk application as a REST API with a generated OpenAPI 3.1 description. Errors are RFC 9457 problem
documents, models have `ETag`/`Last-Modified`, and every model lists the events the client may execute on it now.

## Installation

```kotlin
implementation(platform("dev.klerkframework:klerk-bom:$klerkBomVersion"))
implementation("dev.klerkframework:klerk")
implementation("dev.klerkframework:klerk-rest")
```

## Usage

```kotlin
fun Application.module() {
    klerkRest(klerk, contextProvider = { call -> contextFor(call) }, title = "My API", version = "1.0.0")
}
```

`contextProvider` receives every request, so it decides who the client is, e.g. from a Bearer token in the
`Authorization` header. Return `null` to reject the request with `401 Unauthorized`. Klerk's authorization rules apply
to everything the client does. Verifying tokens (e.g. JWT) is up to the application.

The API is served under `basePath` (default `/api/v1`). `klerkRest` can be called several times with different
`basePath`s.

## Endpoints

A model `Book` becomes the collection `books` (`Category` becomes `categories`). Only events with
`EventVisibility.External` get endpoints.

| Request                                    | Does                                                                         |
|--------------------------------------------|------------------------------------------------------------------------------|
| `GET /books`                               | One page of a view. Query: `view` (default `all`), `limit` (1–100, default 20), `cursor`, `referencing` (only models that reference the given ID), `actions` (include `actions`, default false). |
| `GET /books/{id}`                          | The book.                                                                    |
| `POST /books/events/{event}`              | Executes a void event, e.g. `/books/events/create-book`. `201` with `Location`. |
| `POST /books`                              | Same, when the model has exactly one External void event.                    |
| `POST /books/{id}/events/{event}`          | Executes an instance event, e.g. `/books/42/events/publish-book`. `200` with the book. |
| `GET /openapi.json`                        | The OpenAPI document. Needs no authentication.                               |

Event names in URIs are the kebab case of the event name. The body of an event request is a JSON object with the
event's parameters; leave it out for an event without parameters. An event that changes nothing visible, deletes the
model or whose result the client may not read gives `204`.

## Models

```json
{
  "id": "42",
  "href": "/api/v1/books/42",
  "type": "Book",
  "state": "Draft",
  "createdAt": "2026-10-24T10:00:00Z",
  "lastModifiedAt": "2026-10-24T10:05:00Z",
  "props": { "title": "Dune" },
  "actions": [
    { "event": "PublishBook", "method": "POST", "href": "/api/v1/books/42/events/publish-book" },
    {
      "event": "UpdateBook", "method": "POST", "href": "/api/v1/books/42/events/update-book",
      "params": "/api/v1/openapi.json#/components/schemas/BookUpdateBookParams"
    }
  ]
}
```

`actions` are the events the client may execute on the model now. Times are RFC 3339, durations ISO 8601 and positions
ISO 6709. Decimals are strings. A collection page is `{ "items": [...], "nextCursor": "..." }`; when there is a next
page the response also has a `Link` header with `rel="next"`. Models the client may not read are left out of lists and
are `404` when fetched.

## Caching and concurrency

`GET /books/{id}` returns `ETag` and `Last-Modified` and answers `304` to `If-None-Match` and `If-Modified-Since`.
Send the `ETag` as `If-Match` to an instance event to make it fail with `412 Precondition Failed` if the model has
changed in the meantime.

## Idempotency

Send `Idempotency-Key: <unique string>` (1–255 characters, e.g. a UUID) with a `POST` to be able to retry it when the
response was lost. The header is required: a request that executes an event without it gets `400`. Pass
`requireIdempotencyKey = false` to `klerkRest` to make it optional. Only the first successful request with a key is applied; a retry fails with `409` and
`urn:klerk:error:ERROR-COMMAND-5`, and the client can `GET` the model to see the outcome. A request that failed does
not use up the key. Keys are separate per actor and URI, and are forgotten after
`KlerkSettings.commandTokenValidity` (24 hours by default).

## Errors

Errors are `application/problem+json`. `type` is `urn:klerk:error:<code>` (e.g. `urn:klerk:error:ERROR-AUTH-1`), and
`problems` lists every problem with its `code` and `detail`.

| Status | When                                                              |
|--------|-------------------------------------------------------------------|
| 400    | Malformed request, e.g. invalid JSON, a missing property or a missing `Idempotency-Key` |
| 401    | `contextProvider` returned `null`. Has `WWW-Authenticate: Bearer` |
| 403    | The actor may not execute the event                               |
| 404    | No such model, event or view                                      |
| 409    | The event is not possible in the model's state, or the `Idempotency-Key` has been used |
| 412    | `If-Match` does not match                                         |
| 415    | The body is not `application/json`                                |
| 422    | Klerk's validation rejected a value                               |
| 503    | Klerk is not running                                              |
