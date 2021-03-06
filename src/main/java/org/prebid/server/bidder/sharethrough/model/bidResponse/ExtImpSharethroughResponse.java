package org.prebid.server.bidder.sharethrough.model.bidResponse;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Builder
@Value
public class ExtImpSharethroughResponse {

    @JsonProperty("adserverRequestId")
    String adserverRequestId;

    @JsonProperty("bidId")
    String bidId;

    @JsonProperty("cookieSyncUrls")
    List<String> cookieSyncUrls;

    List<ExtImpSharethroughCreative> creatives;

    ExtImpSharethroughPlacement placement;

    @JsonProperty("stxUserId")
    String stxUserId;
}

