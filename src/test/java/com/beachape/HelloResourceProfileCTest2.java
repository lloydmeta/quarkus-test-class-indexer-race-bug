package com.beachape;

import static io.restassured.RestAssured.given;

import com.beachape.profile.ProfileC;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileC.class)
class HelloResourceProfileCTest2 {

    @Test
    void hello() {
        given().when().get("/hello").then().statusCode(200);
    }
}
