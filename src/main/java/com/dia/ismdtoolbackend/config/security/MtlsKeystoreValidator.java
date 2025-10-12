package com.dia.ismdtoolbackend.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/**
 * Validator for CAAIS mTLS keystore configuration.
 * Validates keystore on application startup to catch configuration errors early.
 */
@Slf4j
@Component
public class MtlsKeystoreValidator {

    @Value("${caais.mtls.keystore.path}")
    private Resource keystorePath;

    @Value("${caais.mtls.keystore.password}")
    private String keystorePassword;

    @Value("${caais.mtls.keystore.type:PKCS12}")
    private String keystoreType;

    @Value("${caais.mtls.keystore.alias}")
    private String keystoreAlias;

    /**
     * Validates mTLS keystore configuration on application startup.
     * Logs certificate details and potential issues.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateKeystore() {
        log.info("Validating CAAIS mTLS keystore configuration...");

        try {
            // Load keystore
            KeyStore keyStore = KeyStore.getInstance(keystoreType);

            if (!keystorePath.exists()) {
                log.error("❌ Keystore file not found: {}", keystorePath.getDescription());
                return;
            }

            try (InputStream is = keystorePath.getInputStream()) {
                keyStore.load(is, keystorePassword.toCharArray());
            }

            // Validate alias exists
            if (!keyStore.containsAlias(keystoreAlias)) {
                log.error("❌ Keystore does not contain alias: {}", keystoreAlias);
                log.info("Available aliases: {}", listAliases(keyStore));
                return;
            }

            // Get certificate
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(keystoreAlias);
            if (certificate == null) {
                log.error("❌ No certificate found for alias: {}", keystoreAlias);
                return;
            }

            // Validate private key exists
            if (!keyStore.isKeyEntry(keystoreAlias)) {
                log.error("❌ No private key found for alias: {}", keystoreAlias);
                return;
            }

            // Log certificate details
            log.info("✅ Keystore loaded successfully");
            log.info("📜 Certificate Details:");
            log.info("   Subject: {}", certificate.getSubjectX500Principal().getName());
            log.info("   Issuer: {}", certificate.getIssuerX500Principal().getName());
            log.info("   Valid From: {}", certificate.getNotBefore());
            log.info("   Valid Until: {}", certificate.getNotAfter());
            log.info("   Serial Number: {}", certificate.getSerialNumber());

            // Validate certificate expiry
            certificate.checkValidity();
            log.info("✅ Certificate is valid (not expired)");

            // Validate SSL Context can be created
            validateSslContext(keyStore);

        } catch (Exception e) {
            log.error("❌ Failed to validate mTLS keystore: {}", e.getMessage(), e);
        }
    }

    /**
     * Validates that SSLContext can be created with the keystore.
     * This ensures the keystore can be used for mTLS connections.
     */
    private void validateSslContext(KeyStore keyStore) {
        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null); // Use default trust store

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            log.info("✅ SSL Context created successfully - mTLS configuration is valid");
        } catch (Exception e) {
            log.error("❌ Failed to create SSL Context: {}", e.getMessage(), e);
        }
    }

    /**
     * Lists all aliases in the keystore for debugging purposes.
     */
    private String listAliases(KeyStore keyStore) {
        try {
            StringBuilder aliases = new StringBuilder();
            Enumeration<String> aliasEnum = keyStore.aliases();
            while (aliasEnum.hasMoreElements()) {
                aliases.append(aliasEnum.nextElement()).append(", ");
            }
            return aliases.toString();
        } catch (Exception e) {
            return "Error listing aliases: " + e.getMessage();
        }
    }
}
