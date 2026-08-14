package dev.fedorov.ailife.mcp.travel.parse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RT-b: KMZ = a base64-encoded ZIP wrapping a KML — unwrap to the inner KML and parse it. */
class KmzRouteParserTest {

    private final KmzRouteParser parser = new KmzRouteParser(new KmlRouteParser());

    private static final String KML = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Архивный маршрут</name>
              <Placemark><name>Точка</name><Point><coordinates>10.0,20.0</coordinates></Point></Placemark>
              <Placemark><LineString><coordinates>1,2 3,4</coordinates></LineString></Placemark>
            </Document></kml>
            """;

    private static String kmz(String entryName, String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** Scenario: KMZ unwraps to its KML — the inner doc.kml parses to the same track + waypoint. */
    @Test
    void unwrapsInnerKml() throws Exception {
        ParsedRoute parsed = parser.parse(kmz("doc.kml", KML));

        assertThat(parsed.name()).isEqualTo("Архивный маршрут");
        assertThat(parsed.geometry().track()).hasSize(2);
        assertThat(parsed.geometry().waypoints()).hasSize(1);
        assertThat(parsed.geometry().waypoints().get(0).name()).isEqualTo("Точка");
        assertThat(parsed.geometry().waypoints().get(0).lat()).isEqualTo(20.0);
    }

    /** An archive with no .kml entry is rejected. */
    @Test
    void rejectsArchiveWithoutKml() throws Exception {
        assertThatThrownBy(() -> parser.parse(kmz("readme.txt", "not kml")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no .kml");
    }

    /** Non-archive content (valid base64 but not a ZIP) is rejected, not silently empty. */
    @Test
    void rejectsNonZipContent() {
        String base64OfPlainText = Base64.getEncoder().encodeToString("not a zip".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> parser.parse(base64OfPlainText))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
