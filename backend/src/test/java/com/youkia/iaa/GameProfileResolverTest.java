package com.youkia.iaa;

import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic state checks: no market collection or real IAA claim. */
class GameProfileResolverTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ProfileFieldCatalog catalog=new ProfileFieldCatalog(mapper);
    final LocalDateTime end=LocalDateTime.of(2026,10,30,15,59);
    Map<String,Object> h(long id,Object value){var h=new LinkedHashMap<String,Object>();h.put("id",id);h.put("value",mapper.valueToTree(value));h.put("status","available");h.put("kind","manual");h.put("observedAt",end.minusDays(2));h.put("availableAt",end.minusDays(1));h.put("releaseId",1L);h.put("reviewed",true);h.put("rights","internal");return h;}
    Map<String,Object> resolve(String key,Map<String,Object>... rows){return GameProfileResolver.resolve(catalog.field(key),List.of(rows),end,1L);}
    Map<String,Map<String,Object>> iaa(){var f=new LinkedHashMap<String,Map<String,Object>>();f.put("mode",resolve("mode",h(1,"IAA")));f.put("no_iap_basis",resolve("no_iap_basis",h(2,"SYNTHETIC 目标版本完整核查记录")));f.put("monetization_valid",resolve("monetization_valid",h(3,"SYNTHETIC CN 版本1.0")));f.put("ad_formats",resolve("ad_formats",h(4,List.of("rewarded"))));return f;}
    @Test void catalogHas81FieldsAnd12Domains(){assertEquals(81,catalog.fields().size());assertEquals(12,catalog.document().path("domains").size());}
    @Test void jsonTypedIaaCanQualifyOnlyWithReviewedPackage(){assertEquals(true,GameProfileResolver.qualification(iaa()).get("eligible"));}
    @Test void platformScopesHaveIndependentQualification(){var wx=iaa();var ios=iaa();ios.put("mode",resolve("mode",h(7,"mixed")));assertEquals(true,GameProfileResolver.qualification(wx).get("eligible"));assertEquals(false,GameProfileResolver.qualification(ios).get("eligible"));}
    @Test void laterAvailabilityCannotChangeEarlierKnowledge(){var future=h(2,"mixed");future.put("availableAt",end.plusDays(1));assertEquals("IAA",GameProfileResolver.text(resolve("mode",h(1,"IAA"),future).get("value")));}
    @Test void futureObservationCannotLeak(){var row=h(1,"secret");row.put("observedAt",end.plusDays(1));var f=resolve("summary",row);assertEquals("not_yet_known",f.get("status"));assertNull(f.get("value"));}
    @Test void lateMaterialIsExcludedFromStrictHistoricalReplay(){var late=h(1,"SYNTHETIC late observation");late.put("availableAt",end.plusDays(1));assertEquals("not_yet_known",resolve("summary",late).get("status"));}
    @Test void laterKnowledgeCanReviseEarlierBusinessDayWithoutApplyingPresentExpiry(){
        var late=h(1,"SYNTHETIC late observation");late.put("availableAt",end.plusDays(1));late.put("validUntil",end.plusHours(1));
        var revised=GameProfileResolver.resolve(catalog.field("summary"),List.of(late),end,end.plusDays(1),1L);
        assertEquals("available",revised.get("status"));assertEquals("SYNTHETIC late observation",GameProfileResolver.text(revised.get("value")));
    }
    @Test void revisionStillExcludesObservationsAfterBusinessCutoff(){
        var future=h(1,"SYNTHETIC future fact");future.put("observedAt",end.plusHours(1));future.put("availableAt",end.plusDays(1));
        var revised=GameProfileResolver.resolve(catalog.field("summary"),List.of(future),end,end.plusDays(1),1L);
        assertEquals("not_yet_known",revised.get("status"));assertNull(revised.get("value"));
    }
    @Test void conflictingValidValuesRequireExplicitReview(){var one=h(1,"IAA");var two=h(2,"mixed");assertEquals("conflict",resolve("mode",one,two).get("status"));two.put("supersedesId",1L);assertEquals("mixed",GameProfileResolver.text(resolve("mode",one,two).get("value")));}
    @Test void futureSupersessionCannotHideKnownFact(){var one=h(1,"IAA");var two=h(2,"mixed");two.put("supersedesId",1L);two.put("availableAt",end.plusDays(1));assertEquals("IAA",GameProfileResolver.text(resolve("mode",one,two).get("value")));}
    @Test void releaseChangeInvalidatesSensitiveFacts(){var f=GameProfileResolver.resolve(catalog.field("mode"),List.of(h(1,"IAA")),end,2L);assertEquals("stale",f.get("status"));assertNull(f.get("value"));}
    @Test void independentlyRefreshedReleaseIsUsable(){var old=h(1,"IAA");var next=h(2,"IAA");next.put("releaseId",2L);assertEquals("available",GameProfileResolver.resolve(catalog.field("mode"),List.of(old,next),end,2L).get("status"));}
    @Test void expiryDoesNotTurnIntoZero(){var row=h(1,12);row.put("validUntil",end.minusSeconds(1));assertEquals("stale",resolve("rating_count",row).get("status"));assertNull(resolve("rating_count",row).get("value"));}
    @Test void zeroIsReadableAndReasonedNaLeavesDenominator(){var zero=h(1,0);var na=h(2,null);na.put("status","not_applicable");na.put("reason","不适用的授权范围");var f=Map.of("rating_count",resolve("rating_count",zero),"summary",resolve("summary",na));var c=GameProfileResolver.coverage(f);assertEquals(1L,c.get("readable"));assertEquals(1L,c.get("applicable"));}
    @Test void draftsAreCountedSeparatelyAndNeverPromoteIaa(){var f=iaa();var draft=h(1,"IAA");draft.put("kind","inference");f.put("mode",resolve("mode",draft));assertEquals(false,GameProfileResolver.qualification(f).get("eligible"));assertEquals(1L,GameProfileResolver.coverage(f).get("aiDrafts"));}
    @Test void syntheticPackageCannotBeCalledFormal(){var f=iaa();f.get("mode").put("isSynthetic",true);assertEquals(false,GameProfileResolver.qualification(f).get("eligible"));assertEquals("simulation",GameProfileResolver.qualification(f).get("status"));}
    @Test void draftCannotConcealStaleOrNegativeFact(){var old=h(1,"IAA");old.put("releaseId",2L);var draft=h(2,"IAA");draft.put("kind","inference");assertEquals("stale",resolve("mode",old,draft).get("status"));}
    @Test void iapEvidenceBlocksOtherwiseQualifiedPackage(){var f=iaa();f.put("iap_basis",resolve("iap_basis",h(5,"SYNTHETIC 购买入口及定价")));assertEquals(false,GameProfileResolver.qualification(f).get("eligible"));assertEquals("conflict",GameProfileResolver.qualification(f).get("status"));}
    @Test void emptyAdEvidenceAndUnmatchedReleaseDoNotCertify(){var f=iaa();f.put("ad_formats",resolve("ad_formats",h(5,List.of())));assertEquals(false,GameProfileResolver.qualification(f).get("eligible"));f=iaa();f.get("no_iap_basis").put("releaseId",2L);assertEquals(false,GameProfileResolver.qualification(f).get("eligible"));}
    @Test void ratingsCannotBeUsedAsReviewSample(){assertThrows(IllegalArgumentException.class,()->catalog.validate("sample",mapper.valueToTree(100),"available"));catalog.validate("sample",mapper.valueToTree(Map.of("count",0,"from","2026-10-20","to","2026-10-30","method","time_stratified","language","zh-CN")),"available");}
    @Test void validationRejectsFakeRankBadRangeAndUrls(){assertThrows(IllegalArgumentException.class,()->catalog.validate("rank",mapper.valueToTree(0),"available"));assertThrows(IllegalArgumentException.class,()->catalog.validate("effort",mapper.valueToTree(Map.of("lower",8,"upper",2)),"available"));assertThrows(IllegalArgumentException.class,()->catalog.validate("rating",mapper.valueToTree(0),"missing"));assertThrows(IllegalArgumentException.class,()->ProfileFieldCatalog.safeUrl("javascript:alert(1)"));assertThrows(IllegalArgumentException.class,()->ProfileFieldCatalog.safeUrl("https://user:secret@example.com"));}
    @Test void offsetsAreConvertedAndResolutionDoesNotMutateInput(){assertEquals(LocalDateTime.of(2026,10,30,2,0),GameProfileResolver.time("2026-10-30T10:00:00+08:00"));var row=h(1,"IAA");var before=new LinkedHashMap<>(row);resolve("mode",row);assertEquals(before,row);}
}
