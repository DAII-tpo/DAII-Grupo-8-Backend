package com.citypass.movilidad.importer;

public record StationImportResult(int processed, int imported, int duplicates, int invalid) {
}
