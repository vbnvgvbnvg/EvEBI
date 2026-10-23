package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.dto.SdeItemDto;
import com.github.serbentd.eve.batch.service.SdeDownloadService;

/**
 * Streaming CSV reader for EVE item dimensions from conditional HTTP streams or local resources.
 */
public class SdeItemReader extends AbstractSdeCsvReader<SdeItemDto> {

    public SdeItemReader(SdeDownloadService downloadService, String syncSource, String url) {
        super(downloadService, syncSource, url);
    }

    @Override
    protected SdeItemDto mapRecord(String[] tokens) {
        Long typeId = getLong(tokens, "typeid");
        if (typeId == null) {
            return null;
        }

        return new SdeItemDto(
                typeId,
                getString(tokens, "typename"),
                getLong(tokens, "groupid"),
                getString(tokens, "groupname"),
                getLong(tokens, "categoryid"),
                getString(tokens, "categoryname"),
                getLong(tokens, "marketgroupid"),
                getBigDecimal(tokens, "volume"),
                getBigDecimal(tokens, "packagedvolume"),
                getInteger(tokens, "portionsize"),
                getBoolean(tokens, "published", "ispublished")
        );
    }
}
