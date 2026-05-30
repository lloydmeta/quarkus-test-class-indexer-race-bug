package com.beachape;

import com.beachape.profile.ProfileC;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileC.class)
class ProfileCTest3 {
    @Test
    void test() {}
}
