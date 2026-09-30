package cz.gov.dia.ismd.keycloak.nia;

import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

/**
 * Registers {@link NiaOidcIdentityProvider} under provider id {@value #NIA_PROVIDER_ID}.
 * Config parsing, discovery import and the admin representation are inherited from the
 * stock OIDC factory, so the IdP is configured exactly like an "oidc" one.
 */
public class NiaOidcIdentityProviderFactory extends OIDCIdentityProviderFactory {

    public static final String NIA_PROVIDER_ID = "nia-oidc";

    @Override
    public String getId() {
        return NIA_PROVIDER_ID;
    }

    @Override
    public String getName() {
        return "NIA (OpenID Connect)";
    }

    @Override
    public NiaOidcIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new NiaOidcIdentityProvider(session, new OIDCIdentityProviderConfig(model));
    }
}
