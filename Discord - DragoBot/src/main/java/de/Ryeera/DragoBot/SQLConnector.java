package de.Ryeera.DragoBot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class SQLConnector {

	private Connection conn = null;
	
	public SQLConnector (String address, int port, String user, String password, String database) {
		try {
			Class.forName("com.mysql.cj.jdbc.Driver").getConstructor().newInstance();
			conn = DriverManager.getConnection("jdbc:mysql://" + address + 
					":" + port + 
					"/" + database + 
					"?user=" + user + 
					"&password=" + password + 
					"&serverTimezone=UTC");
		} catch (SQLException e) {
			System.err.println("SQLState: " + e.getSQLState());
			System.err.println("VendorError: " + e.getErrorCode());
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public ResultSet executeQuery(String query) {
		Statement stmt = null;
		ResultSet rs = null;
		try {
		    stmt = conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
		    rs = stmt.executeQuery(query);
		    return rs;
		} catch (SQLException e) {
		    e.printStackTrace();
		    return null;
		}
	}
	
	public boolean executeUpdate(String sql) {
		Statement stmt = null;
		try {
			stmt = conn.createStatement();
			int rows = stmt.executeUpdate(sql);
			return rows > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		} finally {
			if (stmt != null) {
		        try {
		            stmt.close();
		        } catch (SQLException e) {}
		        stmt = null;
		    }
		}
	}
}
