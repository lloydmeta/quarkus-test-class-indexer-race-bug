package com.beachape;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/hello")
public class HelloResource {

    @ConfigProperty(name = "repro.profile-name", defaultValue = "default")
    String profileName;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "hello from " + profileName;
    }
}
