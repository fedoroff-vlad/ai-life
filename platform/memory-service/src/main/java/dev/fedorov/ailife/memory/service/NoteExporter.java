package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.memory.domain.NoteRepository;
import dev.fedorov.ailife.memory.note.NoteMarkdown;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SB-7 markdown export — the <b>vault seam</b> (second-brain epic #257 closer). Reassembles a
 * household's {@code memory.note} rows into a zip of {@code .md} files ({@link NoteMarkdown}:
 * YAML frontmatter + body, {@code [[wiki-links]]} intact), the hand-off point for attaching an
 * Obsidian-like frontend later. Round-trippable: the frontmatter {@code id} is the durable anchor,
 * so a future import matches on it regardless of the (title-based) filename.
 */
@Service
public class NoteExporter {

    private final NoteRepository repo;

    public NoteExporter(NoteRepository repo) {
        this.repo = repo;
    }

    /** Zip every note in the household into a markdown vault. An empty household yields an empty zip. */
    public byte[] exportVault(UUID householdId) {
        if (householdId == null) {
            throw new IllegalArgumentException("householdId is required");
        }
        Set<String> used = new HashSet<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var row : repo.listAllByHousehold(householdId)) {
                NoteDto note = row.toDto();
                zip.putNextEntry(new ZipEntry(uniqueName(NoteMarkdown.fileNameBase(note), used)));
                zip.write(NoteMarkdown.render(note).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("note vault export failed for household " + householdId, e);
        }
        return out.toByteArray();
    }

    /** Disambiguate duplicate title stems within one export: {@code Note.md}, {@code Note (2).md}, … */
    private static String uniqueName(String base, Set<String> used) {
        String name = base + ".md";
        int n = 2;
        while (!used.add(name)) {
            name = base + " (" + n++ + ").md";
        }
        return name;
    }
}
