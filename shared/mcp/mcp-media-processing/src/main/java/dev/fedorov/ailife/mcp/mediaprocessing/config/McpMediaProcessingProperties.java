package dev.fedorov.ailife.mcp.mediaprocessing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mediaprocessing")
public class McpMediaProcessingProperties {

    /** media-service base URL — where {@code ocr} fetches blob bytes by object id. */
    private String mediaServiceUrl = "http://media-service:8088";

    /**
     * Which OCR engine to wire: {@code tesseract} (default, real, needs the native lib)
     * or {@code stub} (native-free marker, for the wiring test / degraded environments).
     */
    private String ocrEngine = "tesseract";

    /** Tesseract languages, '+'-joined (e.g. {@code rus+eng}). */
    private String tessLang = "rus+eng";

    /**
     * Explicit tessdata directory. Blank → resolved from {@code TESSDATA_PREFIX} env, then
     * a probe of common distro paths (so the image/CI works across tesseract 4/5 layouts).
     */
    private String tessDataPath = "";

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }
    public String getOcrEngine() { return ocrEngine; }
    public void setOcrEngine(String ocrEngine) { this.ocrEngine = ocrEngine; }
    public String getTessLang() { return tessLang; }
    public void setTessLang(String tessLang) { this.tessLang = tessLang; }
    public String getTessDataPath() { return tessDataPath; }
    public void setTessDataPath(String tessDataPath) { this.tessDataPath = tessDataPath; }
}
