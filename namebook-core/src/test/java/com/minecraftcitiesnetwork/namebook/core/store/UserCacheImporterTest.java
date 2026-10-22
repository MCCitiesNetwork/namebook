package com.minecraftcitiesnetwork.namebook.core.store;

import com.minecraftcitiesnetwork.namebook.core.importer.ImportResult;
import com.minecraftcitiesnetwork.namebook.core.importer.UserCacheImporter;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Lives in the store package because AbstractDatabaseTest is package-private.
class UserCacheImporterTest extends AbstractDatabaseTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path directory;

    private PlayerStore store;
    private UserCacheImporter importer;

    @BeforeEach
    void createImporter() {
        this.store = new PlayerStore(database);
        this.importer = new UserCacheImporter(this.store, new NameRules("."));
    }

    private Path write(String json) throws IOException {
        return Files.writeString(this.directory.resolve("usercache.json"), json);
    }

    private Path sample() throws URISyntaxException {
        return Path.of(getClass().getResource("/usercache.json").toURI());
    }

    @Test
    void import_addsNewEntries() throws Exception {
        assertEquals(new ImportResult(2, 0), this.importer.importFile(sample()));
        assertEquals(Optional.of("Alice_Test"), this.store.nameOf(ALICE));
        assertEquals(Optional.of(BOB), this.store.uuidOf("BobTest"));
    }

    @Test
    void import_neverChangesExistingRows() throws Exception {
        this.store.record(ALICE, "Renamed");
        ImportResult result = this.importer.importFile(sample());
        assertEquals(new ImportResult(1, 1), result);
        assertEquals(Optional.of("Renamed"), this.store.nameOf(ALICE));
    }

    @Test
    void import_skipsNameHeldByAnotherUuid() throws Exception {
        UUID other = UUID.randomUUID();
        this.store.record(other, "BobTest");
        ImportResult result = this.importer.importFile(sample());
        assertEquals(new ImportResult(1, 1), result);
        assertEquals(Optional.of(other), this.store.uuidOf("BobTest"));
        assertEquals(Optional.empty(), this.store.nameOf(BOB));
    }

    @Test
    void import_skipsInvalidEntries() throws Exception {
        Path file = write("""
                [{"name":"bad name","uuid":"11111111-1111-1111-1111-111111111111"},
                 {"name":"Fine_One","uuid":"not-a-uuid"},
                 {"uuid":"22222222-2222-2222-2222-222222222222"},
                 {"name":"%","uuid":"33333333-3333-3333-3333-333333333333"},
                 "junk",
                 {"name":"Good","uuid":"44444444-4444-4444-4444-444444444444"}]""");
        assertEquals(new ImportResult(1, 5), this.importer.importFile(file));
        assertEquals(Optional.of("Good"), this.store.nameOf(UUID.fromString("44444444-4444-4444-4444-444444444444")));
    }

    @Test
    void import_missingFileThrows() {
        assertThrows(NoSuchFileException.class,
                () -> this.importer.importFile(this.directory.resolve("absent.json")));
    }
}
