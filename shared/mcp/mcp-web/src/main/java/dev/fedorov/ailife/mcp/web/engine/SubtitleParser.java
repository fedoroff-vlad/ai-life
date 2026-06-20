package dev.fedorov.ailife.mcp.web.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a WebVTT subtitle file (what yt-dlp writes for auto-captions/subs) into plain transcript
 * text: drops the {@code WEBVTT} header, cue timings ({@code 00:00:01.000 --> …}), numeric cue ids,
 * and inline tags ({@code <00:00:00.480>}, {@code <c>…</c>}); collapses the consecutive duplicate
 * lines auto-captions emit. Pure function — unit-tested without yt-dlp or a network.
 */
final class SubtitleParser {

    private SubtitleParser() {
    }

    static String parseVtt(String vtt) {
        if (vtt == null || vtt.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        String last = null;
        for (String raw : vtt.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()
                    || line.equals("WEBVTT")
                    || line.contains("-->")
                    || line.matches("\\d+")            // cue id
                    || line.startsWith("Kind:")
                    || line.startsWith("Language:")
                    || line.startsWith("NOTE")) {
                continue;
            }
            // Strip inline timestamp/style tags like <00:00:00.480> and <c>…</c>.
            String text = line.replaceAll("<[^>]*>", "").strip();
            if (text.isEmpty() || text.equals(last)) {
                continue; // drop blanks + the consecutive repeats auto-captions emit
            }
            out.add(text);
            last = text;
        }
        return String.join(" ", out);
    }
}
