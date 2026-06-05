# libs/contracts

Pure data records shared between services. Zero business logic — these are the wire
format. Every record is `@JsonInclude(NON_NULL)`. No transitive Spring deps; just
Jackson annotations.

When you add a new endpoint or MCP tool whose request/response crosses module
boundaries, the DTO goes here.

## Layout (by domain)

| Package | Purpose |
|---|---|
| `agent` | `AgentManifest`, `IntentResponse`, `NormalizedMessage`, `Attachment`, `MessageScope` — agent ↔ orchestrator wire format. |
| `calendar` | `CalendarEventDto`, `CreateEventInput`, `UpdateEventInput`, `ListEventsInput`, `SearchEventsInput` (mcp-caldav); `IcsSubscriptionDto`, `AddSubscriptionInput`, `PullCalendarResult` (mcp-ics-import). |
| `llm` | `LlmChatRequest/Response`, `LlmEmbedRequest/Response`, `LlmMessage`, `LlmRole`, `LlmChannel`, `LlmUsage` — llm-gateway wire format. `LlmChannel` is the `default/fast/vision/embedding` discriminator. |
| `notify` | `NotifyRequest` (notifier-service inbound), `InternalSendRequest` (gateway-telegram `/internal/send`). |
| `profile` | `HouseholdDto`, `UserDto`, `PersonDto` — profile-service responses. |
| `schedule` | `CreateScheduleRequest`, `ScheduleDto`, `AgentWakeRequest` — scheduler-service ↔ orchestrator. |

## Conventions
- Records, not classes.
- `@JsonInclude(JsonInclude.Include.NON_NULL)` on each record.
- Computed `toDto()` lives on the entity, not the record.
- No validation annotations here — validation is per-endpoint in the owning service.
