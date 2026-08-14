package dev.fedorov.ailife.mcp.travel.parse;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * KMZ route parser — a KMZ is a ZIP archive containing a KML document. Since {@code ImportRouteInput.content}
 * is a String, KMZ bytes are passed <b>base64-encoded</b>: this parser base64-decodes, unzips, takes the
 * first {@code .kml} entry and delegates to {@link KmlRouteParser}. No new dependency (JDK {@code java.util.zip}
 * + {@code java.util.Base64}); the parser writes nothing to disk (reads a single entry into memory).
 */
@Component
public class KmzRouteParser implements RouteParser {

    private final KmlRouteParser kml;

    public KmzRouteParser(KmlRouteParser kml) {
        this.kml = kml;
    }

    @Override
    public String format() {
        return "kmz";
    }

    @Override
    public ParsedRoute parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Empty KMZ content");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(content.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid KMZ: content must be the base64-encoded archive bytes");
        }

        String kmlContent = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".kml")) {
                    kmlContent = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid KMZ archive: " + e.getMessage());
        }

        if (kmlContent == null) {
            throw new IllegalArgumentException("KMZ contains no .kml entry");
        }
        return kml.parse(kmlContent);
    }
}
