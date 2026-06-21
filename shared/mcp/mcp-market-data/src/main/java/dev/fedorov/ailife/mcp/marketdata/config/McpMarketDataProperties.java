package dev.fedorov.ailife.mcp.marketdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marketdata")
public class McpMarketDataProperties {

    /** Stooq base URL — where {@code quote} reads CSV ({@code /q/l/?s=&f=&e=csv}). */
    private String stooqUrl = "https://stooq.com";

    /**
     * Which quotes source to wire: {@code stooq} (default, free, no key). Behind the
     * {@code MarketDataSource} interface so a sibling source (Yahoo / a keyed provider) can
     * replace it later via env with no caller change (mirrors mcp-web's search-engine selector).
     */
    private String source = "stooq";

    public String getStooqUrl() { return stooqUrl; }
    public void setStooqUrl(String stooqUrl) { this.stooqUrl = stooqUrl; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
