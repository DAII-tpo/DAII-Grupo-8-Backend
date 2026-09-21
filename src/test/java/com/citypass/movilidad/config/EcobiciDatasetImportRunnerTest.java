package com.citypass.movilidad.config;

import com.citypass.movilidad.importer.StationImportResult;
import com.citypass.movilidad.service.EcobiciStationImportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ByteArrayResource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcobiciDatasetImportRunnerTest {

    @Test
    void delegatesDatasetImportOnStartup() throws Exception {
        EcobiciStationImportService service = mock(EcobiciStationImportService.class);
        ByteArrayResource dataset = new ByteArrayResource(new byte[0]);
        when(service.importStations(dataset)).thenReturn(new StationImportResult(3, 2, 1, 0));
        EcobiciDatasetImportRunner runner = new EcobiciDatasetImportRunner(service, dataset);

        runner.run(new DefaultApplicationArguments());

        verify(service).importStations(dataset);
    }
}
