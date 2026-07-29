package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.error.InvalidLinkCodeException;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SecureLinkCodeAdapter implements LinkCodePort {

    static final int CODE_BYTES = 16;
    static final int CODE_LENGTH = 22;

    private static final Pattern RAW_CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");

    private final SecureRandom secureRandom;

    public SecureLinkCodeAdapter() {
        this(new SecureRandom());
    }

    SecureLinkCodeAdapter(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public IssuedLinkCode issue() {
        byte[] bytes = new byte[CODE_BYTES];
        secureRandom.nextBytes(bytes);
        String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedLinkCode(rawCode, hash(rawCode));
    }

    @Override
    public String hash(String rawCode) {
        if (rawCode == null || !RAW_CODE.matcher(rawCode).matches()) {
            throw new InvalidLinkCodeException();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawCode.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
