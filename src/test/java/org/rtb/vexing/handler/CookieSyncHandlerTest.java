package org.rtb.vexing.handler;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.AsciiString;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.rtb.vexing.VertxTest;
import org.rtb.vexing.adapter.Adapter;
import org.rtb.vexing.adapter.AdapterCatalog;
import org.rtb.vexing.cookie.UidsCookie;
import org.rtb.vexing.cookie.UidsCookieFactory;
import org.rtb.vexing.metric.MetricName;
import org.rtb.vexing.metric.Metrics;
import org.rtb.vexing.model.Uids;
import org.rtb.vexing.model.request.CookieSyncRequest;
import org.rtb.vexing.model.response.BidderStatus;
import org.rtb.vexing.model.response.CookieSyncResponse;
import org.rtb.vexing.model.response.UsersyncInfo;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

public class CookieSyncHandlerTest extends VertxTest {

    private static final String RUBICON = "rubicon";
    private static final String APPNEXUS = "appnexus";
    private static final String ADNXS = "adnxs";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private UidsCookieFactory uidsCookieFactory;
    @Mock
    private AdapterCatalog adapterCatalog;
    @Mock
    private Adapter rubiconAdapter;
    @Mock
    private Adapter appnexusAdapter;
    @Mock
    private Metrics metrics;
    private CookieSyncHandler cookieSyncHandler;

    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerResponse httpResponse;

    @Before
    public void setUp() {
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().build()));

        given(routingContext.response()).willReturn(httpResponse);
        given(httpResponse.putHeader(any(CharSequence.class), any(CharSequence.class))).willReturn(httpResponse);

        cookieSyncHandler = new CookieSyncHandler(uidsCookieFactory, adapterCatalog, metrics);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new CookieSyncHandler(null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new CookieSyncHandler(uidsCookieFactory, null, null));
        assertThatNullPointerException().isThrownBy(
                () -> new CookieSyncHandler(uidsCookieFactory, adapterCatalog, null));
    }

    @Test
    public void shouldRespondWithErrorIfOptedOut() {
        // given
        given(uidsCookieFactory.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(401));
        verify(httpResponse).setStatusMessage(eq("User has opted out"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, adapterCatalog);
    }

    @Test
    public void shouldRespondWithErrorIfRequestBodyCouldNotBeParsed() {
        // given
        given(routingContext.getBodyAsJson())
                .willThrow(new DecodeException("Could not parse", new JsonParseException(null, (String) null)));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).setStatusMessage(eq("JSON parse failed"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, adapterCatalog);
    }

    @Test
    public void shouldRespondWithErrorIfRequestBodyIsMissing() {
        // given
        given(routingContext.getBodyAsJson()).willReturn(null);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, adapterCatalog);
    }

    @Test
    public void shouldRespondWithExpectedHeaders() {
        // given
        given(routingContext.getBodyAsJson())
                .willReturn(JsonObject.mapFrom(CookieSyncRequest.builder().bidders(emptyList()).build()));

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        verify(httpResponse)
                .putHeader(eq(new AsciiString("Content-Type")), eq(new AsciiString("application/json")));
    }

    @Test
    public void shouldRespondWithSomeBidderStatusesIfSomeUidsMissingInCookies() throws IOException {
        // given
        final Map<String, String> uids = new HashMap<>();
        uids.put(RUBICON, "J5VLCWQP-26-CWFT");
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.builder().uuid("uuid").bidders(Arrays.asList(RUBICON, APPNEXUS)).build()));

        givenAdaptersReturningFamilyName();

        final ObjectNode appnexusUsersyncInfo = defaultNamingMapper.valueToTree(UsersyncInfo.builder()
                .url("http://adnxsexample.com")
                .type("redirect")
                .supportCORS(false)
                .build());
        given(appnexusAdapter.usersyncInfo()).willReturn(appnexusUsersyncInfo);

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.builder()
                .uuid("uuid")
                .status("no_cookie")
                .bidderStatus(singletonList(BidderStatus.builder()
                        .bidder(APPNEXUS)
                        .noCookie(true)
                        .usersync(appnexusUsersyncInfo)
                        .build()))
                .build());
    }

    @Test
    public void shouldRespondWithNoBidderStatusesIfAllUidsPresentInCookies() throws IOException {
        // given
        final Map<String, String> uids = new HashMap<>();
        uids.put(RUBICON, "J5VLCWQP-26-CWFT");
        uids.put(ADNXS, "12345");
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.builder().uuid("uuid").bidders(Arrays.asList(RUBICON, APPNEXUS)).build()));

        givenAdaptersReturningFamilyName();

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.builder()
                .uuid("uuid")
                .status("no_cookie")
                .bidderStatus(emptyList())
                .build());
    }

    @Test
    public void shouldTolerateUnsupportedBidder() throws IOException {
        // given
        final Map<String, String> uids = new HashMap<>();
        uids.put(RUBICON, "J5VLCWQP-26-CWFT");
        uids.put(ADNXS, "12345");
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(routingContext.getBodyAsJson()).willReturn(JsonObject.mapFrom(
                CookieSyncRequest.builder().uuid("uuid").bidders(Arrays.asList(RUBICON, "unsupported")).build()));

        givenAdaptersReturningFamilyName();

        given(adapterCatalog.isValidCode("unsupported")).willReturn(false);

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.builder()
                .uuid("uuid")
                .status("no_cookie")
                .bidderStatus(emptyList())
                .build());
    }

    @Test
    public void shouldIncrementMetrics() {
        // given
        given(routingContext.getBodyAsJson())
                .willReturn(JsonObject.mapFrom(CookieSyncRequest.builder().bidders(emptyList()).build()));

        // when
        cookieSyncHandler.sync(routingContext);

        // then
        verify(metrics).incCounter(eq(MetricName.cookie_sync_requests));
    }

    private void givenAdaptersReturningFamilyName() {
        given(adapterCatalog.getByCode(eq(RUBICON))).willReturn(rubiconAdapter);
        given(adapterCatalog.isValidCode(eq(RUBICON))).willReturn(true);
        given(adapterCatalog.getByCode(eq(APPNEXUS))).willReturn(appnexusAdapter);
        given(adapterCatalog.isValidCode(eq(APPNEXUS))).willReturn(true);

        given(rubiconAdapter.cookieFamily()).willReturn(RUBICON);
        given(rubiconAdapter.code()).willReturn(RUBICON);
        given(appnexusAdapter.cookieFamily()).willReturn(ADNXS);
        given(appnexusAdapter.code()).willReturn(APPNEXUS);
    }

    private CookieSyncResponse captureCookieSyncResponse() throws IOException {
        final ArgumentCaptor<String> cookieSyncResponseCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpResponse).end(cookieSyncResponseCaptor.capture());
        return mapper.readValue(cookieSyncResponseCaptor.getValue(), CookieSyncResponse.class);
    }
}