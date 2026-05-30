package com.beachape;

import com.beachape.profile.ProfileH;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileH.class)
class ProfileHTest1 {
    @Test
    void test() {}
}
