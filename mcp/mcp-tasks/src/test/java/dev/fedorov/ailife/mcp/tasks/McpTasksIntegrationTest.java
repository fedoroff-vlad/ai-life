package dev.fedorov.ailife.mcp.tasks;

import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.ListTasksInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.TaskProjectDto;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import dev.fedorov.ailife.contracts.tasks.UpsertProjectInput;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.mcp.tasks.review.ReviewService;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions
 * scope on per-test households/projects to stay deterministic (mirrors mcp-finance).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpTasksIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static UUID householdId;
    static UUID otherHouseholdId;

    @Autowired TasksMcpTools tools;
    @Autowired ReviewService reviewService;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
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

    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }
}
