package dev.fedorov.ailife.mcp.inventory.web;

import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.mcp.inventory.tools.InventoryMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for the physical-storage store. inventory-agent (IN-c…IN-g) has already
 * decided what it wants — it hits these deterministic HTTP paths rather than an LLM-driven MCP tool
 * call (the MCP/SSE binding stays for future selection but isn't MockWebServer-testable). Each
 * endpoint delegates straight to {@link InventoryMcpTools} so the tool's scope/validation applies
 * identically. Mirrors mcp-docs' {@code InternalDocumentsController}.
 */
@RestController
@RequestMapping("/internal")
public class InternalInventoryController {

    private final InventoryMcpTools tools;

    public InternalInventoryController(InventoryMcpTools tools) {
        this.tools = tools;
    }

    // ── zones ────────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/zones")
    public ResponseEntity<?> saveZone(@RequestBody SaveZoneInput input) {
        try {
            return ResponseEntity.ok(tools.saveZone(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/zones")
    public List<StorageZoneDto> listZones(@RequestParam UUID householdId) {
        return tools.listZones(householdId);
    }

    // ── containers ───────────────────────────────────────────────────────────────────────────────

    @PostMapping("/containers")
    public ResponseEntity<?> saveContainer(@RequestBody SaveContainerInput input) {
        try {
            return ResponseEntity.ok(tools.saveContainer(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** One container with its zone + contents; 404 when there is no such container. */
    @GetMapping("/containers/{id}")
    public ResponseEntity<ContainerViewDto> getContainer(@PathVariable UUID id) {
        ContainerViewDto view = tools.getContainer(id);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    /** What a scanned label resolves to; 404 when the token is unknown. */
    @GetMapping("/containers/by-token/{qrToken}")
    public ResponseEntity<ContainerViewDto> getContainerByToken(@PathVariable String qrToken) {
        ContainerViewDto view = tools.getContainerByToken(qrToken);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @GetMapping("/containers")
    public List<ContainerDto> listContainers(@RequestParam UUID householdId,
                                             @RequestParam(required = false) UUID zoneId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Integer limit) {
        return tools.listContainers(householdId, zoneId, status, limit);
    }

    // ── items ────────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/items")
    public ResponseEntity<?> saveItem(@RequestBody SaveItemInput input) {
        try {
            return ResponseEntity.ok(tools.saveItem(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/items")
    public List<ItemDto> listItems(@RequestParam UUID containerId) {
        return tools.listItems(containerId);
    }

    /** 404 when there was no such item, so a delete-by-description flow can tell the two apart. */
    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id) {
        return tools.deleteItem(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** "где лежит X" — each hit carries its container + zone. */
    @GetMapping("/items/search")
    public List<ItemLocationDto> searchItems(@RequestParam UUID householdId,
                                             @RequestParam String query,
                                             @RequestParam(required = false) Integer limit) {
        return tools.searchItems(householdId, query, limit);
    }
}
