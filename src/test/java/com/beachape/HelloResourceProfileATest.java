package com.beachape;

import static io.restassured.RestAssured.given;

import com.beachape.profile.ProfileA;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProfileA.class)
class HelloResourceProfileATest {

    @Test
    void hello() {
        given().when().get("/hello").then().statusCode(200);
    }
}
