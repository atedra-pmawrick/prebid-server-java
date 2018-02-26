package org.prebid.server.adapter.pubmatic;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Cookie;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.adapter.OpenrtbAdapter;
import org.prebid.server.adapter.model.AdUnitBidWithParams;
import org.prebid.server.adapter.model.ExchangeCall;
import org.prebid.server.adapter.model.HttpRequest;
import org.prebid.server.adapter.pubmatic.model.PubmaticParams;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.model.AdUnitBid;
import org.prebid.server.model.Bidder;
import org.prebid.server.model.MediaType;
import org.prebid.server.model.PreBidRequestContext;
import org.prebid.server.model.request.PreBidRequest;
import org.prebid.server.model.response.Bid;
import org.prebid.server.model.response.UsersyncInfo;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PubmaticAdapter extends OpenrtbAdapter {

    private static final Logger logger = LoggerFactory.getLogger(PubmaticAdapter.class);

    private static final Set<MediaType> ALLOWED_MEDIA_TYPES = Collections.unmodifiableSet(
            EnumSet.of(MediaType.banner, MediaType.video));

    private final String endpointUrl;
    private final UsersyncInfo usersyncInfo;

    public PubmaticAdapter(String endpointUrl, String usersyncUrl, String externalUrl) {
        this.endpointUrl = validateUrl(Objects.requireNonNull(endpointUrl));

        usersyncInfo = createUsersyncInfo(Objects.requireNonNull(usersyncUrl), Objects.requireNonNull(externalUrl));
    }

    private static UsersyncInfo createUsersyncInfo(String usersyncUrl, String externalUrl) {
        final String redirectUri = encodeUrl("%s/setuid?bidder=pubmatic&uid=", externalUrl);

        return UsersyncInfo.of(String.format("%s%s", usersyncUrl, redirectUri), "iframe", false);
    }

    @Override
    public String code() {
        return "pubmatic";
    }

    @Override
    public String cookieFamily() {
        return "pubmatic";
    }

    @Override
    public UsersyncInfo usersyncInfo() {
        return usersyncInfo;
    }

    @Override
    public List<HttpRequest> makeHttpRequests(Bidder bidder, PreBidRequestContext preBidRequestContext) {
        final MultiMap headers = headers()
                .add(HttpHeaders.SET_COOKIE, makeUserCookie(preBidRequestContext));

        final BidRequest bidRequest = createBidRequest(bidder, preBidRequestContext);
        final HttpRequest httpRequest = HttpRequest.of(endpointUrl, headers, bidRequest);
        return Collections.singletonList(httpRequest);
    }

    private BidRequest createBidRequest(Bidder bidder, PreBidRequestContext preBidRequestContext) {
        final List<AdUnitBid> adUnitBids = bidder.getAdUnitBids();

        validateAdUnitBidsMediaTypes(adUnitBids);

        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        final List<AdUnitBidWithParams<Params>> adUnitBidsWithParams = createAdUnitBidsWithParams(adUnitBids,
                preBidRequest.getTid());
        final List<Imp> imps = makeImps(adUnitBidsWithParams, preBidRequestContext);
        validateImps(imps);

        final Publisher publisher = makePublisher(preBidRequestContext, adUnitBidsWithParams);

        return BidRequest.builder()
                .id(preBidRequest.getTid())
                .at(1)
                .tmax(preBidRequest.getTimeoutMillis())
                .imp(imps)
                .app(makeApp(preBidRequestContext, publisher))
                .site(makeSite(preBidRequestContext, publisher))
                .device(deviceBuilder(preBidRequestContext).build())
                .user(makeUser(preBidRequestContext))
                .source(makeSource(preBidRequestContext))
                .build();
    }

    private static List<AdUnitBidWithParams<Params>> createAdUnitBidsWithParams(List<AdUnitBid> adUnitBids,
                                                                                String requestId) {
        final List<AdUnitBidWithParams<Params>> adUnitBidWithParams = adUnitBids.stream()
                .map(adUnitBid -> AdUnitBidWithParams.of(adUnitBid, parseAndValidateParams(adUnitBid, requestId)))
                .collect(Collectors.toList());

        // at least one adUnitBid of banner type must be with valid params
        if (adUnitBidWithParams.stream().noneMatch(PubmaticAdapter::isValidParams)) {
            throw new PreBidException("Incorrect adSlot / Publisher param");
        }

        return adUnitBidWithParams;
    }

    private static boolean isValidParams(AdUnitBidWithParams<Params> adUnitBidWithParams) {
        final Params params = adUnitBidWithParams.getParams();
        if (params == null) {
            return false;
        }

        // if adUnitBid has banner type, params should contains tagId, width and height fields
        return !adUnitBidWithParams.getAdUnitBid().getMediaTypes().contains(MediaType.banner)
                || ObjectUtils.allNotNull(params.getTagId(), params.getWidth(), params.getHeight());
    }

    private static Params parseAndValidateParams(AdUnitBid adUnitBid, String requestId) {
        final ObjectNode params = adUnitBid.getParams();
        if (params == null) {
            logWrongParams(requestId, null, adUnitBid, "Ignored bid: invalid JSON  [%s] err [%s]", null,
                    "params section is missing");
            return null;
        }

        final PubmaticParams pubmaticParams;
        try {
            pubmaticParams = Json.mapper.convertValue(params, PubmaticParams.class);
        } catch (IllegalArgumentException e) {
            logWrongParams(requestId, null, adUnitBid, "Ignored bid: invalid JSON  [%s] err [%s]", params, e);
            return null;
        }

        final String publisherId = pubmaticParams.getPublisherId();
        if (StringUtils.isEmpty(publisherId)) {
            logWrongParams(requestId, publisherId, adUnitBid, "Ignored bid: Publisher Id missing");
            return null;
        }

        final String adSlot = StringUtils.trimToNull(pubmaticParams.getAdSlot());
        if (StringUtils.isEmpty(adSlot)) {
            logWrongParams(requestId, publisherId, adUnitBid, "Ignored bid: adSlot missing");
            return null;
        }

        final String[] adSlots = adSlot.split("@");
        if (adSlots.length != 2 || StringUtils.isEmpty(adSlots[0]) || StringUtils.isEmpty(adSlots[1])) {
            logWrongParams(requestId, publisherId, adUnitBid, "Ignored bid: invalid adSlot [%s]", adSlot);
            return null;
        }

        final String[] adSizes = adSlots[1].toLowerCase().split("x");
        if (adSizes.length != 2) {
            logWrongParams(requestId, publisherId, adUnitBid, "Ignored bid: invalid adSize [%s]", adSlots[1]);
            return null;
        }

        final int width;
        try {
            width = Integer.parseInt(adSizes[0].trim());
        } catch (NumberFormatException e) {
            logWrongParams(requestId, publisherId, adUnitBid, "Ignored bid: invalid adSlot width [%s]", adSizes[0]);
            return null;
        }

        final int height;
        final String[] adSizeHeights = adSizes[1].split(":");
        try {
            height = Integer.parseInt(adSizeHeights[0].trim());
        } catch (NumberFormatException e) {
            logWrongParams(requestId, publisherId, adUnitBid, "Ignored bid: invalid adSlot height [%s]", adSizes[0]);
            return null;
        }

        return Params.of(publisherId, adSlot, adSlots[0], width, height);
    }

    private static void logWrongParams(String requestId, String publisherId, AdUnitBid adUnitBid, String errorMessage,
                                       Object... args) {
        logger.warn("[PUBMATIC] ReqID [{0}] PubID [{1}] AdUnit [{2}] BidID [{3}] {4}", requestId, publisherId,
                adUnitBid.getAdUnitCode(), adUnitBid.getBidId(), String.format(errorMessage, args));
    }

    private static List<Imp> makeImps(List<AdUnitBidWithParams<Params>> adUnitBidsWithParams,
                                      PreBidRequestContext preBidRequestContext) {
        return adUnitBidsWithParams.stream()
                .flatMap(adUnitBidWithParams -> makeImpsForAdUnitBid(adUnitBidWithParams, preBidRequestContext))
                .collect(Collectors.toList());
    }

    private static Stream<Imp> makeImpsForAdUnitBid(AdUnitBidWithParams<Params> adUnitBidWithParams,
                                                    PreBidRequestContext preBidRequestContext) {
        final AdUnitBid adUnitBid = adUnitBidWithParams.getAdUnitBid();
        final Params params = adUnitBidWithParams.getParams();

        return allowedMediaTypes(adUnitBid, ALLOWED_MEDIA_TYPES).stream()
                .map(mediaType -> impBuilderWithMedia(mediaType, adUnitBid, params)
                        .id(adUnitBid.getAdUnitCode())
                        .instl(adUnitBid.getInstl())
                        .secure(preBidRequestContext.getSecure())
                        .tagid(mediaType == MediaType.banner && params != null ? params.getTagId() : null)
                        .build());
    }

    private static Imp.ImpBuilder impBuilderWithMedia(MediaType mediaType, AdUnitBid adUnitBid, Params params) {
        final Imp.ImpBuilder impBuilder = Imp.builder();

        switch (mediaType) {
            case video:
                impBuilder.video(videoBuilder(adUnitBid).build());
                break;
            case banner:
                impBuilder.banner(makeBanner(adUnitBid, params));
                break;
            default:
                // unknown media type, just skip it
        }
        return impBuilder;
    }

    private static Banner makeBanner(AdUnitBid adUnitBid, Params params) {
        final List<Format> sizes = adUnitBid.getSizes();
        final Format format = sizes.get(0);
        return Banner.builder()
                .w(params != null ? params.getWidth() : format.getW())
                .h(params != null ? params.getHeight() : format.getH())
                .format(params != null ? null : sizes) // pubmatic doesn't support
                .topframe(adUnitBid.getTopframe())
                .build();
    }

    private static Publisher makePublisher(PreBidRequestContext preBidRequestContext,
                                           List<AdUnitBidWithParams<Params>> adUnitBidsWithParams) {
        return adUnitBidsWithParams.stream()
                .map(AdUnitBidWithParams::getParams)
                .filter(params -> params != null && params.getPublisherId() != null && params.getAdSlot() != null)
                .map(Params::getPublisherId)
                .reduce((first, second) -> second)
                .map(publisherId -> Publisher.builder()
                        .id(publisherId)
                        .domain(preBidRequestContext.getDomain())
                        .build())
                .orElse(null);
    }

    private static App makeApp(PreBidRequestContext preBidRequestContext, Publisher publisher) {
        final App app = preBidRequestContext.getPreBidRequest().getApp();
        return app == null ? null : app.toBuilder()
                .publisher(publisher)
                .build();
    }

    private static Site makeSite(PreBidRequestContext preBidRequestContext, Publisher publisher) {
        final Site.SiteBuilder siteBuilder = siteBuilder(preBidRequestContext);
        return siteBuilder == null ? null : siteBuilder
                .publisher(publisher)
                .build();
    }

    private String makeUserCookie(PreBidRequestContext preBidRequestContext) {
        final String cookieValue = preBidRequestContext.getUidsCookie().uidFrom(cookieFamily());
        return Cookie.cookie("KADUSERCOOKIE", ObjectUtils.firstNonNull(cookieValue, "")).encode();
    }

    @Override
    public List<Bid.BidBuilder> extractBids(Bidder bidder, ExchangeCall exchangeCall) {
        return responseBidStream(exchangeCall.getBidResponse())
                .map(bid -> toBidBuilder(bid, bidder))
                .collect(Collectors.toList());
    }

    private static Bid.BidBuilder toBidBuilder(com.iab.openrtb.response.Bid bid, Bidder bidder) {
        final AdUnitBid adUnitBid = lookupBid(bidder.getAdUnitBids(), bid.getImpid());
        return Bid.builder()
                .bidder(adUnitBid.getBidderCode())
                .bidId(adUnitBid.getBidId())
                .code(bid.getImpid())
                .price(bid.getPrice())
                .adm(bid.getAdm())
                .creativeId(bid.getCrid())
                .width(bid.getW())
                .height(bid.getH())
                .dealId(bid.getDealid());
    }

    @AllArgsConstructor(staticName = "of")
    @Value
    private static final class Params {

        String publisherId;

        String adSlot;

        String tagId;

        Integer width;

        Integer height;
    }
}