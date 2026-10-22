package com.minecraftcitiesnetwork.namebook.core.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamebookDatabaseUrlTest {

    private static String url(String configured) {
        return NamebookDatabase.jdbcUrl(new DatabaseSettings(configured, "user", "secret"));
    }

    @Test
    void jdbcUrl_addsTimeoutsAndTimeZone() {
        assertEquals("jdbc:mariadb://db:3306/namebook?connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=true"
                        + "&connectTimeout=5000&socketTimeout=10000",
                url("mariadb://db:3306/namebook"));
    }

    @Test
    void jdbcUrl_existingQuery_appendsWithAmpersand() {
        assertEquals("jdbc:mariadb://db/namebook?useSsl=true&connectionTimeZone=LOCAL"
                        + "&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=10000",
                url("mariadb://db/namebook?useSsl=true"));
    }

    @Test
    void jdbcUrl_configuredTimeouts_keptAsGiven() {
        assertEquals("jdbc:mariadb://db/namebook?connectTimeout=2000&socketTimeout=0&connectionTimeZone=UTC",
                url("mariadb://db/namebook?connectTimeout=2000&socketTimeout=0&connectionTimeZone=UTC"));
    }

    @Test
    void jdbcUrl_oneTimeoutConfigured_addsTheOther() {
        assertEquals("jdbc:mariadb://db/namebook?socketTimeout=30000&connectionTimeZone=UTC&connectTimeout=5000",
                url("mariadb://db/namebook?socketTimeout=30000&connectionTimeZone=UTC"));
    }
}
