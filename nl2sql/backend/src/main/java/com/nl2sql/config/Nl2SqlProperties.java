package com.nl2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "nl2sql")
public class Nl2SqlProperties {

    private Metadata metadata = new Metadata();
    private Security security = new Security();

    @Data
    public static class Metadata {
        private List<String> defaultSchemas;
        private String storagePath = "./metadata";
        private int maxResultRows = 500;
        private String favouritesFile = "./metadata/favourites.json";
    }

    @Data
    public static class Security {
        /** Allow INSERT statements. Default false. Set true in application.yml to enable. */
        private boolean allowInsert = false;
        /** Allow UPDATE statements. Default false. Set true in application.yml to enable. */
        private boolean allowUpdate = false;
        // DELETE is NEVER allowed — no config flag intentionally.
    }

    // Convenience accessors for backward compatibility
    public String getStoragePath()          { return metadata.getStoragePath(); }
    public int    getMaxResultRows()        { return metadata.getMaxResultRows(); }
    public List<String> getDefaultSchemas() { return metadata.getDefaultSchemas(); }
    public String getFavouritesFile()       { return metadata.getFavouritesFile(); }
    public boolean isAllowInsert()          { return security.isAllowInsert(); }
    public boolean isAllowUpdate()          { return security.isAllowUpdate(); }
}
