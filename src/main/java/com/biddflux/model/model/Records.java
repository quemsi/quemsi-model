package com.biddflux.model.model;

import java.time.LocalDateTime;

public class Records {
   public record SitemapItem(String loc, LocalDateTime lastmod) {
    }
}
