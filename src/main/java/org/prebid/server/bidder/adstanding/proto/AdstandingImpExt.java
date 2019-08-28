package org.prebid.server.bidder.adstanding.proto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Value;

@AllArgsConstructor(staticName = "of")
@Value
public class AdstandingImpExt {
    @JsonProperty("zone_id")
    Integer zoneId;
}
