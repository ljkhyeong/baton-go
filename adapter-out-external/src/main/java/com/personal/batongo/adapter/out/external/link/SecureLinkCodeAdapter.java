package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecureLinkCodeAdapter implements LinkCodePort {

    private static final int CODE_BYTES = 16;

    private static final String DERIVATION_VERSION = "hmac-sha256-link-code-v1";
    private static final byte[] DERIVATION_CONTEXT =
            "baton-go-link-code:v1\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FINGERPRINT_CONTEXT =
            "baton-go-link-code-key-fingerprint:v1\u0000"
                    .getBytes(StandardCharsets.US_ASCII);

    private final Map<String, String> keys;
    private final LinkCodeKeyRingIdentity keyRingIdentity;

    public SecureLinkCodeAdapter(LinkCodeProperties properties) {
        this.keys = properties.keys();
        this.keyRingIdentity = new LinkCodeKeyRingIdentity(
                properties.activeKeyId(),
                keys.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> new LinkCodeDerivationIdentity(
                                DERIVATION_VERSION,
                                HexFormat.of().formatHex(hmac(entry.getValue(), FINGERPRINT_CONTEXT))
                        )
                ))
        );
    }

    @Override
    public LinkCodeKeyRingIdentity keyRingIdentity() {
        return keyRingIdentity;
    }

    @Override
    public String issue(String idempotencyKey, String keyId) {
        String secret = keys.get(keyId);
        if (secret == null) {
            throw new LinkCodeReplayMismatchException();
        }
        byte[] digest = hmac(
                secret,
                DERIVATION_CONTEXT,
                idempotencyKey.getBytes(StandardCharsets.US_ASCII)
        );
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Arrays.copyOf(digest, CODE_BYTES));
    }

    private byte[] hmac(String secret, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // 비밀값은 정규화·공백 제거 없이 UTF-8 바이트 그대로 쓴다.
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
