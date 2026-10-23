package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.domain.SdeMetadataEntity;
import com.github.serbentd.eve.batch.repository.DimItemRepository;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import com.github.serbentd.eve.batch.repository.SdeMetadataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SdeSyncJobIntegrationTest {

    /** Fixture IDs matching rows in {@code sde/items_test.csv} and {@code sde/stations_test.csv}. */
    private static final long ISHTAR_TYPE_ID = 12005L;
    private static final long GILA_TYPE_ID = 17715L;
    private static final long HEK_FACTORY_STATION_ID = 60005686L;

    private final JobLauncher jobLauncher;
    private final Job sdeSyncJob;
    private final DimItemRepository dimItemRepository;
    private final DimStationRepository dimStationRepository;
    private final SdeMetadataRepository sdeMetadataRepository;

    SdeSyncJobIntegrationTest(
            JobLauncher jobLauncher,
            @Qualifier(SdeSyncJobConfig.JOB_NAME) Job sdeSyncJob,
            DimItemRepository dimItemRepository,
            DimStationRepository dimStationRepository,
            SdeMetadataRepository sdeMetadataRepository) {
        this.jobLauncher = jobLauncher;
        this.sdeSyncJob = sdeSyncJob;
        this.dimItemRepository = dimItemRepository;
        this.dimStationRepository = dimStationRepository;
        this.sdeMetadataRepository = sdeMetadataRepository;
    }

    @Test
    @DisplayName("sdeSyncJob should stream items and stations, persist into analytics schema, and track metadata")
    void sdeSyncJob_shouldSynchronizeDimensionsAndRecordMetadata() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("itemsUrl", "classpath:sde/items_test.csv")
                .addString("stationsUrl", "classpath:sde/stations_test.csv")
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(sdeSyncJob, params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 1. Verify synchronized items
        Optional<DimItemEntity> ishtar = dimItemRepository.findById(ISHTAR_TYPE_ID);
        assertThat(ishtar).isPresent();
        assertThat(ishtar.get().getTypeName()).isEqualTo("Ishtar");
        assertThat(ishtar.get().getGroupName()).isEqualTo("Cruiser");

        Optional<DimItemEntity> gila = dimItemRepository.findById(GILA_TYPE_ID);
        assertThat(gila).isPresent();
        assertThat(gila.get().getTypeName()).isEqualTo("Gila");

        // 2. Verify synchronized stations
        Optional<DimStationEntity> hekStation = dimStationRepository.findById(HEK_FACTORY_STATION_ID);
        assertThat(hekStation).isPresent();
        assertThat(hekStation.get().getStationName()).contains("Hek VIII - Moon 12");
        assertThat(hekStation.get().getSecurityClass()).isEqualTo("HIGH_SEC");

        // 3. Verify sde_metadata audit records
        Optional<SdeMetadataEntity> itemMeta = sdeMetadataRepository
                .findFirstBySyncSourceAndStatusOrderByStartedAtDesc("ITEMS", "SUCCESS");
        assertThat(itemMeta).isPresent();
        assertThat(itemMeta.get().getRecordsProcessed()).isEqualTo(2);

        Optional<SdeMetadataEntity> stationMeta = sdeMetadataRepository
                .findFirstBySyncSourceAndStatusOrderByStartedAtDesc("STATIONS", "SUCCESS");
        assertThat(stationMeta).isPresent();
        assertThat(stationMeta.get().getRecordsProcessed()).isEqualTo(2);

        // 4. Test Rerun Idempotency
        JobParameters rerunParams = new JobParametersBuilder(params)
                .addLong("run.id", System.currentTimeMillis() + 1)
                .toJobParameters();

        JobExecution rerunExecution = jobLauncher.run(sdeSyncJob, rerunParams);
        assertThat(rerunExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }
}
