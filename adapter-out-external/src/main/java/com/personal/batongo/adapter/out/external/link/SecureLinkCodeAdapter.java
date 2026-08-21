package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecureLinkCodeAdapter implements LinkCodePort {

    private static final int CODE_BYTES = 16;

    private static final String DERIVATION_VERSION = "hmac-sha256-link-code-v1";
    private static final Pattern RAW_CODE = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final byte[] DERIVATION_CONTEXT =
            "baton-go-link-code:v1\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FINGERPRINT_CONTEXT =
            "baton-go-link-code-key-fingerprint:v1\u0000"
                    .getBytes(StandardCharsets.US_ASCII);

    private final byte[] secret;
    private final LinkCodeDerivationIdentity derivationIdentity;

    public SecureLinkCodeAdapter(LinkCodeProperties properties) {
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        this.derivationIdentity = new LinkCodeDerivationIdentity(
                DERIVATION_VERSION,
                HexFormat.of().formatHex(hmac(FINGERPRINT_CONTEXT))
        );
    }

    @Override
    public LinkCodeDerivationIdentity derivationIdentity() {
        return derivationIdentity;
    }

    @Override
    public IssuedLinkCode issue(String idempotencyKey) {
        byte[] digest = hmac(
                DERIVATION_CONTEXT,
                idempotencyKey.getBytes(StandardCharsets.US_ASCII)
        );
        String rawCode = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Arrays.copyOf(digest, CODE_BYTES));
        return new IssuedLinkCode(rawCode, sha256(rawCode));
    }

    @Override
    public String hash(String rawCode) {
        if (rawCode == null || !RAW_CODE.matcher(rawCode).matches()) {
            throw new LinkNotFoundException();
        }
        return sha256(rawCode);
    }

    @Override
    public String hashIdempotencyKey(String idempotencyKey) {
        return sha256(idempotencyKey);
    }

    private byte[] hmac(byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
