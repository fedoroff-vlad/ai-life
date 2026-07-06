package dev.fedorov.ailife.profile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the profile-service (core schema: users, household, people, identity resolution). */
@SpringBootApplication
public class ProfileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProfileServiceApplication.class, args);
    }
}
