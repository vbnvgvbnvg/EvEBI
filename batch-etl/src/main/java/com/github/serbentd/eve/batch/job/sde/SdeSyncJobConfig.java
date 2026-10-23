package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.DimItemEntity;
import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.dto.SdeItemDto;
import com.github.serbentd.eve.batch.dto.SdeStationDto;
import com.github.serbentd.eve.batch.reaper.AdvisoryLockJobExecutionListener;
import com.github.serbentd.eve.batch.service.SdeDownloadService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch 5 configuration for {@code sdeSyncJob}.
 * Orchestrates conditional streaming synchronization of EVE Static Data Export (SDE)
 * reference dimensions (items and stations) with high-throughput atomic batch upserts.
 */
@Configuration
public class SdeSyncJobConfig {

    public static final String JOB_NAME = "sdeSyncJob";
    public static final String ITEM_STEP_NAME = "sdeItemSyncStep";
    public static final String STATION_STEP_NAME = "sdeStationSyncStep";

    @Bean
    @StepScope
    public SdeItemReader sdeItemReader(
            SdeDownloadService downloadService,
            BatchProperties batchProperties,
            @Value("#{jobParameters['itemsUrl']}") String itemsUrlParam) {

        String url = (itemsUrlParam != null && !itemsUrlParam.isBlank())
                ? itemsUrlParam
                : batchProperties.sde().itemsUrl();
        return new SdeItemReader(downloadService, "ITEMS", url);
    }

    @Bean
    @StepScope
    public SdeStationReader sdeStationReader(
            SdeDownloadService downloadService,
            BatchProperties batchProperties,
            @Value("#{jobParameters['stationsUrl']}") String stationsUrlParam) {

        String url = (stationsUrlParam != null && !stationsUrlParam.isBlank())
                ? stationsUrlParam
                : batchProperties.sde().stationsUrl();
        return new SdeStationReader(downloadService, "STATIONS", url);
    }

    @Bean
    public Step sdeItemSyncStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            SdeItemReader sdeItemReader,
            SdeItemProcessor processor,
            ItemDimensionWriter writer,
            BatchProperties batchProperties) {

        return new StepBuilder(ITEM_STEP_NAME, jobRepository)
                .<SdeItemDto, DimItemEntity>chunk(batchProperties.sde().chunkSize(), transactionManager)
                .reader(sdeItemReader)
                .processor(processor)
                .writer(writer)
                .listener(sdeItemReader)
                .build();
    }

    @Bean
    public Step sdeStationSyncStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            SdeStationReader sdeStationReader,
            SdeStationProcessor processor,
            StationDimensionWriter writer,
            BatchProperties batchProperties) {

        return new StepBuilder(STATION_STEP_NAME, jobRepository)
                .<SdeStationDto, DimStationEntity>chunk(batchProperties.sde().chunkSize(), transactionManager)
                .reader(sdeStationReader)
                .processor(processor)
                .writer(writer)
                .listener(sdeStationReader)
                .build();
    }

    @Bean
    public Job sdeSyncJob(
            JobRepository jobRepository,
            @Qualifier(ITEM_STEP_NAME) Step sdeItemSyncStep,
            @Qualifier(STATION_STEP_NAME) Step sdeStationSyncStep,
            AdvisoryLockJobExecutionListener advisoryLockListener) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .start(sdeItemSyncStep)
                .next(sdeStationSyncStep)
                .listener(advisoryLockListener)
                .build();
    }
}
