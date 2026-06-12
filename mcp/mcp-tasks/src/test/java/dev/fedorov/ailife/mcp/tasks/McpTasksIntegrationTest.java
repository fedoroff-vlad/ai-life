package dev.fedorov.ailife.mcp.tasks;

import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.contracts.tasks.ListTasksInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.TaskProjectDto;
import dev.fedorov.ailife.contracts.tasks.UpsertProjectInput;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Autowired JdbcTemplate jdbc;

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }
}
