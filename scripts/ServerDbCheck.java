import java.sql.*;
import java.nio.file.*;
import java.util.*;
public class ServerDbCheck {
 public static void main(String[] args)throws Exception {
  var env=new HashMap<String,String>();for(String line:Files.readAllLines(Path.of("/etc/iaa-radar/app.env"))){int p=line.indexOf('=');if(p>0)env.put(line.substring(0,p),line.substring(p+1));}
  try(var c=DriverManager.getConnection(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));var st=c.createStatement()){
   for(String sql:List.of("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank","SELECT COUNT(*),SUM(table_comment='') FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'","SELECT COUNT(*),SUM(column_comment='') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'","SELECT c.platform,c.region,c.name,s.id,s.actual_count,s.completeness FROM ranking_snapshot s JOIN chart_definition c ON c.id=s.chart_id JOIN data_source d ON d.id=c.source_id WHERE d.access_method<>'SIMULATED'","SELECT id,period_start,period_end,revision FROM weekly_report ORDER BY id DESC LIMIT 3")){
    System.out.println(sql);try(var rs=st.executeQuery(sql)){while(rs.next()){for(int i=1;i<=rs.getMetaData().getColumnCount();i++)System.out.print(rs.getString(i)+" | ");System.out.println();}}
   }
  }
 }
}
