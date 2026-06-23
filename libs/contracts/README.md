# libs/contracts

Pure data records shared between services. Zero business logic — these are the wire
format. Every record is `@JsonInclude(NON_NULL)`. No transitive Spring deps; just
Jackson annotations.

When you add a new endpoint or MCP tool whose request/response crosses module
boundaries, the DTO goes here.

## Layout (by domain)

| Package | Purpose |
|---|---|
| `agent` | `AgentManifest`, `IntentResponse`, `NormalizedMessage`, `Attachment`, `MessageScope` (agent ↔ orchestrator); `AgentActionRequest`/`AgentActionResult` (inter-agent hub `/v1/agents/invoke`); `ResumeRequest` (route-lock resume). |
| `calendar` | `CalendarEventDto`, `CreateEventInput`, `UpdateEventInput`, `ListEventsInput`, `SearchEventsInput` (mcp-caldav); `IcsSubscriptionDto`, `AddSubscriptionInput`, `PullCalendarResult` (mcp-ics-import). |
| `finance` | mcp-finance surface: `FinTransactionDto`/`FinAccountDto`/`FinBudgetDto`/`FinCategoryDto`/`FinRecurringDto` + their inputs; `BalanceResult`, `BudgetStatusResult`, `SpendingByCategoryInput`/`Row`, gift-budget (`GiftBudgetResult`/`GiftBudgetRuleDto`/`SetGiftBudgetRuleInput`), CSV import/export, matview refresh. |
| `tasks` | GTD: `TaskProjectDto`/`TaskItemDto` + inputs (`Add/Clarify/Update/ListTasksInput`, `UpsertProjectInput`), `LinkTaskToEventInput`/`TaskToEventRequest`, `WeeklyReviewResult`. |
| `wardrobe` | stylist: `WardrobeItemDto`/`AddItemInput`/`UpdateItemInput`, `StyleProfileDto`/`SetStyleProfileInput`. |
| `nutrition` | `MealLogDto`/`LogMealInput`, `DietProfileDto`/`SetDietProfileInput`, `BasketDto`/`BasketItem`/`SaveBasketInput`. |
| `basket` | `BasketCapturedEvent` — the grocery-receipt fan-out bus event (finance → nutrition). |
| `media` | mcp-media-processing: `MediaObjectDto`, `OcrResult`, `CaptionInput`/`CaptionResult`, `TranscriptResult`. |
| `web` | mcp-web: `WebSearchInput`/`WebSearchHit`/`WebSearchResult`, `FetchUrlInput`/`PageContent`, `TranscribeInput`/`VideoTranscript`. |
| `market` | mcp-market-data: `QuoteInput`/`Quote`. |
| `imagegen` | mcp-image-gen: `ImageGenInput`/`ImageGenResult`. |
| `memory` | memory-service: `WriteMemoryRequest`/`MemoryDto`, `RecallMemoryRequest`/`RecallMemoryHit`, `CaptureRequest`, relations (`WriteRelationRequest`/`RelationDto`/`PersonRelationsResponse`). |
| `message` | `MessageReceivedEvent` — the memory-from-chat capture bus event. |
| `conversation` | `ConversationStateDto`/`SetConversationStateRequest` — conversation-service route-lock/state. |
| `llm` | `LlmChatRequest/Response`, `LlmEmbedRequest/Response`, `LlmMessage`, `LlmImage`, `LlmRole`, `LlmChannel`, `LlmUsage` — llm-gateway wire format. `LlmChannel` is the `default/fast/vision/embedding` discriminator. |
| `notify` | `NotifyRequest` (notifier inbound), `InternalSendRequest` (gateway-telegram `/internal/send`), `NotifyRequestedEvent` (bus). |
| `profile` | `HouseholdDto`, `UserDto`, `PersonDto` — profile-service responses. |
| `schedule` | `CreateScheduleRequest`, `ScheduleDto`, `AgentWakeRequest`, `ScheduleFiredEvent` — scheduler-service ↔ orchestrator + bus. |

## Conventions
- Records, not classes.
- `@JsonInclude(JsonInclude.Include.NON_NULL)` on each record.
- Computed `toDto()` lives on the entity, not the record.
- No validation annotations here — validation is per-endpoint in the owning service.
