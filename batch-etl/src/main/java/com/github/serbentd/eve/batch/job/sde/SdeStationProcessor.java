package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.dto.SdeStationDto;
import com.github.serbentd.eve.batch.service.CitadelDimensionResolver;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Item processor that maps raw transit records (SdeStationDto) into conformed domain entities (DimStationEntity),
 * applying security class derivations, location fallbacks, and audit timestamps.
 */
@Component
public class SdeStationProcessor implements ItemProcessor<SdeStationDto, DimStationEntity> {

    private static final BigDecimal HIGH_SEC_THRESHOLD = new BigDecimal("0.45");

    private final CitadelDimensionResolver citadelResolver;

    public SdeStationProcessor(CitadelDimensionResolver citadelResolver) {
        this.citadelResolver = citadelResolver;
    }

    @Override
    public DimStationEntity process(SdeStationDto station) {
        if (station == null || station.stationId() == null || station.regionId() == null) {
            return null;
        }

        String stationName = station.stationName() != null && !station.stationName().isBlank()
                ? station.stationName().trim()
                : "Unknown Location (" + station.stationId() + ")";

        Long solarSystemId = station.solarSystemId() != null ? station.solarSystemId() : 0L;
        String solarSystemName = station.solarSystemName() != null ? station.solarSystemName() : "System " + solarSystemId;
        BigDecimal securityStatus = station.securityStatus() != null ? station.securityStatus() : BigDecimal.ZERO;

        String securityClass = station.securityClass() != null && !station.securityClass().isBlank()
                ? station.securityClass()
                : deriveSecurityClass(securityStatus);

        Boolean isStructure = station.isStructure() != null
                ? station.isStructure()
                : citadelResolver.isUpwellStructure(station.stationId());

        Instant now = Instant.now();
        return DimStationEntity.builder()
                .stationId(station.stationId())
                .stationName(stationName)
                .solarSystemId(solarSystemId)
                .solarSystemName(solarSystemName)
                .regionId(station.regionId())
                .securityStatus(securityStatus)
                .securityClass(securityClass)
                .isStructure(isStructure)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public static String deriveSecurityClass(BigDecimal securityStatus) {
        if (securityStatus == null) {
            return "UNKNOWN";
        }
        if (securityStatus.compareTo(HIGH_SEC_THRESHOLD) >= 0) {
            return "HIGH_SEC";
        }
        if (securityStatus.compareTo(BigDecimal.ZERO) > 0) {
            return "LOW_SEC";
        }
        return "NULL_SEC";
    }
}
