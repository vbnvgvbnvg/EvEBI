package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.service.EsiRegionSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Spring Batch Tasklet executing programmatic synchronization of EVE market regions
 * from CCP's ESI Universe API before reference item and station steps execute.
 */
@Slf4j
@Component
public class EsiRegionSyncTasklet implements Tasklet {

    private final EsiRegionSyncService regionSyncService;

    public EsiRegionSyncTasklet(EsiRegionSyncService regionSyncService) {
        this.regionSyncService = regionSyncService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        int count = regionSyncService.syncRegions();
        log.info("EsiRegionSyncTasklet completed; synchronized {} regions into analytics.dim_region", count);
        contribution.incrementWriteCount(count);
        return RepeatStatus.FINISHED;
    }
}
