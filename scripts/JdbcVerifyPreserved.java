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
  // Backup INSERT lines can exceed 100 MB. Hash bytes in bounded chunks instead of readLine().
  try(var in=Files.newInputStream(Path.of(args[1]))){
   byte[] buffer=new byte[65536];var prefix=new java.io.ByteArrayOutputStream(128);var digest=MessageDigest.getInstance("SHA-256");int length;
   while((length=in.read(buffer))!=-1){int start=0;for(int i=0;i<length;i++)if(buffer[i]=='\n'){
    digest.update(buffer,start,i-start);if(prefix.size()<128)prefix.write(buffer,start,Math.min(i-start,128-prefix.size()));
    String head=prefix.toString(StandardCharsets.UTF_8);if(!head.isBlank())last=head;
    String hash=HexFormat.of().formatHex(digest.digest());
    for(String table:tables){if(head.startsWith("CREATE TABLE `"+table+"`")||head.startsWith("CREATE TABLE \""+table+"\""))definitions.add(table);
     if(head.startsWith("INSERT INTO `"+table+"` VALUES("))expected.get(table).add(hash);}
    prefix.reset();start=i+1;
   }digest.update(buffer,start,length-start);if(prefix.size()<128)prefix.write(buffer,start,Math.min(length-start,128-prefix.size()));}
   if(prefix.size()!=0)throw new IllegalStateException("Backup missing final newline");
  }
  if(!last.equals("SET FOREIGN_KEY_CHECKS=1;")||!definitions.containsAll(tables))throw new IllegalStateException("Incomplete backup; frozen-row verification refused");
  long count=0;try(var c=DriverManager.getConnection(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"))){
   for(String table:tables){var remaining=expected.get(table);int before=remaining.size();
    try(var st=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
     st.setFetchSize(Integer.MIN_VALUE);st.setQueryTimeout(120);
     try(var rs=st.executeQuery("SELECT * FROM `"+table+"`")){int columns=rs.getMetaData().getColumnCount();while(rs.next()){
      var digest=MessageDigest.getInstance("SHA-256");update(digest,"INSERT INTO `"+table+"` VALUES(");
      for(int i=1;i<=columns;i++){if(i>1)update(digest,",");String v=rs.getString(i);value(digest,v);}
      update(digest,");");remaining.remove(HexFormat.of().formatHex(digest.digest()));
     }}
    }
    if(!remaining.isEmpty())throw new IllegalStateException("Frozen rows differ: "+table+" ("+remaining.size()+")");count+=before;
   }
  }System.out.println("Preserved "+count+" frozen rows across "+tables.size()+" tables; contents not printed.");
 }
 private static void value(MessageDigest digest,String text){
  if(text==null){update(digest,"NULL");return;}if(text.isEmpty()){update(digest,"''");return;}
  update(digest,"CONVERT(0x");
  for(int start=0;start<text.length();){int end=Math.min(text.length(),start+8192);
   if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
   update(digest,HexFormat.of().formatHex(text.substring(start,end).getBytes(StandardCharsets.UTF_8)));start=end;
  }update(digest," USING utf8mb4)");
 }
 private static void update(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));}
}
