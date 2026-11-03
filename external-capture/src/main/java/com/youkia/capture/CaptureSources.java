package com.youkia.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.adapters.*;
import java.util.*;

/** Registry used by both CLI and the database adapter. No framework or business dependency. */
public final class CaptureSources {
    private CaptureSources() {}
    public static List<SourceAdapter> adapters(ObjectMapper mapper) {
        return List.of(new AppleGamesAdapter(mapper), new AppBrainAdapter(),
                new GravityRankingAdapter("WECHAT"), new GravityRankingAdapter("DOUYIN"));
    }
    public static List<CaptureRequest> available(int top){
        var requests=new ArrayList<>(daily(top));
        for(String region:List.of("us","gb","de","fr"))for(String chart:StoreCharts.GOOGLE)requests.add(new CaptureRequest("appbrain",region,top,null,chart));
        return List.copyOf(requests);
    }
    public static List<CaptureRequest> daily(int top) {
        List<CaptureRequest> requests = new ArrayList<>();
        for(String chart:StoreCharts.APPLE)requests.add(new CaptureRequest("apple-games", "cn", top, null,chart));
        for (String chart : List.of("popularity", "bestseller", "most_played"))
            requests.add(new CaptureRequest("gravity-wechat", "cn", top, null, chart));
        for (String chart : List.of("popularity", "bestseller", "fresh_game", "publisher_heat"))
            requests.add(new CaptureRequest("gravity-douyin", "cn", top, null, chart));
        return List.copyOf(requests);
    }
}
