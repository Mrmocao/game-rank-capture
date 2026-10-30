import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Compare frozen rows with JdbcBackup SQL; print counts only, never business payloads or credentials. */
public class JdbcVerifyPreserved {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("usage: JdbcVerifyPreserved env-file backup.sql");
  var env=new HashMap<String,String>();for(String line:Files.readAllLines(Path.of(args[0]))){int p=line.indexOf('=');if(p>0&&!line.startsWith("#"))env.put(line.substring(0,p),line.substring(p+1));}
  List<String> tables=List.of("weekly_report","report_item","ranking_snapshot","ranking_entry");var expected=new HashMap<String,Set<String>>();tables.forEach(t->expected.put(t,new HashSet<>()));
  try(var lines=Files.lines(Path.of(args[1]),StandardCharsets.UTF_8)){lines.forEach(line->{for(String table:tables)if(line.startsWith("INSERT INTO `"+table+"` VALUES("))expected.get(table).add(line);});}
  long count=0;try(var c=DriverManager.getConnection(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"))){
   for(String table:tables){var remaining=expected.get(table);int before=remaining.size();try(var st=c.createStatement();var rs=st.executeQuery("SELECT * FROM `"+table+"`")){int columns=rs.getMetaData().getColumnCount();while(rs.next()){var row=new StringBuilder("INSERT INTO `"+table+"` VALUES(");for(int i=1;i<=columns;i++){if(i>1)row.append(',');String v=rs.getString(i);row.append(v==null?"NULL":v.isEmpty()?"''":"CONVERT(0x"+HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8))+" USING utf8mb4)");}row.append(");");remaining.remove(row.toString());}}if(!remaining.isEmpty())throw new IllegalStateException("Frozen rows differ: "+table+" ("+remaining.size()+")");count+=before;}
  }System.out.println("Preserved "+count+" frozen rows across "+tables.size()+" tables; contents not printed.");
 }
}
