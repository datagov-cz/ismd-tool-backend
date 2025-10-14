package com.dia.ismdtoolbackend.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import javax.net.ssl.*;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Date;

/**
 * Configuration for mTLS (Mutual TLS) authentication with CAAIS OAuth2 token endpoint.
 * Loads PKCS12 keystore containing client certificate and configures SSL context.
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "caais.mtls")
public class MtlsConfig {

    @Value("${keystore.path}")
    private Resource keystorePath;

    @Value("${keystore.password}")
    private String keystorePassword;

    @Value("${keystore.type}")
    private String keystoreType;

    @Value("${keystore.alias}")
    private String keystoreAlias;

    @Value("${connect-timeout-second}")
    private int connectionTimeoutSeconds;

    private SSLContext sslContext;

    /**
     * Validates and initializes mTLS keystore at application startup.
     * Logs keystore status, certificate validity, and SSL context initialization.
     */
    @PostConstruct
    public void validateKeystoreConfiguration() {
        try {
            log.info("Validating CAAIS mTLS keystore configuration...");
            log.info("Keystore path: {}", keystorePath.getDescription());
            log.info("Keystore type: {}", keystoreType);
            log.info("Keystore alias: {}", keystoreAlias);

            // Check if keystore file exists
            if (!keystorePath.exists()) {
                throw new IllegalStateException("Keystore file does not exist: " + keystorePath.getDescription());
            }

            KeyStore keyStore = loadKeyStore();
            log.info("Keystore loaded successfully from: {}", keystorePath.getDescription());

            // List all aliases for debugging
            listAliases(keyStore);

            validateCertificate(keyStore);

            this.sslContext = createSslContext(keyStore);
            log.info("SSL Context created successfully - mTLS configuration is valid");

        } catch (Exception e) {
            log.error("Failed to validate mTLS keystore configuration", e);
            throw new IllegalStateException("mTLS keystore configuration is invalid", e);
        }
    }

    /**
     * Creates OAuth2 token response client configured with mTLS.
     * This client is used to exchange authorization code for access token.
     *
     * @return configured OAuth2AccessTokenResponseClient
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        log.info("Configuring mTLS RestClient for CAAIS token endpoint...");

        // Create HttpClient with mTLS SSL context
        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(connectionTimeoutSeconds))
                .build();

        // Create JdkClientHttpRequestFactory with mTLS HttpClient
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        // Build RestClient with mTLS configuration
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();

        // Create token response client with mTLS RestClient
        RestClientAuthorizationCodeTokenResponseClient tokenResponseClient =
                new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.setRestClient(restClient);

        log.info("mTLS RestClient configured successfully");
        return tokenResponseClient;
    }

    /**
     * Loads PKCS12 keystore from configured path.
     */
    private KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(keystoreType);


        try (InputStream keystoreStream = keystorePath.getInputStream()) {
            keyStore.load(keystoreStream, keystorePassword.toCharArray());
        }

        if (!keyStore.isKeyEntry(keystoreAlias)) {
            throw new IllegalStateException("No private key found for alias: " + keystoreAlias);
        }

        return keyStore;
    }

    /**
     * Validates certificate from keystore (checks existence and expiry).
     */
    private void validateCertificate(KeyStore keyStore) throws Exception {
        if (!keyStore.containsAlias(keystoreAlias)) {
            throw new IllegalStateException("Keystore does not contain alias: " + keystoreAlias);
        }

        Certificate cert = keyStore.getCertificate(keystoreAlias);
        if (cert == null) {
            throw new IllegalStateException("No certificate found for alias: " + keystoreAlias);
        }

        log.info("Certificate and private key found for alias: {}", keystoreAlias);

        if (cert instanceof X509Certificate) {
            X509Certificate x509Cert = (X509Certificate) cert;

            // Log detailed certificate information
            log.info("Certificate Subject: {}", x509Cert.getSubjectX500Principal().getName());
            log.info("Certificate Issuer: {}", x509Cert.getIssuerX500Principal().getName());
            log.info("Certificate Serial Number: {}", x509Cert.getSerialNumber().toString(16));
            log.info("Certificate Valid From: {}", x509Cert.getNotBefore());
            log.info("Certificate Valid Until: {}", x509Cert.getNotAfter());

            Date notAfter = x509Cert.getNotAfter();
            Date now = new Date();

            if (now.after(notAfter)) {
                log.error("Certificate expired on: {}", notAfter);
                throw new IllegalStateException("Certificate has expired");
            }

            log.info("Certificate is valid and will expire on: {}", notAfter);
        }
    }

    /**
     * Creates SSL context with loaded keystore and default trust managers.
     */
    private SSLContext createSslContext(KeyStore keyStore) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return sslContext;
    }

    /**
     * Lists all aliases in the keystore for debugging purposes.
     */
    private void listAliases(KeyStore keyStore) throws Exception {
        var aliases = keyStore.aliases();
        log.info("Keystore contains the following aliases:");
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            log.info("  - {}", alias);
        }
    }
}
