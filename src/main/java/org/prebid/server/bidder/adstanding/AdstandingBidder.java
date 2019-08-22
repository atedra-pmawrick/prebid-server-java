package org.prebid.server.bidder.adstanding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AdStanding {@link Bidder} implementation.
 */
public class AdstandingBidder implements Bidder<BidRequest> {

    private static final String PREBID_SERVER_USER_AGENT = "prebid-server/1.0";

    private final String endpointUrl;
    private final MultiMap headers;

    public AdstandingBidder(String endpoint) {
        endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpoint));

        headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(HttpUtil.CONTENT_TYPE_HEADER, HttpUtil.APPLICATION_JSON_CONTENT_TYPE);
        headers.add(HttpUtil.ACCEPT_HEADER, HttpHeaderValues.APPLICATION_JSON);
        headers.add(HttpUtil.USER_AGENT_HEADER, PREBID_SERVER_USER_AGENT);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final List<HttpRequest<BidRequest>> httpRequests = new ArrayList<>(1);
        final List<BidderError> errors = new ArrayList<>();

        try {
            final String body = Json.encode(bidRequest);
            httpRequests.add(HttpRequest.<BidRequest>builder().method(HttpMethod.POST).uri(endpointUrl).body(body)
                    .headers(headers).payload(bidRequest).build());
        } catch (PreBidException e) {
            errors.add(BidderError.badInput(e.getMessage()));
        }

        return Result.of(httpRequests, errors);
    }

    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = Json.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.of(bidsFromResponse(httpCall.getRequest().getPayload(), bidResponse),
                    Collections.emptyList());
        } catch (DecodeException e) {
            return Result.emptyWithError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    @Override
    public Map<String, String> extractTargeting(ObjectNode extBidBidder) {
        return Collections.emptyMap();
    }

    private static List<BidderBid> bidsFromResponse(BidRequest bidRequest, BidResponse bidResponse) {
        if (bidResponse == null) {
            return Collections.emptyList();
        }

        final List<BidderBid> bids = new LinkedList<>();

        if (bidResponse.getSeatbid() != null) {
            for (SeatBid seatbid : bidResponse.getSeatbid()) {
                if (seatbid.getBid() != null) {
                    for (Bid bid : seatbid.getBid()) {
                        BidType bidType = resolveBidType(bidRequest, bid);
                        if (bidType != null) {
                            bids.add(BidderBid.of(bid, bidType, bidResponse.getCur()));
                        }
                    }
                }
            }
        }

        return bids;
    }

    private static BidType resolveBidType(BidRequest bidRequest, Bid bid) {
        for (Imp imp : bidRequest.getImp()) {
            if (imp.getId() == bid.getImpid()) {
                if (imp.getBanner() != null) {
                    return BidType.banner;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                }
            }
        }
        return null;
    }

}
