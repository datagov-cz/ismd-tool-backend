package cz.gov.dia.ismd.keycloak.nia;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.resources.IdentityBrokerService;
import org.keycloak.services.resources.RealmsResource;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * OIDC broker for NIA (Národní bod / Identita občana).
 *
 * <p>NIA deviates from what Keycloak's stock OIDC broker expects in four ways, all
 * confirmed against the NIA test environment:
 * <ul>
 *   <li>The id_token carries <b>no {@code sub}</b>. The stable citizen identifier is the
 *       eIDAS {@code PersonIdentifier} claim. Keycloak takes the brokered identity id
 *       from {@code idToken.getSubject()} and fails without it, so a missing subject is
 *       filled from {@code PersonIdentifier} right after the token is validated.</li>
 *   <li>{@code PersonIdentifier} contains '/', which Keycloak usernames may not. A
 *       {@code niaUsername} claim ('/' replaced by '-') is added for the username
 *       template mapper.</li>
 *   <li>Attributes (including {@code PersonIdentifier}) are only released when the
 *       authorize request carries a {@code claims} JSON parameter. It is read from the
 *       IdP config key {@code niaClaims} and appended to every authorize URL.</li>
 *   <li>A citizen who picks "Nechci poskytnout údaje" on NIA's consent screen gets a
 *       token with <b>no attributes at all</b>, {@code PersonIdentifier} included, while
 *       NIA's own session stays alive. Without handling, Keycloak shows a generic error
 *       and the next attempt silently lands on the consent screen again, with no way to
 *       pick another profile. Instead the browser is sent to NIA's end_session, and the
 *       return to logout_response shows why the login cannot proceed.</li>
 * </ul>
 *
 * <p>Everything else — JWE decryption with the realm's RSA-OAEP key, RS256 signature,
 * expiry, audience and issuer checks — is the stock implementation, untouched.
 * NIA's userinfo endpoint returns {@code {}}; configure the IdP with
 * {@code disableUserInfo=true} so the stock code does not overwrite the id from it.
 */
public class NiaOidcIdentityProvider extends OIDCIdentityProvider {

    private static final Logger LOG = Logger.getLogger(NiaOidcIdentityProvider.class);

    public NiaOidcIdentityProvider(KeycloakSession session, OIDCIdentityProviderConfig config) {
        super(session, config);
    }

    @Override
    protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
        UriBuilder uriBuilder = super.createAuthorizationUrl(request);
        String claims = getConfig().getConfig().get(NiaClaims.CLAIMS_CONFIG_KEY);
        if (claims != null && !claims.isBlank()) {
            uriBuilder.queryParam(NiaClaims.CLAIMS_PARAM, NiaClaims.encodeClaims(claims));
        }
        return uriBuilder;
    }

    @Override
    protected JsonWebToken validateToken(String encodedToken, boolean ignoreAudience) {
        JsonWebToken token = super.validateToken(encodedToken, ignoreAudience);
        if (token.getSubject() == null || token.getSubject().isBlank()) {
            if (!NiaClaims.hasPersonIdentifier(token.getOtherClaims())) {
                throw identifierRefused();
            }
            token.setSubject(NiaClaims.subjectFrom(token.getOtherClaims()));
        }
        // Exposed as a claim so the username template mapper can use ${CLAIM.niaUsername}
        // — its templates cannot rewrite characters themselves.
        Object personIdentifier = token.getOtherClaims().get(NiaClaims.PERSON_IDENTIFIER_CLAIM);
        if (personIdentifier != null && !personIdentifier.toString().isBlank()) {
            token.getOtherClaims().putIfAbsent(NiaClaims.USERNAME_CLAIM,
                    NiaClaims.usernameFrom(personIdentifier.toString()));
        }
        return token;
    }

    /**
     * NIA released no identifier. Ends NIA's session by sending the browser to its
     * end_session with client_id only (NIA rejects any id_token_hint it cannot read);
     * NIA returns to this IdP's logout_response with the refused-state marker.
     *
     * <p>Thrown rather than returned: this runs inside the stock callback, whose
     * {@code catch (WebApplicationException e)} returns {@code e.getResponse()} as-is.
     * Without a logout URL there is nothing to end, so the explanatory page is shown
     * directly through the stock error path (IdentityBrokerException message code).
     */
    private RuntimeException identifierRefused() {
        String logoutUrl = getConfig().getLogoutUrl();
        if (logoutUrl == null || logoutUrl.isBlank()) {
            LOG.info("NIA released no PersonIdentifier; no logout URL configured, showing the refusal page");
            return NiaClaims.refused();
        }

        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        String clientId = authSession == null ? null : authSession.getClient().getClientId();
        UriInfo uriInfo = session.getContext().getUri();
        RealmModel realm = session.getContext().getRealm();

        String postLogout = RealmsResource.brokerUrl(uriInfo)
                .path(IdentityBrokerService.class, "getEndpoint")
                .path(OIDCEndpoint.class, "logoutResponse")
                .build(realm.getName(), getConfig().getAlias())
                .toString();

        Response redirect = Response.status(Response.Status.FOUND)
                .location(UriBuilder.fromUri(logoutUrl)
                        .queryParam("client_id", getConfig().getClientId())
                        .queryParam("post_logout_redirect_uri", postLogout)
                        .queryParam("state", NiaClaims.refusedState(clientId))
                        .build())
                .build();

        LOG.infof("NIA released no PersonIdentifier (consent refused); ending the NIA session, client=%s", clientId);
        return new WebApplicationException(redirect);
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new NiaEndpoint(callback, realm, event, this);
    }

    /** Stock OIDC endpoint, plus the "no identifier" page on the return from NIA's logout. */
    protected static class NiaEndpoint extends OIDCEndpoint {

        public NiaEndpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event,
                           NiaOidcIdentityProvider provider) {
            super(callback, realm, event, provider);
        }

        // JAX-RS annotations restated: the path must stay logout_response, which is also
        // what NIA has registered as this SeP's signout URL.
        @GET
        @Path("logout_response")
        @Override
        public Response logoutResponse(@QueryParam("state") String state) {
            if (!NiaClaims.isRefusedState(state)) {
                return super.logoutResponse(state);
            }
            // The error page links back to the context client's base URL, if any.
            String clientId = NiaClaims.clientIdFromRefusedState(state);
            ClientModel client = clientId == null ? null : realm.getClientByClientId(clientId);
            if (client != null && client.isEnabled()) {
                session.getContext().setClient(client);
            }
            return ErrorPage.error(session, null, Response.Status.FORBIDDEN,
                    NiaClaims.IDENTIFIER_REFUSED_MESSAGE);
        }
    }
}
