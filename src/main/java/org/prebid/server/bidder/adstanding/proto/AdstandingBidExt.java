package org.prebid.server.bidder.adstanding.proto;

import lombok.AllArgsConstructor;
import lombok.Value;

@AllArgsConstructor(staticName = "of")
@Value
public final class AdstandingBidExt {

    AdstandingBidExtAdstanding adstanding;
}
