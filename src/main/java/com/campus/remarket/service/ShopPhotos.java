package com.campus.remarket.service;

import com.campus.remarket.dto.ShopDto;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/** Published catalog images for known restaurants. Owner uploads take precedence. */
public final class ShopPhotos {
    public static final String MENJI_STOREFRONT = "26ee48ce-7c77-4e05-ad47-9bb4a2a6cead.png";
    private record ShopLocation(String name, String address) {}

    private static final Map<ShopLocation, String> CATALOG = Map.ofEntries(
        Map.entry(new ShopLocation("멘지", "인천 미추홀구 인하로77번길 6-19 1층 102호"), MENJI_STOREFRONT),
        dish("포케품은초밥 인하대점", "인천 미추홀구 인하로77번길 6-29 1층", "sushi-roll"),
        dish("양궈푸마라탕 인하대점", "인천 미추홀구 경인남길30번길 70 1층", "malatang"),
        dish("고수찜닭 본점", "인천 미추홀구 경인남길30번길 34 1층", "jjimdak"),
        dish("이태리부대찌개 인하대역점", "인천 미추홀구 소성로 16 인하아리스타 1층 121호", "budae-jjigae"),
        dish("압록순대국 송도본점", "인천 연수구 테크노파크로111번길 12 102호, 103호", "sundae-guk"),
        dish("지츠겐", "인천 연수구 하모니로 144 지웰푸르지오시티 A동 128호", "tonkatsu"),
        dish("오봉집 송도인천대입구역점", "인천 연수구 하모니로 158 송도타임스페이스 C동 1층 120,121호", "grilled-octopus"),
        dish("목향 중식당", "인천 연수구 하모니로178번길 6 중앙타워 건물 2층 201~206호", "dim-sum"),
        dish("나 그리고 너", "인천 연수구 하모니로 144 송도 지웰 푸르지오 1층 B동 106-107호", "pasta"),
        dish("국수나무 인천도화점", "인천 미추홀구 숙골로87번길 14 HS메디켈빌딩 102호", "soba"),
        dish("정정아식당", "인천 미추홀구 숙골로95번길 13 2층", "dak-bokkeum"),
        dish("담솥 앨리웨이 인천점", "인천 미추홀구 숙골로88번길 12 110동 3층 304~306호", "pot-rice"),
        dish("히노아지 앨리웨이인천점", "인천 미추홀구 숙골로88번길 12 앨리웨이 110동 3층 14호", "katsudon"),
        dish("백령면옥", "인천 미추홀구 숙골로87번길 5 204동 1층 1-04,05,06", "naengmyeon"),
        dish("시아우동", "인천 미추홀구 염전로202번길 48-35 1층 102호", "tempura-udon")
    );

    private ShopPhotos() {}

    private static Map.Entry<ShopLocation, String> dish(String name, String address, String slug) {
        return Map.entry(new ShopLocation(name, address), "menu-" + slug + ".jpg");
    }

    public static String forDisplay(ShopDto shop) {
        String uploaded = shop.getPhoto();
        if (uploaded != null && !uploaded.isBlank()) return uploaded;
        return CATALOG.get(new ShopLocation(shop.getShopname(), shop.getAddress()));
    }

    public static Resource bundled(String name) {
        if (name == null || !CATALOG.containsValue(name)) return null;
        Resource image = new ClassPathResource("shop-photos/" + name);
        return image.exists() ? image : null;
    }
}
