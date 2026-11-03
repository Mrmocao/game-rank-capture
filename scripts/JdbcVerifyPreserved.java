import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;

/** Compare frozen rows with JdbcBackup SQL; print counts only, never business payloads or credentials. */
public class JdbcVerifyPreserved {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("usage: JdbcVerifyPreserved env-file backup.sql");
  var env=new HashMap<String,String>();for(String line:Files.readAllLines(Path.of(args[0]))){int p=line.indexOf('=');if(p>0&&!line.startsWith("#"))env.put(line.substring(0,p),line.substring(p+1));}
  List<String> tables=List.of("weekly_report","report_item","ranking_snapshot","ranking_entry");var expected=new HashMap<String,Set<String>>();tables.forEach(t->expected.put(t,new HashSet<>()));
  Set<String> definitions=new HashSet<>();String last="";
  try(var reader=Files.newBufferedReader(Path.of(args[1]),StandardCharsets.UTF_8)){
   String line;while((line=reader.readLine())!=null){if(!line.isBlank())last=line;
    for(String table:tables){if(line.startsWith("CREATE TABLE `"+table+"`")||line.startsWith("CREATE TABLE \""+table+"\""))definitions.add(table);
     if(line.startsWith("INSERT INTO `"+table+"` VALUES("))expected.get(table).add(hash(line));}
   }
  }
  if(!last.equals("SET FOREIGN_KEY_CHECKS=1;")||!definitions.containsAll(tables))throw new IllegalStateException("Incomplete backup; frozen-row verification refused");
  long count=0;try(var c=DriverManager.getConnection(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"))){
   for(String table:tables){var remaining=expected.get(table);int before=remaining.size();
    try(var st=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
     st.setFetchSize(Integer.MIN_VALUE);st.setQueryTimeout(120);
     try(var rs=st.executeQuery("SELECT * FROM `"+table+"`")){int columns=rs.getMetaData().getColumnCount();while(rs.next()){
      var digest=MessageDigest.getInstance("SHA-256");update(digest,"INSERT INTO `"+table+"` VALUES(");
      for(int i=1;i<=columns;i++){if(i>1)update(digest,",");String v=rs.getString(i);update(digest,v==null?"NULL":v.isEmpty()?"''":"CONVERT(0x"+HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8))+" USING utf8mb4)");}
      update(digest,");");remaining.remove(HexFormat.of().formatHex(digest.digest()));
     }}
    }
    if(!remaining.isEmpty())throw new IllegalStateException("Frozen rows differ: "+table+" ("+remaining.size()+")");count+=before;
   }
  }System.out.println("Preserved "+count+" frozen rows across "+tables.size()+" tables; contents not printed.");
 }
 private static String hash(String text)throws Exception{var digest=MessageDigest.getInstance("SHA-256");update(digest,text);return HexFormat.of().formatHex(digest.digest());}
 private static void update(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));}
}
