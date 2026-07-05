package dev.fedorov.ailife.mcp.tasks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.LinkTaskToEventInput;
import dev.fedorov.ailife.contracts.tasks.ListTasksInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.TaskProjectDto;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import dev.fedorov.ailife.contracts.tasks.UpsertProjectInput;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.mcp.tasks.review.ReviewService;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions
 * scope on per-test households/projects to stay deterministic (mirrors mcp-finance).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpTasksIntegrationTest extends AbstractPostgresIntegrationTest {


    // Started in a static initializer so the port is known when Spring resolves the
    // @DynamicPropertySource supplier during context refresh (runs BEFORE @BeforeAll).
    static final MockWebServer scheduler;
    static final SchedulerDispatcher schedulerDispatcher = new SchedulerDispatcher();
    static {
        scheduler = new MockWebServer();
        scheduler.setDispatcher(schedulerDispatcher);
        try {
            scheduler.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stopScheduler() throws Exception {
        scheduler.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);        registry.add("mcp-tasks.scheduler-url",
                () -> "http://localhost:" + scheduler.getPort());
    }

    @BeforeEach
    void resetScheduler() throws Exception {
        // Context (and recorded requests) are reused across methods — drain leftovers
        // and reset the stub so each weekly-review test starts clean.
        while (scheduler.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
        schedulerDispatcher.reset();
    }

    static UUID householdId;
    static UUID otherHouseholdId;

    @Autowired TasksMcpTools tools;
    @Autowired ReviewService reviewService;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        householdId = UUID.randomUUID();
        otherHouseholdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                otherHouseholdId, "other household");
    }

    @Test
    void upsertProjectCreatesThenUpdatesInPlace() {
        TaskProjectDto created = tools.upsertProject(new UpsertProjectInput(
                null, householdId, null, "Plan vacation", null, "summer"));
        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo("active"); // default applied

        TaskProjectDto updated = tools.upsertProject(new UpsertProjectInput(
                created.id(), householdId, null, "Plan vacation (2026)", "someday", "later"));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.name()).isEqualTo("Plan vacation (2026)");
        assertThat(updated.status()).isEqualTo("someday");
        assertThat(updated.note()).isEqualTo("later");
    }

    @Test
    void listProjectsFiltersByStatus() {
        // Own household for determinism — the shared test DB accumulates rows.
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        tools.upsertProject(new UpsertProjectInput(null, h, null, "Active one", "active", null));
        tools.upsertProject(new UpsertProjectInput(null, h, null, "Someday one", "someday", null));

        assertThat(tools.listProjects(h, null)).hasSize(2);
        assertThat(tools.listProjects(h, "someday"))
                .singleElement()
                .satisfies(p -> assertThat(p.name()).isEqualTo("Someday one"));
    }

    @Test
    void addTaskLandsInInboxWithDefaults() {
        TaskItemDto t = tools.addTask(new AddTaskInput(
                householdId, null, "купить молоко", null, null));
        assertThat(t.id()).isNotNull();
        assertThat(t.status()).isEqualTo("inbox");
        assertThat(t.source()).isEqualTo("manual");
        assertThat(t.projectId()).isNull();
        assertThat(t.context()).isNull();
        assertThat(t.completedAt()).isNull();
    }

    @Test
    void listTasksFiltersByStatusAndProject() {
        // add_task always lands in inbox; non-inbox states need clarify_task (next PR), so this
        // slice asserts the status/project filters against fresh inbox captures.
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TaskProjectDto proj = tools.upsertProject(new UpsertProjectInput(
                null, h, null, "Errands", null, null));
        tools.addTask(new AddTaskInput(h, null, "inbox item", null, null));

        List<TaskItemDto> inbox = tools.listTasks(new ListTasksInput(
                h, "inbox", null, null, null, null));
        assertThat(inbox).singleElement()
                .satisfies(t -> assertThat(t.title()).isEqualTo("inbox item"));

        // status filter that matches nothing (no clarified items yet)
        assertThat(tools.listTasks(new ListTasksInput(h, "next", null, null, null, null)))
                .isEmpty();

        // project filter on a project with no items yet
        assertThat(tools.listTasks(new ListTasksInput(h, null, null, proj.id(), null, null)))
                .isEmpty();
    }

    @Test
    void listTasksScopedToHouseholdNoCrossLeak() {
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        seedHousehold(h1);
        seedHousehold(h2);
        tools.addTask(new AddTaskInput(h1, null, "h1 task", null, null));
        tools.addTask(new AddTaskInput(h2, null, "h2 task", null, null));

        assertThat(tools.listTasks(new ListTasksInput(h1, null, null, null, null, null)))
                .singleElement()
                .satisfies(t -> assertThat(t.title()).isEqualTo("h1 task"));
    }

    @Test
    void clarifyTaskOrganizesInboxItemAndIsFilterableByNewState() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TaskProjectDto proj = tools.upsertProject(new UpsertProjectInput(
                null, h, null, "Home", null, null));
        TaskItemDto captured = tools.addTask(new AddTaskInput(h, null, "fix the sink", null, null));

        Instant due = Instant.now().plus(2, ChronoUnit.DAYS);
        TaskItemDto clarified = tools.clarifyTask(new ClarifyTaskInput(
                captured.id(), "next", "@home", proj.id(), due, null, 1));
        assertThat(clarified.status()).isEqualTo("next");
        assertThat(clarified.context()).isEqualTo("@home");
        assertThat(clarified.projectId()).isEqualTo(proj.id());
        assertThat(clarified.priority()).isEqualTo(1);
        assertThat(clarified.completedAt()).isNull();

        // Now filterable by its organized state, no longer in inbox.
        assertThat(tools.listTasks(new ListTasksInput(h, "next", "@home", proj.id(), null, null)))
                .singleElement().satisfies(t -> assertThat(t.title()).isEqualTo("fix the sink"));
        assertThat(tools.listTasks(new ListTasksInput(h, "inbox", null, null, null, null))).isEmpty();
    }

    @Test
    void clarifyRejectsBadStatusAndCrossHouseholdProject() {
        TaskItemDto t = tools.addTask(new AddTaskInput(householdId, null, "thing", null, null));
        assertThatThrownBy(() -> tools.clarifyTask(new ClarifyTaskInput(
                t.id(), "bogus", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported status");

        // A project from another household must be rejected.
        TaskProjectDto foreign = tools.upsertProject(new UpsertProjectInput(
                null, otherHouseholdId, null, "Foreign", null, null));
        assertThatThrownBy(() -> tools.clarifyTask(new ClarifyTaskInput(
                t.id(), "next", null, foreign.id(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to household");
    }

    @Test
    void completeTaskStampsCompletedAtThenClarifyAwayClearsIt() {
        TaskItemDto t = tools.addTask(new AddTaskInput(householdId, null, "ship it", null, null));
        TaskItemDto done = tools.completeTask(t.id());
        assertThat(done.status()).isEqualTo("done");
        assertThat(done.completedAt()).isNotNull();

        // Re-opening via clarify clears the completion stamp.
        TaskItemDto reopened = tools.clarifyTask(new ClarifyTaskInput(
                t.id(), "next", null, null, null, null, null));
        assertThat(reopened.status()).isEqualTo("next");
        assertThat(reopened.completedAt()).isNull();
    }

    @Test
    void updateTaskAppliesNonNullFieldsOnly() {
        TaskItemDto t = tools.addTask(new AddTaskInput(householdId, null, "draft", null, "telegram"));
        TaskItemDto updated = tools.updateTask(new UpdateTaskInput(
                t.id(), "final title", "a note", "@work", null, 2, null, null));
        assertThat(updated.title()).isEqualTo("final title");
        assertThat(updated.note()).isEqualTo("a note");
        assertThat(updated.context()).isEqualTo("@work");
        assertThat(updated.priority()).isEqualTo(2);
        // Untouched immutables / fields stay.
        assertThat(updated.source()).isEqualTo("telegram");
        assertThat(updated.status()).isEqualTo("inbox"); // update doesn't move status
    }

    @Test
    void deleteTaskReturnsRowAndThrowsOnUnknown() {
        TaskItemDto t = tools.addTask(new AddTaskInput(householdId, null, "temp", null, null));
        TaskItemDto deleted = tools.deleteTask(t.id());
        assertThat(deleted.id()).isEqualTo(t.id());
        assertThatThrownBy(() -> tools.deleteTask(t.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    @Test
    void linkTaskToEventStoresUid() {
        TaskItemDto t = tools.addTask(new AddTaskInput(householdId, null, "call dentist", null, null));
        TaskItemDto linked = tools.linkTaskToEvent(t.id(), "evt-uid-123");
        assertThat(linked.calendarEventUid()).isEqualTo("evt-uid-123");
    }

    @Test
    void weeklyReviewAggregatesInboxWaitingAndStuckProjects() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        // Two inbox captures (left un-clarified).
        tools.addTask(new AddTaskInput(h, null, "inbox A", null, null));
        tools.addTask(new AddTaskInput(h, null, "inbox B", null, null));

        // One delegated waiting-for.
        TaskItemDto delegated = tools.addTask(new AddTaskInput(h, null, "wait on Bob", null, null));
        tools.clarifyTask(new ClarifyTaskInput(delegated.id(), "waiting", null, null, null, null, null));

        // A stuck project: active, no next-action.
        TaskProjectDto stuck = tools.upsertProject(new UpsertProjectInput(null, h, null, "Stuck proj", null, null));
        // A healthy project: active, has a next-action.
        TaskProjectDto healthy = tools.upsertProject(new UpsertProjectInput(null, h, null, "Healthy proj", null, null));
        TaskItemDto step = tools.addTask(new AddTaskInput(h, null, "do the thing", null, null));
        tools.clarifyTask(new ClarifyTaskInput(step.id(), "next", "@home", healthy.id(), null, null, null));

        WeeklyReviewResult r = reviewService.review(h);
        assertThat(r.inboxCount()).isEqualTo(2);
        assertThat(r.waitingCount()).isEqualTo(1);
        assertThat(r.inbox()).extracting(TaskItemDto::title).containsExactlyInAnyOrder("inbox A", "inbox B");
        assertThat(r.waiting()).singleElement().satisfies(t -> assertThat(t.title()).isEqualTo("wait on Bob"));
        // Only the project without a next-action is "stuck".
        assertThat(r.stuckProjectCount()).isEqualTo(1);
        assertThat(r.stuckProjects()).singleElement()
                .satisfies(p -> assertThat(p.name()).isEqualTo("Stuck proj"));
    }

    @Test
    void internalReviewEndpointReturnsAggregate() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        tools.addTask(new AddTaskInput(h, null, "endpoint inbox", null, null));

        org.springframework.test.web.reactive.server.WebTestClient client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port).build();

        WeeklyReviewResult r = client.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/review")
                        .queryParam("householdId", h).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WeeklyReviewResult.class)
                .returnResult().getResponseBody();

        assertThat(r).isNotNull();
        assertThat(r.householdId()).isEqualTo(h);
        assertThat(r.inboxCount()).isEqualTo(1);
        assertThat(r.inbox()).singleElement().satisfies(t -> assertThat(t.title()).isEqualTo("endpoint inbox"));
    }

    @Test
    void internalLinkEventStoresUidAnd400OnUnknown() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TaskItemDto t = tools.addTask(new AddTaskInput(h, null, "pay rent", null, null));

        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer().baseUrl("http://localhost:" + port).build();

        TaskItemDto linked = client.post().uri("/internal/link-event")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new LinkTaskToEventInput(t.id(), "evt-xyz"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskItemDto.class)
                .returnResult().getResponseBody();
        assertThat(linked).isNotNull();
        assertThat(linked.calendarEventUid()).isEqualTo("evt-xyz");

        client.post().uri("/internal/link-event")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new LinkTaskToEventInput(UUID.randomUUID(), "evt-1"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void enableWeeklyReviewRegistersWhenNoneExists() throws Exception {
        UUID h = UUID.randomUUID();
        ScheduleDto created = tools.enableWeeklyReview(h, null);
        assertThat(created).isNotNull();
        assertThat(created.kind()).isEqualTo("weekly.review");

        // First the list lookup, then the create — no delete (nothing to replace).
        RecordedRequest list = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(list.getMethod()).isEqualTo("GET");
        assertThat(list.getPath()).startsWith("/v1/schedules");

        RecordedRequest post = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getPath()).isEqualTo("/v1/schedules");
        JsonNode body = MAPPER.readTree(post.getBody().readUtf8());
        assertThat(body.get("householdId").asText()).isEqualTo(h.toString());
        assertThat(body.get("ownerAgent").asText()).isEqualTo("tasks");
        assertThat(body.get("kind").asText()).isEqualTo("weekly.review");
        assertThat(body.get("cron").asText()).isEqualTo("0 0 9 * * MON"); // default
        assertThat(body.hasNonNull("runAt")).isFalse();

        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void enableWeeklyReviewIsNoOpWhenSameCronAlreadyActive() throws Exception {
        UUID h = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        schedulerDispatcher.existing = new ScheduleDto(existingId, h, "tasks", "weekly.review",
                "0 0 9 * * MON", null, true, Instant.now(), null, Instant.now());

        ScheduleDto result = tools.enableWeeklyReview(h, null);
        assertThat(result.id()).isEqualTo(existingId);

        // Only the list lookup — no create, no delete.
        assertThat(scheduler.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo("GET");
        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void enableWeeklyReviewReplacesScheduleWhenCronDiffers() throws Exception {
        UUID h = UUID.randomUUID();
        UUID oldId = UUID.randomUUID();
        schedulerDispatcher.existing = new ScheduleDto(oldId, h, "tasks", "weekly.review",
                "0 0 9 * * MON", null, true, Instant.now(), null, Instant.now());

        ScheduleDto result = tools.enableWeeklyReview(h, "0 0 18 * * FRI");
        assertThat(result).isNotNull();

        assertThat(scheduler.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo("GET");
        // Register-first ordering: new POST, then delete the old one.
        RecordedRequest post = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(MAPPER.readTree(post.getBody().readUtf8()).get("cron").asText())
                .isEqualTo("0 0 18 * * FRI");
        RecordedRequest delete = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(delete.getMethod()).isEqualTo("DELETE");
        assertThat(delete.getPath()).isEqualTo("/v1/schedules/" + oldId);
    }

    @Test
    void enableWeeklyReviewRejectsInvalidCron() {
        assertThatThrownBy(() -> tools.enableWeeklyReview(UUID.randomUUID(), "not a cron"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron");
    }

    @Test
    void disableWeeklyReviewDeletesExistingThenNoOpWhenAbsent() throws Exception {
        UUID h = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        schedulerDispatcher.existing = new ScheduleDto(existingId, h, "tasks", "weekly.review",
                "0 0 9 * * MON", null, true, Instant.now(), null, Instant.now());

        ScheduleDto removed = tools.disableWeeklyReview(h);
        assertThat(removed.id()).isEqualTo(existingId);
        assertThat(scheduler.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo("GET");
        RecordedRequest delete = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(delete.getMethod()).isEqualTo("DELETE");
        assertThat(delete.getPath()).isEqualTo("/v1/schedules/" + existingId);

        // Nothing active now → disable is a no-op (only the list lookup, no delete).
        schedulerDispatcher.existing = null;
        assertThat(tools.disableWeeklyReview(h)).isNull();
        assertThat(scheduler.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo("GET");
        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void internalTasksEndpointReturnsFilteredNextActions() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        // One inbox capture clarified to a next-action, plus one left in inbox.
        TaskItemDto toClarify = tools.addTask(new AddTaskInput(h, null, "позвонить врачу", null, null));
        tools.clarifyTask(new ClarifyTaskInput(toClarify.id(), "next", "@calls", null, null, null, null));
        tools.addTask(new AddTaskInput(h, null, "still in inbox", null, null));

        org.springframework.test.web.reactive.server.WebTestClient client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port).build();

        List<TaskItemDto> nextActions = client.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/tasks")
                        .queryParam("householdId", h)
                        .queryParam("status", "next")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TaskItemDto.class)
                .returnResult().getResponseBody();

        assertThat(nextActions).singleElement().satisfies(t -> {
            assertThat(t.title()).isEqualTo("позвонить врачу");
            assertThat(t.context()).isEqualTo("@calls");
        });
    }

    @Test
    void internalClarifyEndpointAppliesClarification() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TaskItemDto captured = tools.addTask(new AddTaskInput(h, null, "купить молоко", null, null));

        org.springframework.test.web.reactive.server.WebTestClient client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port).build();

        TaskItemDto clarified = client.post()
                .uri("/internal/clarify")
                .bodyValue(new ClarifyTaskInput(captured.id(), "next", "@errand", null, null, null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskItemDto.class)
                .returnResult().getResponseBody();

        assertThat(clarified).isNotNull();
        assertThat(clarified.status()).isEqualTo("next");
        assertThat(clarified.context()).isEqualTo("@errand");
        // A bad status → 400 (the tool's validation surfaces through the passthrough).
        client.post()
                .uri("/internal/clarify")
                .bodyValue(new ClarifyTaskInput(captured.id(), "bogus", null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * scheduler-service stub: GET /v1/schedules → a JSON array holding {@link #existing}
     * (or empty); POST /v1/schedules → 200 echoing the request's cron as a fresh
     * ScheduleDto; DELETE → 204.
     */
    static final class SchedulerDispatcher extends Dispatcher {
        volatile ScheduleDto existing;

        void reset() {
            existing = null;
        }

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            String base = path.split("\\?", 2)[0];
            try {
                if ("GET".equals(req.getMethod()) && base.equals("/v1/schedules")) {
                    ScheduleDto[] body = existing == null
                            ? new ScheduleDto[0] : new ScheduleDto[]{existing};
                    return json(MAPPER.writeValueAsString(body));
                }
                if ("POST".equals(req.getMethod()) && base.equals("/v1/schedules")) {
                    // Don't read the request body here — the test consumes it via
                    // takeRequest() to assert the create payload (a Buffer is single-read).
                    ScheduleDto created = new ScheduleDto(UUID.randomUUID(), null, "tasks",
                            "weekly.review", "0 0 9 * * MON", null, true,
                            Instant.now(), null, Instant.now());
                    return json(MAPPER.writeValueAsString(created));
                }
                if ("DELETE".equals(req.getMethod()) && base.startsWith("/v1/schedules/")) {
                    return new MockResponse().setResponseCode(204);
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(String body) {
            return new MockResponse().setHeader("content-type", "application/json").setBody(body);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }
}
