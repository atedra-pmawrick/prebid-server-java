package org.prebid.server.bidder.adstanding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.BidRequest.BidRequestBuilder;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.BidResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import org.apache.commons.collections4.CollectionUtils;
import org.prebid.server.auction.model.AdUnitBid;
import org.prebid.server.auction.model.AdapterRequest;
import org.prebid.server.auction.model.PreBidRequestContext;
import org.prebid.server.bidder.OpenrtbAdapter;
import org.prebid.server.bidder.adstanding.proto.AdstandingBidExt;
import org.prebid.server.bidder.adstanding.proto.AdstandingBidExtAdstanding;
import org.prebid.server.bidder.adstanding.proto.AdstandingImpExt;
import org.prebid.server.bidder.adstanding.proto.AdstandingParams;
import org.prebid.server.bidder.model.AdUnitBidWithParams;
import org.prebid.server.bidder.model.AdapterHttpRequest;
import org.prebid.server.bidder.model.ExchangeCall;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.request.PreBidRequest;
import org.prebid.server.proto.response.Bid;
import org.prebid.server.proto.response.Bid.BidBuilder;
import org.prebid.server.proto.response.MediaType;
import org.prebid.server.util.HttpUtil;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AdstandingAdapter extends OpenrtbAdapter {
    private static final Set<MediaType> ALLOWED_MEDIA_TYPES = Collections
            .unmodifiableSet(EnumSet.of(MediaType.banner, MediaType.video));

    private final String endpointUrl;

    public AdstandingAdapter(String cookieFamilyName, String endpointUrl) {
        super(cookieFamilyName);
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
    }

    @Override
    public List<AdapterHttpRequest<BidRequest>> makeHttpRequests(AdapterRequest adapterRequest,
            PreBidRequestContext preBidRequestContext) {
        final BidRequest bidRequest = createBidRequest(adapterRequest, preBidRequestContext);
        final AdapterHttpRequest<BidRequest> httpRequest = AdapterHttpRequest.of(HttpMethod.POST, this.endpointUrl,
                bidRequest, headers());
        return Collections.singletonList(httpRequest);
    }

    private BidRequest createBidRequest(AdapterRequest adapterRequest, PreBidRequestContext preBidRequestContext) {
        final List<AdUnitBid> adUnitBids = adapterRequest.getAdUnitBids();
        final List<AdUnitBidWithParams<AdstandingParams>> adUnitBidsWithParams = createAdUnitBidsWithParams(adUnitBids);

        validateAdUnitBidsMediaTypes(adUnitBids, ALLOWED_MEDIA_TYPES);

        final List<Imp> imps = makeImps(adUnitBidsWithParams, preBidRequestContext);
        validateImps(imps);

        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        final BidRequestBuilder brb = BidRequest.builder();
        brb.id(preBidRequest.getTid());
        brb.at(1);
        brb.tmax(preBidRequest.getTimeoutMillis());
        brb.imp(imps);
        brb.app(preBidRequest.getApp());
        brb.site(makeSite(preBidRequestContext));
        brb.device(deviceBuilder(preBidRequestContext).build());
        brb.user(makeUser(preBidRequestContext));
        brb.source(makeSource(preBidRequestContext));
        brb.regs(preBidRequest.getRegs());

        return brb.build();
    }

    private static List<AdUnitBidWithParams<AdstandingParams>> createAdUnitBidsWithParams(List<AdUnitBid> adUnitBids) {
        return adUnitBids.stream()
                .map(adUnitBid -> AdUnitBidWithParams.of(adUnitBid, parseAndValidateParams(adUnitBid)))
                .collect(Collectors.toList());
    }

    private static AdstandingParams parseAndValidateParams(AdUnitBid adUnitBid) {
        final ObjectNode paramsNode = adUnitBid.getParams();
        if (paramsNode == null) {
            throw new PreBidException("Adstanding params section is missing");
        }

        AdstandingParams params;
        try {
            params = Json.mapper.convertValue(paramsNode, AdstandingParams.class);
        } catch (IllegalArgumentException e) {
            // a weird way to pass parsing exception
            throw new PreBidException(e.getMessage(), e.getCause());
        }

        final Integer zoneId = params.getZoneId();
        if (zoneId == null || Objects.equals(zoneId, 0)) {
            throw new PreBidException("No zone provided");
        }

        return params;
    }

    private static List<Imp> makeImps(List<AdUnitBidWithParams<AdstandingParams>> adUnitBids,
            PreBidRequestContext preBidRequestContext) {
        return adUnitBids.stream().filter(AdstandingAdapter::containsAnyAllowedMediaType)
                .map(adUnitBid -> makeImp(adUnitBid, preBidRequestContext)).collect(Collectors.toList());
    }

    private static boolean containsAnyAllowedMediaType(AdUnitBidWithParams<AdstandingParams> adUnitBid) {
        return CollectionUtils.containsAny(adUnitBid.getAdUnitBid().getMediaTypes(), ALLOWED_MEDIA_TYPES);
    }

    private static Imp makeImp(AdUnitBidWithParams<AdstandingParams> adUnitBidWithParam,
            PreBidRequestContext preBidRequestContext) {
        final AdUnitBid adUnitBid = adUnitBidWithParam.getAdUnitBid();
        AdstandingParams adstandingParams = adUnitBidWithParam.getParams();

        final Imp.ImpBuilder ib = Imp.builder();

        ib.id(adUnitBid.getAdUnitCode());
        ib.instl(adUnitBid.getInstl());
        ib.secure(preBidRequestContext.getSecure());
        ib.ext(Json.mapper.valueToTree(AdstandingImpExt.of(adstandingParams.getZoneId())));

        final Set<MediaType> mediaTypes = allowedMediaTypes(adUnitBid, ALLOWED_MEDIA_TYPES);
        if (mediaTypes.contains(MediaType.banner)) {
            ib.banner(bannerBuilder(adUnitBid).build());
        }
        if (mediaTypes.contains(MediaType.video)) {
            ib.video(videoBuilder(adUnitBid).build());
        }

        return ib.build();
    }

    @Override
    public List<Bid.BidBuilder> extractBids(AdapterRequest adapterRequest,
            ExchangeCall<BidRequest, BidResponse> exchangeCall) {
        return responseBidStream(exchangeCall.getResponse()).map(bid -> toBidBuilder(bid, adapterRequest))
                .collect(Collectors.toList());
    }

    private static Bid.BidBuilder toBidBuilder(com.iab.openrtb.response.Bid bid, AdapterRequest adapterRequest) {
        final AdUnitBid adUnitBid = lookupBid(adapterRequest.getAdUnitBids(), bid.getImpid());

        BidBuilder bb = Bid.builder();

        bb.bidder(adUnitBid.getBidderCode());
        bb.bidId(adUnitBid.getBidId());
        bb.code(bid.getImpid());
        bb.price(bid.getPrice());
        bb.adm(bid.getAdm());
        bb.creativeId(bid.getCrid());
        bb.mediaType(mediaTypeFor(bid.getExt()));
        bb.width(bid.getW());
        bb.height(bid.getH());
        bb.dealId(bid.getDealid());
        bb.nurl(bid.getNurl());

        return bb;
    }

    private static MediaType mediaTypeFor(ObjectNode bidExt) {
        final AdstandingBidExtAdstanding adstanding = parseAdstandingBidExt(bidExt).getAdstanding();
        if (adstanding == null) {
            throw new PreBidException("bidResponse.bid.ext.adstanding should be defined");
        }

        final Integer bidAdType = adstanding.getBidAdType();

        if (bidAdType == null) {
            throw new PreBidException("bidResponse.bid.ext.adstanding.bid_ad_type should be defined");
        }

        if (bidAdType == 0) {
            return MediaType.banner;
        } else if (bidAdType == 1) {
            return MediaType.video;
        } else {
            throw new PreBidException(
                    String.format("Unrecognized bid_ad_type in response from adstanding: %s", bidAdType));
        }
    }

    private static AdstandingBidExt parseAdstandingBidExt(ObjectNode bidExt) {
        if (bidExt == null) {
            throw new PreBidException("bidResponse.bid.ext should be defined for adstanding");
        }

        final AdstandingBidExt adstandingBidExt;
        try {
            adstandingBidExt = Json.mapper.treeToValue(bidExt, AdstandingBidExt.class);
        } catch (JsonProcessingException e) {
            throw new PreBidException(e.getMessage(), e);
        }
        return adstandingBidExt;
    }

}
