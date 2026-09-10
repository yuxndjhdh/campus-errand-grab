package com.yu.errand.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class PingController {

    private final String redisPrefilterEnabled;

    public PingController(@Value("${app.grab.redis-prefilter-enabled}") String redisPrefilterEnabled) {
        this.redisPrefilterEnabled = redisPrefilterEnabled;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "UP",
                "app", "campus-errand-grab",
                "redisPrefilterEnabled", redisPrefilterEnabled
        );
    }
}
