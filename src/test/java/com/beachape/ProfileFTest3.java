package com.beachape;

import com.beachape.profile.ProfileF;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileF.class)
class ProfileFTest3 {
    @Test
    void test() {}
}
