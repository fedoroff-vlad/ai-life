package dev.fedorov.ailife.mcp.fooddata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fooddata")
public class McpFoodDataProperties {

    /**
     * Open Food Facts base URL — where {@code food_lookup} reads product JSON
     * ({@code /api/v2/product/{barcode}.json} for a barcode, {@code /cgi/search.pl} for a name).
     */
    private String openFoodFactsUrl = "https://world.openfoodfacts.org";

    /**
     * Which food-data source to wire: {@code openfoodfacts} (default, free, no key). Behind the
     * {@code FoodDataSource} interface so a sibling source (USDA / a keyed provider) can replace it
     * later via env with no caller change (mirrors mcp-market-data's source selector).
     */
    private String source = "openfoodfacts";

    public String getOpenFoodFactsUrl() { return openFoodFactsUrl; }
    public void setOpenFoodFactsUrl(String openFoodFactsUrl) { this.openFoodFactsUrl = openFoodFactsUrl; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
