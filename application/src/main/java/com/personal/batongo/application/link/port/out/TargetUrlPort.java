package com.personal.batongo.application.link.port.out;

import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;

public interface TargetUrlPort {

    URI resolve(TargetSystem targetSystem, String targetPath);
}
