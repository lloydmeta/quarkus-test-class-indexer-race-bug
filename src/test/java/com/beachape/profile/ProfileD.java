package com.beachape.profile;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class ProfileD implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("repro.profile-name", "D");
    }
}
