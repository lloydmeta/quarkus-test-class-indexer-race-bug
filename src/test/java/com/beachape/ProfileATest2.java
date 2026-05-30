package com.beachape;

import com.beachape.profile.ProfileA;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileA.class)
class ProfileATest2 {
    @Test
    void test() {}
}
