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

    /**
     * Which STT engine to wire: {@code whisper} (default, real, calls the whisper ASR
     * sidecar over HTTP) or {@code stub} (native-free marker, for the wiring test /
     * degraded environments). Engine decision LOCKED = whisper sidecar service (owner
     * 2026-06-21) — see {@code plans/media.md}.
     */
    private String sttEngine = "whisper";

    /** Whisper ASR sidecar base URL — where {@code transcribe} POSTs the audio bytes. */
    private String whisperUrl = "http://whisper:9000";

    /**
     * Which video-keyframe extractor to wire: {@code ffmpeg} (default, real, needs the native tool
     * in the image) or {@code stub} (native-free marker frames, for the wiring test / degraded
     * environments). MP-e — the visual channel for speechless video.
     */
    private String frameExtractor = "ffmpeg";

    /** Path/name of the ffmpeg binary (the image bundles it as {@code ffmpeg}). */
    private String ffmpegBin = "ffmpeg";

    /** Path/name of the ffprobe binary (bundled by the same ffmpeg package). */
    private String ffprobeBin = "ffprobe";

    /** ffprobe/ffmpeg per-invocation subprocess timeout (seconds). */
    private int frameTimeoutSec = 30;

    /** Upper bound on {@code frames(mediaId, n)} — a video only needs a handful of keyframes. */
    private int frameMaxCount = 20;

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }
    public String getOcrEngine() { return ocrEngine; }
    public void setOcrEngine(String ocrEngine) { this.ocrEngine = ocrEngine; }
    public String getTessLang() { return tessLang; }
    public void setTessLang(String tessLang) { this.tessLang = tessLang; }
    public String getTessDataPath() { return tessDataPath; }
    public void setTessDataPath(String tessDataPath) { this.tessDataPath = tessDataPath; }
    public String getSttEngine() { return sttEngine; }
    public void setSttEngine(String sttEngine) { this.sttEngine = sttEngine; }
    public String getWhisperUrl() { return whisperUrl; }
    public void setWhisperUrl(String whisperUrl) { this.whisperUrl = whisperUrl; }
    public String getFrameExtractor() { return frameExtractor; }
    public void setFrameExtractor(String frameExtractor) { this.frameExtractor = frameExtractor; }
    public String getFfmpegBin() { return ffmpegBin; }
    public void setFfmpegBin(String ffmpegBin) { this.ffmpegBin = ffmpegBin; }
    public String getFfprobeBin() { return ffprobeBin; }
    public void setFfprobeBin(String ffprobeBin) { this.ffprobeBin = ffprobeBin; }
    public int getFrameTimeoutSec() { return frameTimeoutSec; }
    public void setFrameTimeoutSec(int frameTimeoutSec) { this.frameTimeoutSec = frameTimeoutSec; }
    public int getFrameMaxCount() { return frameMaxCount; }
    public void setFrameMaxCount(int frameMaxCount) { this.frameMaxCount = frameMaxCount; }
}
