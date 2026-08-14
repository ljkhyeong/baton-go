package com.personal.batongo.application.link.port.out;

import com.personal.batongo.domain.link.TrustedTarget;
import java.net.URI;

public interface TargetUrlPort {

    URI resolve(TrustedTarget target);
}
