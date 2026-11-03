package com.youkia.capture;
import java.util.*;

/** Explicit store chart scopes, never interchangeable rank scales. */
public final class StoreCharts {
    private StoreCharts(){}
    public static final List<String> APPLE=List.of("top-free","top-paid","top-grossing");
    public static final List<String> GOOGLE=List.of("top-free","top-paid","top-grossing","top-new-free","top-new-paid");
    public static String label(String chart){return switch(chart){case "top-free"->"游戏免费榜";case "top-paid"->"游戏付费榜";case "top-grossing"->"游戏畅销榜";case "top-new-free"->"新免费游戏榜";case "top-new-paid"->"新付费游戏榜";default->throw new CaptureException("INVALID_SCOPE","不支持的商店榜单");};}
    public static String appleFeed(String chart){return switch(chart){case "top-free"->"topfreeapplications";case "top-paid"->"toppaidapplications";case "top-grossing"->"topgrossingapplications";default->throw new CaptureException("INVALID_SCOPE","不支持的Apple榜单");};}
    public static String rankBasis(String chart){return "STORE_"+chart.toUpperCase(Locale.ROOT).replace('-','_')+"_RANK";}
}
