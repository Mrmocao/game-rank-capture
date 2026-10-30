package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.collection.AppleGamesService;
import com.youkia.iaa.api.ApiException;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class AppleGamesValidationTest {
    private final AppleGamesService service=new AppleGamesService(null,new ObjectMapper(),"unused");
    private String material(String entries){return """
        {"feed":{"id":{"label":"https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json"},
        "updated":{"label":"2026-01-02T01:00:00+08:00"},"entry":%s}}
        """.formatted(entries);}
    private final String row="""
        {"id":{"attributes":{"im:id":"123"}},"im:name":{"label":"Fixture game"},"category":{"attributes":{"im:id":"6014"}}}
        """;
    @Test void missingAndDuplicateIdsDoNotPublish(){
        assertThrows(ApiException.class,()->service.validate("cn",100,null,material("[{},{}]")));
        assertThrows(ApiException.class,()->service.validate("cn",100,null,material("["+row+","+row+"]")));
    }
    @Test void emptyResponseDoesNotBecomeSuccessfulZeroSnapshot(){
        assertThrows(ApiException.class,()->service.validate("cn",100,null,material("[]")));
        assertThrows(ApiException.class,()->service.validate("cn",100,null,""));
    }
    @Test void countryAndDateCannotBeRelabeled(){
        assertThrows(ApiException.class,()->service.validate("us",100,null,material("["+row+"]")));
        assertThrows(ApiException.class,()->service.validate("cn",100,LocalDate.of(2026,1,1),material("["+row+"]")));
    }
    @Test void partialIsPreservedAndBusinessDateUsesShanghai(){
        var m=service.validate("cn",100,null,material("["+row+"]"));
        assertEquals(1,m.entries().size());assertEquals(100,m.top());assertEquals(LocalDate.of(2026,1,2),m.businessDate());
    }
    @Test void nonGamesAreRejected(){
        assertThrows(ApiException.class,()->service.validate("cn",100,null,material("["+row.replace("6014","6000")+"]")));
    }
}
