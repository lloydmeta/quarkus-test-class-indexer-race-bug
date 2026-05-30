package com.beachape;

import com.beachape.profile.ProfileD;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileD.class)
class ProfileDTest1 {
    @Test
    void test() {}
}
