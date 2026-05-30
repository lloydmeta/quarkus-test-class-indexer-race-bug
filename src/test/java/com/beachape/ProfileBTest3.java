package com.beachape;

import com.beachape.profile.ProfileB;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileB.class)
class ProfileBTest3 {
    @Test
    void test() {}
}
