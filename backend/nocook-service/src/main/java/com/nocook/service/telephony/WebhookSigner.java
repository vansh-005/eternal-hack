package com.nocook.service.telephony;

import com.nocook.service.config.WorkerProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 over the raw body bytes, hex encoded; verification is constant time. */
@Component
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-Mock-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public WebhookSigner(WorkerProperties.Telephony telephony) {
        this.secret = telephony.secret().getBytes(StandardCharsets.UTF_8);
    }

    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    public boolean verify(byte[] rawBody, @Nullable String signatureHex) {
        if (signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signatureHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
