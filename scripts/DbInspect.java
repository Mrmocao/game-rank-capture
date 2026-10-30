import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Read-only inspection. Credentials are read locally and are never printed. */
public class DbInspect {
    public static void main(String[] args) throws Exception {
        Properties env=new Properties();
        for(String line:Files.readAllLines(Path.of(".env"),StandardCharsets.UTF_8)) {
            int split=line.indexOf('=');if(split>0&&!line.startsWith("#"))env.setProperty(line.substring(0,split).trim(),line.substring(split+1));
        }
        StringBuilder out=new StringBuilder("# MySQL 实际数据字典\n\n自动查询 information_schema；不包含配置值或业务凭据。\n\n");
        StringBuilder structure=new StringBuilder();
        int tables=0,columns=0,missing=0;
        try(Connection c=DriverManager.getConnection(env.getProperty("DB_URL"),env.getProperty("DB_USERNAME"),env.getProperty("DB_PASSWORD"))) {
            try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT table_name,table_comment FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name")) {
                while(rs.next()) {
                    String table=rs.getString(1),comment=rs.getString(2);tables++;if(comment==null||comment.isBlank())missing++;
                    out.append("## ").append(table).append(" — ").append(comment).append("\n\n|字段|类型|可空|默认|说明|\n|---|---|---|---|---|\n");
                    try(PreparedStatement ps=c.prepareStatement("SELECT column_name,column_type,is_nullable,column_default,extra,column_comment,collation_name,column_key FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position")) {
                        ps.setString(1,table);
                        try(ResultSet cr=ps.executeQuery()){while(cr.next()) {
                            columns++;String note=cr.getString(6);if(note==null||note.isBlank())missing++;
                            out.append('|').append(cr.getString(1)).append('|').append(cr.getString(2)).append('|').append(cr.getString(3)).append('|').append(cr.getString(4)).append('|').append(note).append("|\n");
                            structure.append(table);for(int n:new int[]{1,2,3,4,5,7,8})structure.append('|').append(cr.getString(n));structure.append('\n');
                        }}
                    }out.append('\n');
                }
            }
            try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM ranking_snapshot")){rs.next();System.out.println("snapshots="+rs.getInt(1));}
            try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")){while(rs.next())System.out.println("migration="+rs.getString(1)+" success="+rs.getBoolean(2));}
        }
        Path dir=Path.of("validation");Files.createDirectories(dir);
        String suffix=args.length==0?"after":args[0];
        Files.writeString(dir.resolve("database-dictionary-"+suffix+".md"),out,StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("database-structure-"+suffix+".txt"),structure,StandardCharsets.UTF_8);
        System.out.printf("tables=%d columns=%d missingComments=%d%n",tables,columns,missing);
    }
}
