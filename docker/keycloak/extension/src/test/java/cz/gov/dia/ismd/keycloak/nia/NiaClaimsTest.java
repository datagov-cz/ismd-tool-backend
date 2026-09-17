package cz.gov.dia.ismd.keycloak.nia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.IdentityBrokerException;

class NiaClaimsTest {

    @Test
    void subjectIsTakenFromPersonIdentifier() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("PersonIdentifier", "CZ/CZ/abc-123");
        claims.put("CurrentGivenName", "Jan");

        assertEquals("CZ/CZ/abc-123", NiaClaims.subjectFrom(claims));
    }

    @Test
    void missingPersonIdentifierFailsWithBrokerException() {
        Map<String, Object> claims = Map.of("CurrentGivenName", "Jan");

        assertThrows(IdentityBrokerException.class, () -> NiaClaims.subjectFrom(claims));
    }

    @Test
    void blankPersonIdentifierFailsWithBrokerException() {
        Map<String, Object> claims = Map.of("PersonIdentifier", "  ");

        assertThrows(IdentityBrokerException.class, () -> NiaClaims.subjectFrom(claims));
    }

    @Test
    void nullClaimsFailWithBrokerException() {
        assertThrows(IdentityBrokerException.class, () -> NiaClaims.subjectFrom(null));
    }

    @Test
    void usernameReplacesSlashesOnly() {
        assertEquals("CZ-CZ-ede5bc65-36ad-416c-ac39-6fdb7db11160",
                NiaClaims.usernameFrom("CZ/CZ/ede5bc65-36ad-416c-ac39-6fdb7db11160"));
    }

    @Test
    void usernameWithoutSlashesIsUnchanged() {
        assertEquals("abc-123", NiaClaims.usernameFrom("abc-123"));
    }

    @Test
    void claimsAreEncodedWithoutTemplateBracesAndRoundTrip() {
        String json = "{\"id_token\":{\"PersonIdentifier\":null,\"CurrentGivenName\":null,\"CurrentFamilyName\":null}}";

        String encoded = NiaClaims.encodeClaims(json);

        assertFalse(encoded.contains("{") || encoded.contains("}"), "raw braces would be read as a URI template");
        assertFalse(encoded.contains("+"), "spaces must not be encoded as '+'");
        assertEquals(json, URLDecoder.decode(encoded, StandardCharsets.UTF_8));
    }
}
