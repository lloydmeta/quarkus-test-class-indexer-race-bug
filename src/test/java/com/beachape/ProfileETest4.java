package com.beachape;

import com.beachape.profile.ProfileE;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileE.class)
class ProfileETest4 {
    @Test
    void test() {}
}
