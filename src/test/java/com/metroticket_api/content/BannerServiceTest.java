package com.metroticket_api.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.content.ObjectStorage.StoredObject;

import tools.jackson.databind.ObjectMapper;

class BannerServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};

    private final Map<String, StoredObject> objects = new HashMap<>();
    private BannerService service;

    private static class MemoryStorage implements ObjectStorage {
        private final Map<String, StoredObject> objects;

        MemoryStorage(Map<String, StoredObject> objects) {
            this.objects = objects;
        }

        @Override
        public void put(String key, byte[] content, String contentType) {
            objects.put(key, new StoredObject(content, contentType));
        }

        @Override
        public Optional<StoredObject> get(String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static BannerService serviceWith(ObjectStorage storage) {
        ObjectProvider<ObjectStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        return new BannerService(provider, new ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        service = serviceWith(new MemoryStorage(objects));
    }

    @Test
    void addedBannerIsListedInPositionOrderWithItsImage() {
        var second = service.add(PNG, "Sau", null, null, 2);
        var first = service.add(PNG, "Trước", "Mô tả", "/p/news", 1);

        assertThat(service.list()).extracting(BannerService.Banner::id).containsExactly(first.id(), second.id());
        assertThat(service.image(first.id())).get()
                .extracting(StoredObject::contentType).isEqualTo("image/png");
    }

    @Test
    void imageIsLookedUpOnlyFromListedIds() {
        objects.put("banners/secret", new StoredObject(PNG, "image/png"));
        assertThat(service.image("secret")).isEmpty();
        assertThat(service.image("../manifest.json")).isEmpty();
    }

    @Test
    void rejectsFilesThatAreNotImagesWhateverTheyClaim() {
        assertThatThrownBy(() -> service.add("<svg></svg>".getBytes(), "T", null, null, 0))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.add(new byte[BannerService.MAX_BYTES + 1], "T", null, null, 0))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(objects).isEmpty();
    }

    @Test
    void rejectsExternalLinks() {
        assertThatThrownBy(() -> service.add(PNG, "T", null, "https://evil.example", 0))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(objects).isEmpty();
    }

    @Test
    void deleteRemovesImageAndEntry() {
        var banner = service.add(PNG, "T", null, null, 0);
        service.delete(banner.id());
        assertThat(service.list()).isEmpty();
        assertThat(objects).doesNotContainKey("banners/" + banner.id());
    }

    @Test
    void withoutStorageListIsEmptyAndWritesAreRefused() {
        BannerService none = serviceWith(null);
        assertThat(none.list()).isEmpty();
        assertThatThrownBy(() -> none.add(PNG, "T", null, null, 0))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void brokenStorageDoesNotBreakListing() {
        ObjectStorage broken = mock(ObjectStorage.class);
        when(broken.get(any())).thenThrow(new IllegalStateException("down"));
        assertThat(serviceWith(broken).list()).isEmpty();
    }
}
