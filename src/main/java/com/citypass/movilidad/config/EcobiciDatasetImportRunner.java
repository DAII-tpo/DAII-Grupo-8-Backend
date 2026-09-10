package com.citypass.movilidad.config;

import com.citypass.movilidad.importer.StationImportResult;
import com.citypass.movilidad.service.EcobiciStationImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.dataset.ecobici.import-enabled", havingValue = "true")
public class EcobiciDatasetImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(EcobiciDatasetImportRunner.class);

    private final EcobiciStationImportService importService;
    private final Resource dataset;

    public EcobiciDatasetImportRunner(
            EcobiciStationImportService importService,
            @Value("${app.dataset.ecobici.location}") Resource dataset
    ) {
        this.importService = importService;
        this.dataset = dataset;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        StationImportResult result = importService.importStations(dataset);
        LOGGER.info("Ecobici import finished: processed={}, imported={}, duplicates={}, invalid={}",
                result.processed(), result.imported(), result.duplicates(), result.invalid());
    }
}
