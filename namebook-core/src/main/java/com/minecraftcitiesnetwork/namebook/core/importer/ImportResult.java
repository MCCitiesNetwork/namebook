package com.minecraftcitiesnetwork.namebook.core.importer;

/** What an import did: rows inserted, and entries left out because they were invalid or already known. */
public record ImportResult(int added, int skipped) {
}
