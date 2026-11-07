package com.metroticket_api.config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình cache trong bộ nhớ cho dữ liệu ngoài với TTL rõ ràng để tránh cache "ở lại mãi".
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        Map<String, Duration> ttlByName = Map.of(
                "facility-nearby", Duration.ofMinutes(60),
                "bus-nearby", Duration.ofMinutes(15),
                "bus-routes", Duration.ofMinutes(30));
        return new ExpiringCacheManager(ttlByName);
    }

    private static final class ExpiringCacheManager implements CacheManager {
        private final Map<String, ExpiringCache> caches = new ConcurrentHashMap<>();
        private final Map<String, Duration> ttlByName;

        private ExpiringCacheManager(Map<String, Duration> ttlByName) {
            this.ttlByName = ttlByName;
        }

        @Override
        public Cache getCache(String name) {
            return caches.computeIfAbsent(name,
                    key -> new ExpiringCache(key, ttlByName.getOrDefault(key, Duration.ofMinutes(30))));
        }

        @Override
        public Collection<String> getCacheNames() {
            return new ArrayList<>(ttlByName.keySet());
        }
    }

    private static final class ExpiringCache implements Cache {
        private final String name;
        private final Duration ttl;
        private static final int MAX_ENTRIES = 5_000;
        private final ConcurrentMap<Object, CacheEntry> entries = new ConcurrentHashMap<>();

        private ExpiringCache(String name, Duration ttl) {
            this.name = name;
            this.ttl = ttl;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return entries;
        }

        @Override
        public ValueWrapper get(Object key) {
            CacheEntry entry = entries.get(key);
            if (entry == null || entry.expired()) {
                entries.remove(key);
                return null;
            }
            return new ValueWrapperImpl(entry.value());
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            ValueWrapper wrapper = get(key);
            if (wrapper == null) {
                return null;
            }
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            return type.cast(value);
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            T value = get(key, (Class<T>) Object.class);
            if (value != null) {
                return value;
            }
            try {
                T loaded = valueLoader.call();
                put(key, loaded);
                return loaded;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load cache value for key: " + key, ex);
            }
        }

        @Override
        public void put(Object key, Object value) {
            ensureCapacity();
            entries.put(key, new CacheEntry(value, Instant.now().plus(ttl)));
        }

        /**
         * Giới hạn số phần tử: khoá cache có thể đến từ tham số client, không giới hạn thì spam request làm tràn heap.
         * Đầy thì dọn phần tử hết hạn trước, vẫn đầy thì bỏ phần tử sắp hết hạn nhất.
         */
        private void ensureCapacity() {
            if (entries.size() < MAX_ENTRIES) {
                return;
            }
            entries.entrySet().removeIf(e -> e.getValue().expired());
            while (entries.size() >= MAX_ENTRIES) {
                entries.entrySet().stream()
                        .min(java.util.Comparator.comparing(e -> e.getValue().expiresAt()))
                        .ifPresent(oldest -> entries.remove(oldest.getKey()));
            }
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            CacheEntry existing = entries.get(key);
            if (existing != null && !existing.expired()) {
                return new ValueWrapperImpl(existing.value());
            }
            CacheEntry newEntry = new CacheEntry(value, Instant.now().plus(ttl));
            ensureCapacity();
            entries.put(key, newEntry);
            return new ValueWrapperImpl(value);
        }

        @Override
        public void evict(Object key) {
            entries.remove(key);
        }

        @Override
        public void clear() {
            entries.clear();
        }

        @Override
        public String toString() {
            return "ExpiringCache[name=" + name + ", ttl=" + ttl + ", size=" + entries.size() + "]";
        }
    }

    private record CacheEntry(Object value, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final class ValueWrapperImpl implements Cache.ValueWrapper {
        private final Object value;

        private ValueWrapperImpl(Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }
    }
}
