import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;

/** Verify V23 preserves each week's winner and all non-report business rows. Run with writers stopped. */
public class JdbcWeeklyRetentionAudit {
 public static void main(String[] args)throws Exception{
  if(args.length!=3||!Set.of("before","after").contains(args[0]))throw new IllegalArgumentException("before|after env-file audit.properties");
  var env=new HashMap<String,String>();for(String line:Files.readAllLines(Path.of(args[1]))){int p=line.indexOf('=');if(p>0&&!line.startsWith("#"))env.put(line.substring(0,p),line.substring(p+1));}
  boolean before=args[0].equals("before");var expected=new Properties();Path file=Path.of(args[2]);
  if(!before)try(var in=Files.newInputStream(file)){expected.load(in);}
  var actual=new Properties();
  try(var c=DriverManager.getConnection(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"))){
   List<String> tables=new ArrayList<>();try(var st=c.createStatement();var rs=st.executeQuery("SHOW FULL TABLES WHERE Table_type='BASE TABLE'")){while(rs.next())tables.add(rs.getString(1));}
   tables.removeAll(List.of("weekly_report","report_item","weekly_report_alias","flyway_schema_history"));
   for(String t:tables){if(!t.matches("[a-zA-Z0-9_]+"))throw new IllegalStateException("invalid table");actual.setProperty("table."+t,hash(c,"SELECT * FROM `"+t+"`",false));}
   String winners=before?"SELECT id FROM (SELECT id,ROW_NUMBER() OVER(PARTITION BY DATE_SUB(DATE_SUB(period_end,INTERVAL 1 DAY),INTERVAL WEEKDAY(DATE_SUB(period_end,INTERVAL 1 DAY)) DAY) ORDER BY generated_at DESC,id DESC) n FROM weekly_report) r WHERE n=1 ORDER BY id":"SELECT id FROM weekly_report ORDER BY id";
   List<Long> ids=new ArrayList<>();try(var st=c.createStatement();var rs=st.executeQuery(winners)){while(rs.next())ids.add(rs.getLong(1));}
   actual.setProperty("weekly.ids",ids.toString());
   for(long id:ids){actual.setProperty("weekly."+id,hash(c,"SELECT * FROM weekly_report WHERE id="+id,true));actual.setProperty("items."+id,hash(c,"SELECT * FROM report_item WHERE report_id="+id,false));}
   if(!before){
    try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*),COUNT(DISTINCT week_start) FROM weekly_report")){rs.next();if(rs.getLong(1)!=rs.getLong(2))throw new IllegalStateException("duplicate weeks");}
    try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM weekly_report_alias a LEFT JOIN weekly_report r ON r.id=a.report_id WHERE r.id IS NULL")){rs.next();if(rs.getLong(1)!=0)throw new IllegalStateException("broken aliases");}
   }
  }
  if(before){try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){actual.store(out,"V23 migration fingerprints; no credentials or raw business data");}}
  else if(!expected.equals(actual)){var keys=new TreeSet<>(expected.stringPropertyNames());keys.addAll(actual.stringPropertyNames());keys.removeIf(k->Objects.equals(expected.get(k),actual.get(k)));throw new IllegalStateException("Migration mismatch: "+keys);}
  System.out.println((before?"Captured":"Verified")+" weekly winners "+actual.getProperty("weekly.ids")+" and "+actual.stringPropertyNames().stream().filter(k->k.startsWith("table.")).count()+" unchanged business tables");
 }
 private static String hash(Connection c,String sql,boolean skipWeek)throws Exception{
  var rows=new ArrayList<String>();
  try(var st=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
   st.setFetchSize(Integer.MIN_VALUE);st.setQueryTimeout(180);
   try(var rs=st.executeQuery(sql)){int columns=rs.getMetaData().getColumnCount();char[] buffer=new char[8192];byte[] bytes=new byte[16384];
    while(rs.next()){
     var digest=MessageDigest.getInstance("SHA-256");
     for(int i=1;i<=columns;i++){
      if(skipWeek&&rs.getMetaData().getColumnName(i).equals("week_start"))continue;
      int type=rs.getMetaData().getColumnType(i);
      boolean text=Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.CLOB,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.NCLOB).contains(type);
      String scalar=text?null:rs.getString(i);
      try(var reader=text?rs.getCharacterStream(i):scalar==null?null:new java.io.StringReader(scalar)){
       digest.update((byte)(reader==null?0:1));long count=0;
       if(reader!=null){int n;while((n=reader.read(buffer))!=-1){for(int k=0;k<n;k++){bytes[2*k]=(byte)(buffer[k]>>>8);bytes[2*k+1]=(byte)buffer[k];}digest.update(bytes,0,2*n);count+=n;}}
       digest.update(java.nio.ByteBuffer.allocate(8).putLong(count).array());
      }
     }rows.add(HexFormat.of().formatHex(digest.digest()));
    }
   }
  }
  Collections.sort(rows);var digest=MessageDigest.getInstance("SHA-256");for(String row:rows)digest.update(row.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  return rows.size()+":"+HexFormat.of().formatHex(digest.digest());
 }
}
