import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.io.*;

/** Server-local consistent data backup. No MySQL CLI and no credentials in output. */
public class JdbcBackup {
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("usage: JdbcBackup env-file new-backup.sql");
  var env=new HashMap<String,String>();for(String line:Files.readAllLines(Path.of(args[0]))){int p=line.indexOf('=');if(p>0&&!line.startsWith("#"))env.put(line.substring(0,p),line.substring(p+1));}
  Path target=Path.of(args[1]);
  if(Files.exists(target))throw new FileAlreadyExistsException(target.toString());
  Path partial=target.resolveSibling(target.getFileName()+".partial");
  long rows=0;int tableCount;
  try(var out=Files.newBufferedWriter(partial,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);var c=DriverManager.getConnection(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"))){
   c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);c.setAutoCommit(false);
   out.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n");
   List<String> tables=new ArrayList<>();try(var st=c.createStatement();var rs=st.executeQuery("SHOW FULL TABLES WHERE Table_type='BASE TABLE'")){while(rs.next())tables.add(rs.getString(1));}
   tableCount=tables.size();for(String table:tables){if(!table.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("unexpected table name");
    try(var st=c.createStatement();var rs=st.executeQuery("SHOW CREATE TABLE `"+table+"`")){rs.next();out.write(rs.getString(2)+";\n");}
    // Connector/J streams one row at a time instead of buffering every frozen report in memory.
    try(var st=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
     st.setFetchSize(Integer.MIN_VALUE);st.setQueryTimeout(120);
     try(var rs=st.executeQuery("SELECT * FROM `"+table+"`")){int columns=rs.getMetaData().getColumnCount();
     while(rs.next()){out.write("INSERT INTO `"+table+"` VALUES(");for(int i=1;i<=columns;i++){if(i>1)out.write(',');String v=rs.getString(i);out.write(v==null?"NULL":v.isEmpty()?"''":"CONVERT(0x"+HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8))+" USING utf8mb4)");}out.write(");\n");rows++;}}
    }
   }
   out.write("SET FOREIGN_KEY_CHECKS=1;\n");c.commit();
  }
  Files.move(partial,target);
  System.out.println("Backup complete: "+tableCount+" tables, "+rows+" rows; file contents not printed.");
 }
}
