package com.beachape;

import com.beachape.profile.ProfileG;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileG.class)
class ProfileGTest4 {
    @Test
    void test() {}
}
