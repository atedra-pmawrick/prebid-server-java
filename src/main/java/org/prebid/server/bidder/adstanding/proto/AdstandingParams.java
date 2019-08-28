package org.prebid.server.bidder.adstanding.proto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Builder(toBuilder = true)
@Value
public class AdstandingParams {
    @JsonProperty("zone_id")
    Integer zoneId;
}
