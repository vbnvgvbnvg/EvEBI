package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.dto.SdeStationDto;
import com.github.serbentd.eve.batch.service.SdeDownloadService;

/**
 * Streaming CSV reader for EVE station dimensions from conditional HTTP streams or local resources.
 */
public class SdeStationReader extends AbstractSdeCsvReader<SdeStationDto> {

    public SdeStationReader(SdeDownloadService downloadService, String syncSource, String url) {
        super(downloadService, syncSource, url);
    }

    @Override
    protected SdeStationDto mapRecord(String[] tokens) {
        Long stationId = getLong(tokens, "stationid");
        Long regionId = getLong(tokens, "regionid");
        if (stationId == null || regionId == null) {
            return null;
        }

        return new SdeStationDto(
                stationId,
                getString(tokens, "stationname"),
                getLong(tokens, "solarsystemid"),
                getString(tokens, "solarsystemname"),
                regionId,
                getBigDecimal(tokens, "securitystatus", "security"),
                getString(tokens, "securityclass"),
                getBoolean(tokens, "isstructure", "structure")
        );
    }
}
