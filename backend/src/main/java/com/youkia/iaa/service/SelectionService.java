package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.*;
import com.youkia.iaa.domain.BusinessDate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@Service
public class SelectionService {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final ProductQueryService products;
    public SelectionService(JdbcTemplate jdbc,ObjectMapper mapper,ProductQueryService products){this.jdbc=jdbc;this.mapper=mapper;this.products=products;}

    public Map<String,Object> list(Long productId,String owner,String status,int page,int size){
        StringBuilder w=new StringBuilder(" WHERE 1=1 "); List<Object> args=new ArrayList<>();
        if(productId!=null){w.append(" AND s.product_id=?");args.add(productId);} if(owner!=null&&!owner.isBlank()){w.append(" AND s.owner_name=?");args.add(owner);} if(status!=null&&!status.isBlank()){w.append(" AND s.status=?");args.add(status);}
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM selection_record s"+w,args.toArray(),Integer.class);
        args.add(size);args.add((long)(Math.max(page,1)-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.id,s.product_id,p.name product_name,s.reference_version_id,s.owner_name,s.status,s.selected_on,s.started_on,s.finished_on,s.note,s.created_by,s.updated_by,s.updated_at,s.version FROM selection_record s JOIN product p ON p.id=s.product_id"+w+" ORDER BY s.updated_at DESC LIMIT ? OFFSET ?",args.toArray());
        Map<String,Object> d=new LinkedHashMap<>();d.put("items",rows);d.put("total",total==null?0:total);d.put("page",page);d.put("pageSize",size);return d;
    }
    @Transactional
    public Map<String,Object> create(long productId,SelectionRequest req,String key){
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai")); LocalDate selected=req.selectedOn()==null?today:req.selectedOn(); BusinessDate.validateOrder(selected,req.startedOn(),req.finishedOn());
        String status=req.status()==null||req.status().isBlank()?"SELECTED":req.status(); validateStatus(status); validateVersion(productId,req.referenceVersionId());
        String actor="dev-user"; String payload=hash(req.toString());
        if(key!=null&&!key.isBlank()) { try { Map<String,Object> old=jdbc.queryForMap("SELECT request_sha256,response_json FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=?",actor,"CREATE_SELECTION",key); if(!payload.equals(old.get("request_sha256"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一幂等键的请求内容不同"); return mapper.convertValue(readJson((String)old.get("response_json")),Map.class); } catch(EmptyResultDataAccessException ignored){} }
        KeyHolder kh=new GeneratedKeyHolder(); jdbc.update(c->{PreparedStatement ps=c.prepareStatement("INSERT INTO selection_record(product_id,reference_version_id,owner_name,status,selected_on,started_on,finished_on,note,created_by,updated_by,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0)",Statement.RETURN_GENERATED_KEYS);int i=1;ps.setLong(i++,productId);if(req.referenceVersionId()==null)ps.setObject(i++,null);else ps.setLong(i++,req.referenceVersionId());ps.setString(i++,req.owner());ps.setString(i++,status);ps.setObject(i++,selected);ps.setObject(i++,req.startedOn());ps.setObject(i++,req.finishedOn());ps.setString(i++,req.note());ps.setString(i++,actor);ps.setString(i++,actor);ps.setObject(i++,LocalDateTime.now(ZoneOffset.UTC));ps.setObject(i,LocalDateTime.now(ZoneOffset.UTC));return ps;},kh);
        long id=Objects.requireNonNull(kh.getKey()).longValue(); Map<String,Object> out=jdbc.queryForMap("SELECT id,product_id,reference_version_id,owner_name,status,selected_on,started_on,finished_on,note,version FROM selection_record WHERE id=?",id);
        if(key!=null&&!key.isBlank()) { try {jdbc.update("INSERT INTO idempotency_record(actor_id,operation,request_key,request_sha256,status,response_json,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",actor,"CREATE_SELECTION",key,payload,"SUCCEEDED",mapper.writeValueAsString(out),LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.now(ZoneOffset.UTC).plusDays(1));}catch(Exception e){/* unique race is safe: caller can retry and read it */} }
        return out;
    }
    @Transactional
    public Map<String,Object> update(long id,SelectionPatch req){
        if(req.owner()!=null&&req.owner().isBlank())throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"OWNER_REQUIRED","负责人不能为空");
        Map<String,Object> old;try{old=jdbc.queryForMap("SELECT product_id,version,selected_on,owner_name,reference_version_id,status,started_on,finished_on,note FROM selection_record WHERE id=?",id);}catch(EmptyResultDataAccessException e){throw new ApiException(HttpStatus.NOT_FOUND,"SELECTION_NOT_FOUND","选用记录不存在");}
        if(((Number)old.get("version")).intValue()!=req.version())throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","记录已被其他人修改，请刷新后合并");
        long product=((Number)old.get("product_id")).longValue(); LocalDate selected=req.selectedOn()==null?(LocalDate)old.get("selected_on"):req.selectedOn(); BusinessDate.validateOrder(selected,req.startedOn(),req.finishedOn()); validateVersion(product,req.referenceVersionId()); String status=req.status()==null?(String)old.get("status"):req.status();validateStatus(status);
        int n=jdbc.update("UPDATE selection_record SET owner_name=COALESCE(?,owner_name),reference_version_id=?,status=?,selected_on=?,started_on=?,finished_on=?,note=?,updated_by=?,updated_at=?,version=version+1 WHERE id=? AND version=?",req.owner(),req.referenceVersionId(),status,selected,req.startedOn(),req.finishedOn(),req.note(),"dev-user",LocalDateTime.now(ZoneOffset.UTC),id,req.version()); if(n!=1)throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","记录已被其他人修改，请刷新后合并");
        var result=jdbc.queryForMap("SELECT id,product_id,reference_version_id,owner_name,status,selected_on,started_on,finished_on,note,version FROM selection_record WHERE id=?",id);
        try{jdbc.update("INSERT INTO audit_event(actor_id,action,entity_type,entity_id,before_json,after_json,created_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))","dev-user","UPDATE_SELECTION","selection_record",String.valueOf(id),mapper.writeValueAsString(old),mapper.writeValueAsString(result));}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
        return result;
    }
    private void validateVersion(long productId,Long version){if(version==null)return;Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM platform_version WHERE id=? AND product_id=?",Integer.class,version,productId);if(n==null||n==0)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_REFERENCE_VERSION","参考版本不属于该产品");}
    private void validateStatus(String s){if(!Set.of("SELECTED","IN_PROGRESS","COMPLETED","ABANDONED").contains(s))throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_STATUS","不支持的选用状态");}
    private String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Object readJson(String s){try{return mapper.readTree(s);}catch(Exception e){throw new IllegalStateException(e);}}
}
