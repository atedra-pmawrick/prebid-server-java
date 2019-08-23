package org.prebid.server.bidder.adstanding;

import org.prebid.server.bidder.OpenrtbBidder;

public class AdstandingBidder extends OpenrtbBidder<Void> {

    public AdstandingBidder(String endpointUrl) {
        super(endpointUrl, RequestCreationStrategy.SINGLE_REQUEST, Void.class);
    }

}
