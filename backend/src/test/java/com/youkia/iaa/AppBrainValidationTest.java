package com.youkia.iaa;
import com.youkia.iaa.collection.AppBrainService;
import com.youkia.iaa.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AppBrainValidationTest {
 private final AppBrainService service=new AppBrainService(null,new ObjectMapper(),"unused");
 private String page(String rows){return "<link rel='canonical' href='https://www.appbrain.com/stats/google-play-rankings/top_free/game/us'><div class='stats-pagetop-updated'><time>October 27, 2026</time></div><table id='rankings-table'><tbody>"+rows+"</tbody></table>";}
 private String row(int rank,String id){return "<tr><td class='ranking-rank'>"+rank+"</td><td>=</td><td></td><td class='ranking-app-cell'><a href='/app/fixture/"+id+"'>Fixture Puzzle</a><div class='ranking-app-cell-creator'><a>Fixture Studio</a></div></td><td class='ranking-rating-cell'><span>4.7</span></td><td>2 M</td><td>200 K</td></tr>";}
 @Test void gapsArePreservedAndEstimatesRemainSourceStrings(){var m=service.validate("us",page(row(1,"com.fixture.a")+row(3,"com.fixture.b")));assertEquals(2,m.rows().size());assertEquals(3,m.rows().getLast().rank());assertEquals("200 K",m.rows().getFirst().recent());}
 @Test void wrongCountryAndLoginPageRejected(){assertThrows(ApiException.class,()->service.validate("gb",page(row(1,"com.fixture.a"))));assertThrows(ApiException.class,()->service.validate("us","<html>Login required</html>"));}
 @Test void duplicatesAndInvalidRankRejected(){assertThrows(ApiException.class,()->service.validate("us",page(row(1,"com.fixture.a")+row(1,"com.fixture.b"))));assertThrows(ApiException.class,()->service.validate("us",page(row(0,"com.fixture.a"))));}
}
